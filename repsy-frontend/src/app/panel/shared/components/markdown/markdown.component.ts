///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnInit,
  SecurityContext,
  ViewChild,
  ViewEncapsulation,
} from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Converter } from 'showdown';
import showdownHighlight from 'showdown-highlight';

/** Elements that fetch or embed remote content. They have no place in a panel-rendered README. */
const REMOVED_ELEMENTS = 'source, video, audio, track, link, object, embed, iframe, area, noscript';

/** Attributes that can trigger a request (or hide one in CSS) without going through src or href. */
const REMOVED_ATTRIBUTES = ['style', 'background', 'poster', 'srcset'];

const EXTERNAL_LINK_REL = 'noopener noreferrer nofollow';

/**
 * How a URL found in a README resolves in the browser:
 * - `external`: http(s) or protocol-relative, so it points at a publisher-chosen host.
 * - `data-image`: an inline image, which needs no request.
 * - `scheme`: any other scheme (mailto:, javascript:, ...); left to Angular's sanitiser.
 * - `relative`: resolves against the panel's own origin, so it is meaningless here.
 */
type UrlKind = 'external' | 'data-image' | 'scheme' | 'relative';

interface ClassifiedUrl {
  kind: UrlKind;
  /** The URL with whitespace and control characters removed and backslashes read as slashes. */
  url: string;
}

@Component({
  selector: 'app-markdown',
  templateUrl: './markdown.component.html',
  styleUrls: ['./markdown.component.css'],
  encapsulation: ViewEncapsulation.None,
})
export class MarkdownComponent implements OnInit, AfterViewInit {
  @Input() public markdown: string;
  public markdownHtml: SafeHtml;

  private readonly mdConverter: Converter;

  @ViewChild('container') containerRef: ElementRef;

  constructor(private readonly sanitizer: DomSanitizer) {
    this.mdConverter = new Converter({
      extensions: showdownHighlight({}),
      // README files (RPS-1006) routinely use tables, strikethrough and links out of the panel.
      tables: true,
      strikethrough: true,
      openLinksInNewWindow: true,
    });
  }

  ngOnInit(): void {
    this.markdownToHtml(this.markdown);
  }

  ngAfterViewInit(): void {
    this.addCopyButtons();
  }

  private markdownToHtml(markdown: string): void {
    if (!markdown) {
      return;
    }

    // README content is written by the package publisher, so it must not make the viewer's browser
    // request a publisher-chosen host, nor link into the panel's own routes. Angular's sanitiser
    // below stays the last line of defence against script injection.
    const html = this.restrictRemoteContent(this.mdConverter.makeHtml(markdown));
    const safeHtml = this.sanitizer.sanitize(SecurityContext.HTML, html);
    this.markdownHtml = this.sanitizer.bypassSecurityTrustHtml(safeHtml || '');
  }

  /**
   * Blocks external images (a tracking pixel fires on page open), drops elements that fetch remote
   * content, and turns links that would resolve against the panel's origin into plain text.
   * The HTML is parsed with DOMParser, which builds an inert document: nothing is loaded from it.
   */
  private restrictRemoteContent(html: string): string {
    const doc = new DOMParser().parseFromString(html, 'text/html');

    doc.querySelectorAll(REMOVED_ELEMENTS).forEach((element) => element.remove());
    doc.querySelectorAll('picture').forEach((picture) => picture.replaceWith(...Array.from(picture.childNodes)));
    doc.querySelectorAll('img').forEach((img) => this.restrictImage(doc, img));
    doc.querySelectorAll('a[href]').forEach((anchor) => this.restrictLink(anchor));
    doc.querySelectorAll(REMOVED_ATTRIBUTES.map((name) => `[${name}]`).join(', ')).forEach((element) => {
      REMOVED_ATTRIBUTES.forEach((name) => element.removeAttribute(name));
    });

    return doc.body.innerHTML;
  }

  /** Keeps inline data:image/* pictures. Any other image is replaced by its alt text (and a link, if external). */
  private restrictImage(doc: Document, img: HTMLImageElement): void {
    const { kind, url } = this.classifyUrl(img.getAttribute('src'));
    if (kind === 'data-image') {
      return;
    }

    const replacement: Node[] = [];
    const alt = img.getAttribute('alt')?.trim();
    if (alt) {
      const caption = doc.createElement('span');
      caption.className = 'blocked-image';
      caption.textContent = alt;
      replacement.push(caption);
    }
    // An image inside a link is usually a badge; a second, nested anchor would be invalid HTML.
    if (kind === 'external' && !img.closest('a')) {
      const link = doc.createElement('a');
      link.setAttribute('href', url);
      link.setAttribute('target', '_blank');
      link.setAttribute('rel', EXTERNAL_LINK_REL);
      link.textContent = url;
      replacement.push(doc.createTextNode(' '), link);
    }
    img.replaceWith(...replacement);
  }

  /** Relative links become text, external links open in a new tab without leaking the panel. */
  private restrictLink(anchor: Element): void {
    const { kind, url } = this.classifyUrl(anchor.getAttribute('href'));
    if (kind === 'relative') {
      anchor.replaceWith(...Array.from(anchor.childNodes));
    } else if (kind === 'external') {
      anchor.setAttribute('href', url);
      anchor.setAttribute('target', '_blank');
      anchor.setAttribute('rel', EXTERNAL_LINK_REL);
    }
  }

  /**
   * Browsers ignore leading and trailing whitespace, tabs and newlines inside a URL, read a backslash
   * as a slash, and match schemes case-insensitively; the classification does the same so that
   * ` HTTPS://x` or `/\x` cannot slip past. Attribute values are already entity-decoded by the parser.
   */
  private classifyUrl(raw: string | null): ClassifiedUrl {
    const url = Array.from(raw ?? '')
      .filter((char) => char.charCodeAt(0) > 32 && char.charCodeAt(0) !== 127 && !/\s/.test(char))
      .join('')
      .replace(/\\/g, '/');
    const lower = url.toLowerCase();

    if (lower.startsWith('//') || /^https?:/.test(lower)) {
      return { kind: 'external', url };
    }
    if (lower.startsWith('data:image/')) {
      return { kind: 'data-image', url };
    }
    if (/^[a-z][a-z0-9+.-]*:/.test(lower)) {
      return { kind: 'scheme', url };
    }
    return { kind: 'relative', url };
  }

  private addCopyButtons(): void {
    const container = this.containerRef?.nativeElement as HTMLElement;
    if (!container) {
      return;
    }

    const preBlocks = container.querySelectorAll('pre');

    preBlocks.forEach((pre) => {
      const button = document.createElement('button');
      button.className = 'copy-button h-4 w-4 text-[#9FA0A0] hover:text-secondary-500';

      // Initial copy icon SVG (without Angular directive - this is vanilla JS)
      const copyIconSVG =
        '<svg class="mt-0.5" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" xmlns="http://www.w3.org/2000/svg">' +
        '<path d="M12.4 6H7.6C7.17565 6 6.76869 6.16857 6.46863 6.46863C6.16857 6.76869 6 7.17565 6 7.6V12.4C6 12.8243 6.16857 13.2313 6.46863 13.5314C6.76869 13.8314 7.17565 14 7.6 14H12.4C12.8243 14 13.2313 13.8314 13.5314 13.5314C13.8314 13.2313 14 12.8243 14 12.4V7.6C14 7.17565 13.8314 6.76869 13.5314 6.46863C13.2313 6.16857 12.8243 6 12.4 6Z" stroke-width="1.5" stroke-miterlimit="10" />' +
        '<path d="M4 10H3.6C2.71333 10 2 9.28667 2 8.4V3.6C2 2.71333 2.71333 2 3.6 2H8.4C9.28667 2 10 2.71333 10 3.6V4" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" />' +
        '</svg>';

      // Success checkmark SVG
      const successIconSVG =
        '<svg width="16" height="12" viewBox="0 0 16 12" fill="none" xmlns="http://www.w3.org/2000/svg">' +
        '<path d="M1 5.34547L6.05556 10.4364L14.9311 1.5625" stroke="#69FFB4" stroke-width="1.5" stroke-linecap="round" />' +
        '</svg>';

      button.innerHTML = copyIconSVG;

      button.addEventListener('click', () => {
        const code = pre.querySelector('code');
        if (code?.textContent) {
          navigator.clipboard.writeText(code.textContent);

          // Show success state
          button.className = 'copy-button h-4 w-4';
          button.innerHTML = successIconSVG;

          // Reset to original state after 2 seconds
          setTimeout(() => {
            button.className = 'copy-button h-4 w-4 text-[#9FA0A0] hover:text-secondary-500';
            button.innerHTML = copyIconSVG;
          }, 2000);
        }
      });

      // Insert button before the pre element
      pre.parentNode.insertBefore(button, pre);
    });
  }
}
