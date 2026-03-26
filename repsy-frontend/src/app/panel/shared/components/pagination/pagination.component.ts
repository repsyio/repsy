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

import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-pagination',
  templateUrl: './pagination.component.html',
  imports: [CommonModule, NgOptimizedImage],
})
export class PaginationComponent {
  @Input() public pageNum: number;
  @Input() public totalPages: number;
  @Output() public pageNumChange = new EventEmitter<number>();

  previousPage() {
    if (this.pageNum > 0) {
      this.pageNum--;
      this.pageNumChange.emit(this.pageNum);
    }
  }

  nextPage() {
    if (this.pageNum + 1 < this.totalPages) {
      this.pageNum++;
      this.pageNumChange.emit(this.pageNum);
    }
  }

  goToPage(page: number) {
    this.pageNum = page;
    this.pageNumChange.emit(this.pageNum);
  }

  isNumber(value: unknown): value is number {
    return typeof value === 'number';
  }

  get pages(): (number | string)[] {
    const pages: (number | string)[] = [];

    if (this.totalPages <= 3) {
      for (let i = 0; i < this.totalPages; i++) {
        pages.push(i);
      }
    } else {
      pages.push(0);

      if (this.pageNum > 2) {
        pages.push('...');
      }

      const start = Math.max(1, this.pageNum - 1);
      const end = Math.min(this.totalPages - 2, this.pageNum + 1);

      for (let i = start; i <= end; i++) {
        pages.push(i);
      }

      if (this.pageNum < this.totalPages - 3) {
        pages.push('...');
      }

      pages.push(this.totalPages - 1);
    }

    return pages;
  }
}
