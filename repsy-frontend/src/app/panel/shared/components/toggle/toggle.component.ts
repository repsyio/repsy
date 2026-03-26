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

@Component({
  selector: 'app-toggle-component',
  templateUrl: './toggle.component.html',
  imports: [],
})
export class ToggleComponent {
  @Input() public checked: boolean;
  @Input() public checkedLabel: string;
  @Input() public uncheckedLabel: string;
  @Input() public staticLabel: string;
  @Input() public disabled = false;
  @Output() public checkedChange = new EventEmitter<boolean>();
  @Output() public switch = new EventEmitter<boolean>();

  toggle() {
    if (this.disabled) {
      return;
    }
    this.checked = !this.checked;
    this.checkedChange.emit(this.checked);
    this.switch.emit(this.checked);
  }
}
