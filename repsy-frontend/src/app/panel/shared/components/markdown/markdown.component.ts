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

    const html = this.mdConverter.makeHtml(markdown);
    const safeHtml = this.sanitizer.sanitize(SecurityContext.HTML, html);
    this.markdownHtml = this.sanitizer.bypassSecurityTrustHtml(safeHtml || '');
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
