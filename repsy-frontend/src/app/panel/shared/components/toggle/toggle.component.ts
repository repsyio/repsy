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

import { Component, EventEmitter, forwardRef, Input, Output } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

import { uniqueId } from '../../../../shared/util/unique-id';

/**
 * A switch that works both ways: with `[checked]`/`(checkedChange)`, and as a form control
 * (`formControlName`/`formControl`), where it follows the control's value and its disabled state.
 */
@Component({
  selector: 'app-toggle-component',
  templateUrl: './toggle.component.html',
  imports: [],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => ToggleComponent), multi: true }],
})
export class ToggleComponent implements ControlValueAccessor {
  /** The id of the element that names what the switch controls; the state text is appended to it. */
  @Input() public labelledBy: string | null = null;
  @Input() public checked: boolean;
  @Input() public checkedLabel: string;
  @Input() public uncheckedLabel: string;
  @Input() public staticLabel: string;
  @Input() public disabled = false;
  @Output() public checkedChange = new EventEmitter<boolean>();
  @Output() public switch = new EventEmitter<boolean>();

  public readonly labelId = uniqueId('toggle-label');

  /** Set through `setDisabledState` when the bound form control is disabled. */
  private disabledByForm = false;
  private onChange: (value: boolean) => void = () => {};
  private onTouched: () => void = () => {};

  /** Locked either by the `disabled` input or by a disabled form control. */
  public get isDisabled(): boolean {
    return this.disabled || this.disabledByForm;
  }

  toggle() {
    if (this.isDisabled) {
      return;
    }
    this.checked = !this.checked;
    this.onChange(this.checked);
    this.onTouched();
    this.checkedChange.emit(this.checked);
    this.switch.emit(this.checked);
  }

  writeValue(value: boolean | null): void {
    this.checked = !!value;
  }

  registerOnChange(fn: (value: boolean) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabledByForm = isDisabled;
  }
}
