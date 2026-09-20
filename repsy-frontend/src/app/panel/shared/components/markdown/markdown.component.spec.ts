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

  it('renders nothing for an empty README', () => {
    const el = render('');

    expect(el.querySelector('article')?.textContent?.trim()).toBe('');
  });
});
