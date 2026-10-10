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

import { AccessTokenCreated } from '../../../../../../generated/api';
import { DialogDirective } from '../../../directives/dialog.directive';
import { AccessTokenFormComponent } from '../../access-token-form/access-token-form.component';

@Component({
  selector: 'app-access-token-create-modal',
  imports: [DialogDirective, AccessTokenFormComponent],
  standalone: true,
  templateUrl: './access-token-create-modal.component.html',
})
export class AccessTokenCreateModalComponent {
  @Output() openChange = new EventEmitter<boolean>();
  @Output() created = new EventEmitter<AccessTokenCreated>();
  @Input() open: boolean;

  closeModal(): void {
    this.openChange.emit(false);
  }

  onCreated(token: AccessTokenCreated): void {
    this.closeModal();
    this.created.emit(token);
  }
}
