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

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface RadioOption<T = boolean | string | number> {
  label: string;
  value: T;
}

@Component({
  selector: 'app-radio-group',
  templateUrl: './radio-group.component.html',
  imports: [CommonModule],
  standalone: true,
})
export class RadioGroupComponent<T = boolean | string | number> {
  @Input() public options: RadioOption<T>[] = [];
  @Input() public selectedValue: T | null = null;
  @Input() public name: string = 'radio-group';
  @Input() public disabled = false;
  @Output() public valueChange = new EventEmitter<T>();

  selectOption(value: T) {
    if (this.disabled) {
      return;
    }
    this.selectedValue = value;
    this.valueChange.emit(value);
  }

  isSelected(value: T): boolean {
    return this.selectedValue === value;
  }
}
