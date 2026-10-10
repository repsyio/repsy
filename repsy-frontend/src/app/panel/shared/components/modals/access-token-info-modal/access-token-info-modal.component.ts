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

import { NgClass } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';

import { AccessTokenCreated } from '../../../../../../generated/api';
import { idFactory } from '../../../../../shared/utils/unique-id';
import { DialogDirective } from '../../../directives/dialog.directive';
import { CopyClipboardComponent } from '../../copy-clipboard/copy-clipboard.component';

@Component({
  selector: 'app-access-token-info-modal',
  imports: [DialogDirective, CopyClipboardComponent, NgClass],
  standalone: true,
  templateUrl: './access-token-info-modal.component.html',
})
export class AccessTokenInfoModalComponent {
  /** Element ids of this instance: see `idFactory`. */
  readonly id = idFactory('access-token-info');

  @Output() openChange = new EventEmitter<boolean>();
  @Input() open: boolean;
  @Input() tokenInfo: AccessTokenCreated;

  showToken = false;

  closeModal(): void {
    this.showToken = false;
    this.openChange.emit(false);
  }

  toggleShowToken(): void {
    this.showToken = !this.showToken;
  }
}
