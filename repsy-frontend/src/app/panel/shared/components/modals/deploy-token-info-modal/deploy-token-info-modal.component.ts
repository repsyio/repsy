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
import { FormsModule, ReactiveFormsModule } from '@angular/forms';

import { idFactory } from '../../../../../shared/util/unique-id';
import { TokenCreateInfo } from '../../../../pages/repository/repo-settings/deploy-token/dto/token-create-info';
import { DialogDirective } from '../../../directives/dialog.directive';
import { CopyClipboardComponent } from '../../copy-clipboard/copy-clipboard.component';

@Component({
  selector: 'app-deploy-token-info-modal',
  imports: [DialogDirective, FormsModule, ReactiveFormsModule, CopyClipboardComponent, NgOptimizedImage, CommonModule],
  standalone: true,
  templateUrl: './deploy-token-info-modal.component.html',
  styleUrl: './deploy-token-info-modal.component.css',
})
export class DeployTokenInfoModalComponent {
  /** Element ids of this instance: see `idFactory`. */
  public readonly id = idFactory('token-info');

  @Output() openChange = new EventEmitter<boolean>();
  @Input() public open: boolean;
  @Input() public tokenInfo: TokenCreateInfo;

  public showToken = false;

  closeModal(): void {
    this.openChange.emit(false);
  }

  toggleShowToken(): void {
    this.showToken = !this.showToken;
  }
}
