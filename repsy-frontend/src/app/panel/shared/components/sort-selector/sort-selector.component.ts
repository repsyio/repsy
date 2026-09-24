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

import { CommonModule } from '@angular/common';
import { Component, ElementRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';

import { OutSideClickDirective } from '../../../../shared/components/outside-click-directive';
import { Sort } from '../../dto/sort';

@Component({
  selector: 'app-sort-selector',
  templateUrl: './sort-selector.component.html',
  imports: [CommonModule, OutSideClickDirective],
})
export class SortSelectorComponent implements OnInit {
  @Input() public options: Sort[];
  @Input() public selectedOption: Sort;
  @Output() public selectedOptionChange = new EventEmitter<Sort>();
  @Output() public choose = new EventEmitter<Sort>();
  public isOpen = false;

  constructor(private readonly element: ElementRef<HTMLElement>) {}

  ngOnInit() {
    if (!this.selectedOption) {
      this.selectedOption = this.options[0];
    }
  }

  toggleDropdown(event: Event) {
    event.stopPropagation();
    this.isOpen = !this.isOpen;
  }

  /** Escape closes the menu and hands the focus back to the toggle. */
  @HostListener('document:keydown.escape')
  onEscape() {
    if (!this.isOpen) {
      return;
    }
    this.isOpen = false;
    this.element.nativeElement.querySelector<HTMLElement>('[data-testid="sort-selector-toggle"]')?.focus();
  }

  selectOption(option: Sort) {
    this.isOpen = false;
    this.selectedOption = option;
    this.selectedOptionChange.emit(option);
    this.choose.emit(option);
  }
}
