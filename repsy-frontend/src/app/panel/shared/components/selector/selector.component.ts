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
import { Component, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';

import { OutSideClickDirective } from '../../../../shared/components/outside-click-directive';
import { uniqueId } from '../../../../shared/util/unique-id';

@Component({
  selector: 'app-selector',
  templateUrl: './selector.component.html',
  imports: [CommonModule, OutSideClickDirective],
})
export class SelectorComponent implements OnInit {
  /** The id of the element that labels the selector; the chosen value is appended to that name. */
  @Input() labelledBy: string | null = null;
  @Input() size: 'small' | 'big' = 'small';
  @Input() options: string[];
  @Input() selectedOption: string;
  /** Locks the selector, e.g. while the change it made is being saved. */
  @Input() disabled = false;
  @Output() selectedOptionChange = new EventEmitter<string>();
  @Output() choose = new EventEmitter<string>();
  isOpen = false;
  readonly valueId = uniqueId('selector-value');

  ngOnInit() {
    if (!this.selectedOption) {
      this.selectedOption = this.options[0];
    }
  }

  /**
   * The click is not stopped here: it has to reach the document, where an open row menu (`app-dropdown`) and
   * any other open selector close on a click outside of themselves (RPS-1565). This selector's own menu ignores
   * clicks inside it (`OutSideClickDirective`), so the toggle still opens and closes it.
   */
  toggleDropdown() {
    this.isOpen = !this.isOpen;
  }

  /** Escape closes an open menu first: it must not also close the dialog the selector sits in. */
  @HostListener('keydown.escape', ['$event'])
  closeOnEscape(event: Event) {
    if (this.isOpen) {
      event.preventDefault();
      this.isOpen = false;
    }
  }

  selectOption(option: string) {
    this.selectedOption = option;
    this.isOpen = false;
    this.selectedOptionChange.emit(option);
    this.choose.emit(option);
  }
}
