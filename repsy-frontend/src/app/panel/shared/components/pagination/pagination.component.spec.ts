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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PaginationComponent } from './pagination.component';

describe('PaginationComponent', () => {
  let component: PaginationComponent;
  let emitted: number[];

  function at(pageNum: number, totalPages: number): PaginationComponent {
    component.pageNum = pageNum;
    component.totalPages = totalPages;
    return component;
  }

  beforeEach(() => {
    component = new PaginationComponent();
    emitted = [];
    component.pageNumChange.subscribe((page) => emitted.push(page));
  });

  describe('pages', () => {
    it('is empty when there are no pages', () => {
      expect(at(0, 0).pages).toEqual([]);
    });

    it('lists every page when there are three or fewer', () => {
      expect(at(0, 1).pages).toEqual([0]);
      expect(at(1, 2).pages).toEqual([0, 1]);
      expect(at(2, 3).pages).toEqual([0, 1, 2]);
    });

    it('lists every page of four without gaps, whichever is current', () => {
      expect(at(0, 4).pages).toEqual([0, 1, '...', 3]);
      expect(at(1, 4).pages).toEqual([0, 1, 2, 3]);
      expect(at(2, 4).pages).toEqual([0, 1, 2, 3]);
      expect(at(3, 4).pages).toEqual([0, '...', 2, 3]);
    });

    it('shows the first page, the neighbours of the current one and the last page, with a gap on either side', () => {
      expect(at(5, 10).pages).toEqual([0, '...', 4, 5, 6, '...', 9]);
    });

    it('has no leading gap while the current page is near the start', () => {
      expect(at(0, 10).pages).toEqual([0, 1, '...', 9]);
      expect(at(2, 10).pages).toEqual([0, 1, 2, 3, '...', 9]);
      expect(at(3, 10).pages).toEqual([0, '...', 2, 3, 4, '...', 9]);
    });

    it('has no trailing gap while the current page is near the end', () => {
      expect(at(9, 10).pages).toEqual([0, '...', 8, 9]);
      expect(at(7, 10).pages).toEqual([0, '...', 6, 7, 8, 9]);
      expect(at(6, 10).pages).toEqual([0, '...', 5, 6, 7, '...', 9]);
    });

    it('never lists a page twice, and always starts at the first and ends at the last', () => {
      for (let total = 4; total <= 12; total++) {
        for (let current = 0; current < total; current++) {
          const numbers = at(current, total).pages.filter((p) => component.isNumber(p));

          expect(new Set(numbers).size).withContext(`${current}/${total}`).toBe(numbers.length);
          expect(numbers[0]).toBe(0);
          expect(numbers[numbers.length - 1]).toBe(total - 1);
          expect(numbers).toContain(current);
        }
      }
    });
  });

  describe('previousPage', () => {
    it('moves back one page and announces it', () => {
      at(3, 10).previousPage();

      expect(component.pageNum).toBe(2);
      expect(emitted).toEqual([2]);
    });

    it('does nothing on the first page', () => {
      at(0, 10).previousPage();

      expect(component.pageNum).toBe(0);
      expect(emitted).toEqual([]);
    });
  });

  describe('nextPage', () => {
    it('moves forward one page and announces it', () => {
      at(3, 10).nextPage();

      expect(component.pageNum).toBe(4);
      expect(emitted).toEqual([4]);
    });

    it('does nothing on the last page', () => {
      at(9, 10).nextPage();

      expect(component.pageNum).toBe(9);
      expect(emitted).toEqual([]);
    });

    it('does nothing when there are no pages', () => {
      at(0, 0).nextPage();

      expect(emitted).toEqual([]);
    });
  });

  describe('goToPage', () => {
    it('jumps to the page and announces it', () => {
      at(0, 10).goToPage(7);

      expect(component.pageNum).toBe(7);
      expect(emitted).toEqual([7]);
    });
  });

  describe('isNumber', () => {
    it('tells a page number from the gap marker', () => {
      expect(component.isNumber(0)).toBeTrue();
      expect(component.isNumber('...')).toBeFalse();
    });
  });
});

describe('PaginationComponent markup', () => {
  let fixture: ComponentFixture<PaginationComponent>;

  const q = (selector: string): HTMLElement => fixture.nativeElement.querySelector(selector);

  function render(pageNum: number, totalPages: number) {
    fixture.componentInstance.pageNum = pageNum;
    fixture.componentInstance.totalPages = totalPages;
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [PaginationComponent] });
    fixture = TestBed.createComponent(PaginationComponent);
  });

  it('is a navigation landmark named Pagination that keeps its test id', () => {
    render(0, 5);

    const nav = q('[data-testid="pagination"]');
    expect(nav.tagName).toBe('NAV');
    expect(nav.getAttribute('aria-label')).toBe('Pagination');
  });

  it('marks only the current page with aria-current', () => {
    render(2, 5);

    const current = fixture.nativeElement.querySelectorAll('[aria-current]');
    expect(current.length).toBe(1);
    expect(current[0].getAttribute('data-testid')).toBe('pagination-page-3');
    expect(current[0].getAttribute('aria-current')).toBe('page');
    expect(q('[data-testid="pagination-page-2"]').hasAttribute('aria-current')).toBeFalse();
  });

  it('moves aria-current when the page changes', () => {
    render(0, 5);
    q('[data-testid="pagination-page-2"]').click();
    fixture.detectChanges();

    expect(q('[data-testid="pagination-page-2"]').getAttribute('aria-current')).toBe('page');
    expect(q('[data-testid="pagination-page-1"]').hasAttribute('aria-current')).toBeFalse();
  });

  it('names the page buttons and the previous/next buttons, whose arrow images are decorative', () => {
    render(1, 5);

    expect(q('[data-testid="pagination-page-1"]').getAttribute('aria-label')).toBe('Page 1');
    expect(q('[data-testid="pagination-prev"]').getAttribute('aria-label')).toBe('Previous page');
    expect(q('[data-testid="pagination-next"]').getAttribute('aria-label')).toBe('Next page');
    expect(q('[data-testid="pagination-prev"] img').getAttribute('alt')).toBe('');
    expect(q('[data-testid="pagination-next"] img').getAttribute('alt')).toBe('');
  });

  it('keeps the previous and next buttons working', () => {
    const emitted: number[] = [];
    fixture.componentInstance.pageNumChange.subscribe((page) => emitted.push(page));
    render(1, 5);

    q('[data-testid="pagination-next"]').click();
    q('[data-testid="pagination-prev"]').click();

    expect(emitted).toEqual([2, 1]);
  });
});
