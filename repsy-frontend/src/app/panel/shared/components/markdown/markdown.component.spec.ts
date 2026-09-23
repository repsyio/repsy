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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MarkdownComponent } from './markdown.component';

describe('MarkdownComponent', () => {
  function render(markdown: string): HTMLElement {
    const fixture: ComponentFixture<MarkdownComponent> = TestBed.createComponent(MarkdownComponent);
    fixture.componentRef.setInput('markdown', markdown);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MarkdownComponent] });
  });

  it('renders headings, emphasis and code blocks', () => {
    const el = render('# Title\n\nSome **bold** text\n\n```bash\ndotnet add package Foo\n```\n');

    expect(el.querySelector('h1')?.textContent).toBe('Title');
    expect(el.querySelector('strong')?.textContent).toBe('bold');
    expect(el.querySelector('pre code')?.textContent).toContain('dotnet add package Foo');
  });

  it('renders GitHub-style tables', () => {
    const el = render('| Name | Value |\n| --- | --- |\n| a | 1 |\n');

    expect(el.querySelectorAll('table th').length).toBe(2);
    expect(el.querySelector('table td')?.textContent).toBe('a');
  });

  it('opens links in a new window', () => {
    const el = render('[Repsy](https://repsy.io)');

    const link = el.querySelector('a');
    expect(link?.getAttribute('href')).toBe('https://repsy.io');
    expect(link?.getAttribute('target')).toBe('_blank');
  });

  it('renders an image', () => {
    const el = render('![Repsy logo](data:image/gif;base64,R0lGODlhAQABAAAAACw=)');

    expect(el.querySelector('img')?.getAttribute('src')).toBe('data:image/gif;base64,R0lGODlhAQABAAAAACw=');
    expect(el.querySelector('img')?.getAttribute('alt')).toBe('Repsy logo');
  });

  it('renders GFM strikethrough', () => {
    const el = render('~~deprecated~~ still here');

    expect(el.querySelector('del')?.textContent).toBe('deprecated');
  });

  it('highlights a fenced code block with a known language', () => {
    const el = render('```js\nconst x = 1;\n```\n');

    const code = el.querySelector('pre code');
    expect(code?.classList.contains('hljs')).toBeTrue();
    expect(code?.classList.contains('language-js')).toBeTrue();
    expect(code?.querySelector('[class^="hljs-"]')).not.toBeNull();
  });

  it('renders a large, pathological README within a time budget (anti-ReDoS regression)', () => {
    // showdown's link/anchor handling (CVE-2024-1899, Dependabot alert 57) could hang the tab on a
    // crafted README. This repeats link-like, unbalanced bracket sequences at scale alongside a
    // large body of ordinary content; marked must not exhibit the same catastrophic backtracking.
    const pathological = '[!['.repeat(20000) + '\n\n' + '# Heading\n\nSome ordinary paragraph text.\n\n'.repeat(2000);

    const start = performance.now();
    const el = render(pathological);
    const elapsed = performance.now() - start;

    expect(elapsed).toBeLessThan(5000);
    expect(el.querySelectorAll('h1').length).toBeGreaterThan(0);
  });

  it('strips script elements', () => {
    const el = render('Hello\n\n<script>window.__pwned = true</script>\n');

    expect(el.querySelector('script')).toBeNull();
    expect(el.textContent).not.toContain('__pwned');
  });

  it('strips inline event handlers', () => {
    const el = render('<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" onerror="window.__pwned = true">');

    expect(el.querySelector('img')?.hasAttribute('onerror') ?? false).toBeFalse();
    expect(el.innerHTML).not.toContain('onerror');
  });

  it('neutralises javascript: URLs', () => {
    const el = render('[click me](javascript:alert(1))');

    // Angular prefixes an unsafe scheme with "unsafe:", which browsers do not execute.
    expect(el.querySelector('a')?.getAttribute('href')).toMatch(/^unsafe:/);
  });

  it('strips iframes and forms', () => {
    const el = render('<iframe src="https://example.com"></iframe>\n\n<form action="/x"><input name="a"></form>\n');

    expect(el.querySelector('iframe')).toBeNull();
    expect(el.querySelector('form')).toBeNull();
    expect(el.querySelector('input')).toBeNull();
  });

  describe('remote content (RPS-1067)', () => {
    const PIXEL = 'data:image/gif;base64,R0lGODlhAQABAAAAACw=';

    /** Every attribute outside an anchor that could make the browser request a remote URL. */
    function remoteReferences(el: HTMLElement): string[] {
      const found: string[] = [];
      el.querySelectorAll('*').forEach((element) => {
        Array.from(element.attributes).forEach((attr) => {
          if (/^\s*(https?:)?\/\//i.test(attr.value) || /https?:/i.test(attr.value) || /url\(/i.test(attr.value)) {
            if (element.tagName !== 'A') {
              found.push(`${element.tagName.toLowerCase()}[${attr.name}]=${attr.value}`);
            }
          }
        });
      });
      return found;
    }

    function expectNoImageRequest(el: HTMLElement): void {
      expect(el.querySelectorAll('img').length).toBe(0);
      expect(remoteReferences(el)).toEqual([]);
    }

    it('replaces an external https image by its alt text and a link', () => {
      const el = render('![Build status](https://tracker.example/pixel.png)');

      expectNoImageRequest(el);
      expect(el.querySelector('.blocked-image')?.textContent).toBe('Build status');
      const link = el.querySelector('a');
      expect(link?.getAttribute('href')).toBe('https://tracker.example/pixel.png');
      expect(link?.getAttribute('target')).toBe('_blank');
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer nofollow');
    });

    it('blocks an http image written as HTML', () => {
      const el = render('<img src="http://tracker.example/p.gif" alt="hi" width="1" height="1">');

      expectNoImageRequest(el);
      expect(el.textContent).toContain('hi');
    });

    it('blocks a protocol-relative image', () => {
      const el = render('<img src="//tracker.example/x.png" alt="x">');

      expectNoImageRequest(el);
      expect(el.querySelector('a')?.getAttribute('href')).toBe('//tracker.example/x.png');
    });

    it('blocks an image whose remote URL hides in srcset', () => {
      const el = render(
        `<img src="${PIXEL}" srcset="https://tracker.example/a.png 1x, https://tracker.example/b.png 2x">`,
      );

      expect(el.querySelectorAll('img').length).toBe(1);
      expect(el.querySelector('img')?.hasAttribute('srcset')).toBeFalse();
      expect(remoteReferences(el)).toEqual([]);
    });

    it('blocks an image with only a srcset', () => {
      const el = render('<img srcset="https://tracker.example/a.png 1x" alt="a">');

      expectNoImageRequest(el);
    });

    it('blocks an upper-case scheme', () => {
      const el = render('<img src="HTTPS://tracker.example/p.png" alt="x">');

      expectNoImageRequest(el);
    });

    it('blocks a URL with leading whitespace or an embedded tab', () => {
      const el = render(
        '<img src=" https://tracker.example/p.png" alt="a">\n\n<img src="ht&#9;tps://tracker.example/q.png" alt="b">',
      );

      expectNoImageRequest(el);
    });

    it('blocks an entity-obfuscated scheme', () => {
      const el = render(
        '<img src="&#104;ttps&colon;//tracker.example/p.png" alt="x"><img src="&#x68;&#x74;tp://tracker.example/q.png">',
      );

      expectNoImageRequest(el);
    });

    it('reads a backslash as a slash', () => {
      const el = render('<img src="/\\tracker.example/p.png" alt="a"><img src="\\\\tracker.example/q.png" alt="b">');

      expectNoImageRequest(el);
    });

    it('blocks an image with another scheme', () => {
      const el = render(
        '<img src="ftp://tracker.example/p.png" alt="ftp"><img src="blob:https://tracker.example/1" alt="blob">',
      );

      expectNoImageRequest(el);
      expect(el.querySelector('a')).toBeNull();
    });

    it('shows the alt text of a relative image instead of resolving it against the panel', () => {
      const el = render('![Logo](images/logo.png)');

      expectNoImageRequest(el);
      expect(el.querySelector('.blocked-image')?.textContent).toBe('Logo');
      expect(el.querySelector('a')).toBeNull();
    });

    it('drops a relative image without alt text', () => {
      const el = render('Before <img src="/static/logo.png"> after');

      expectNoImageRequest(el);
      expect(el.textContent?.trim()).toBe('Before  after');
    });

    it('keeps an inline data:image picture', () => {
      const el = render(`<img src="${PIXEL}" alt="dot">`);

      expect(el.querySelectorAll('img').length).toBe(1);
      expect(el.querySelector('img')?.getAttribute('src')).toBe(PIXEL);
    });

    it('does not nest a second link into a badge that is already a link', () => {
      const el = render('[![CI](https://ci.example/badge.svg)](https://ci.example/run)');

      expectNoImageRequest(el);
      expect(el.querySelectorAll('a').length).toBe(1);
      expect(el.querySelector('a')?.getAttribute('href')).toBe('https://ci.example/run');
      expect(el.querySelector('a .blocked-image')?.textContent).toBe('CI');
    });

    it('unwraps a picture element and blocks its sources', () => {
      const el = render(
        '<picture><source srcset="https://tracker.example/dark.png" media="(prefers-color-scheme: dark)">' +
          '<img src="https://tracker.example/light.png" alt="logo"></picture>',
      );

      expectNoImageRequest(el);
      expect(el.querySelector('picture')).toBeNull();
      expect(el.querySelector('source')).toBeNull();
      expect(el.textContent).toContain('logo');
    });

    it('removes video, audio and other elements that fetch remote content', () => {
      const el = render(
        '<video src="https://tracker.example/v.mp4" poster="https://tracker.example/p.png" controls></video>\n\n' +
          '<audio src="https://tracker.example/a.mp3"></audio>\n\n' +
          '<object data="https://tracker.example/o.swf"></object><embed src="https://tracker.example/e.swf">' +
          '<map name="m"><area href="https://tracker.example/" shape="rect" coords="0,0,1,1"></map>' +
          '<noscript><img src="https://tracker.example/n.png"></noscript>',
      );

      ['video', 'audio', 'track', 'object', 'embed', 'area', 'noscript', 'img'].forEach((tag) => {
        expect(el.querySelector(tag)).withContext(tag).toBeNull();
      });
      expect(remoteReferences(el)).toEqual([]);
    });

    it('removes style attributes that could load a background image', () => {
      const el = render('<p style="background:url(https://tracker.example/bg.png)">text</p>');

      expect(el.querySelector('p')?.textContent).toBe('text');
      expect(el.querySelector('[style]')).toBeNull();
      expect(el.innerHTML).not.toContain('tracker.example');
    });

    it('removes the legacy background attribute of table cells', () => {
      const el = render(
        '<table background="https://tracker.example/t.png"><tr><td background="https://tracker.example/c.png">x</td></tr></table>',
      );

      expect(el.querySelector('td')?.textContent).toBe('x');
      expect(remoteReferences(el)).toEqual([]);
    });

    it('turns a relative link into plain text', () => {
      const el = render('See the [usage guide](docs/usage.md), [root](/admin) and [parent](../x.md).');

      expect(el.querySelector('a')).toBeNull();
      expect(el.textContent).toContain('See the usage guide, root and parent.');
    });

    it('turns a fragment link into plain text', () => {
      const el = render('Jump to [install](#install) or <a href="">empty</a>.');

      expect(el.querySelector('a')).toBeNull();
      expect(el.textContent).toContain('Jump to install or empty.');
    });

    it('keeps the formatting inside a relative link', () => {
      const el = render('[**bold** text](docs/usage.md)');

      expect(el.querySelector('a')).toBeNull();
      expect(el.querySelector('strong')?.textContent).toBe('bold');
    });

    it('opens absolute links in a new tab without opener, referrer or search-engine credit', () => {
      const el = render(
        '<a href="https://repsy.io" target="_self" rel="opener">a</a> [b](//example.com/x) [c](HTTP://example.com)',
      );

      const links = Array.from(el.querySelectorAll('a'));
      expect(links.length).toBe(3);
      links.forEach((link) => {
        expect(link.getAttribute('target')).toBe('_blank');
        expect(link.getAttribute('rel')).toBe('noopener noreferrer nofollow');
      });
    });

    it('leaves mailto links alone', () => {
      const el = render('[mail](mailto:team@example.com)');

      expect(el.querySelector('a')?.getAttribute('href')).toBe('mailto:team@example.com');
    });

    it('does not change ordinary README content', () => {
      const el = render('# Title\n\nText with `code` and a [link](https://repsy.io).\n\n- one\n- two\n');

      expect(el.querySelector('h1')?.textContent).toBe('Title');
      expect(el.querySelector('code')?.textContent).toBe('code');
      expect(el.querySelectorAll('li').length).toBe(2);
      expect(el.querySelector('.blocked-image')).toBeNull();
    });
  });

  it('renders nothing for an empty README', () => {
    const el = render('');

    expect(el.querySelector('article')?.textContent?.trim()).toBe('');
  });
});
