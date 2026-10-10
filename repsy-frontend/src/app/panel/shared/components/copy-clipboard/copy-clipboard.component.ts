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

import { Component, Input } from '@angular/core';

import { copyToClipboard } from '../../util/clipboard.util';

@Component({
  selector: 'app-copy-clipboard',
  templateUrl: './copy-clipboard.component.html',
  standalone: true,
  imports: [],
})
export class CopyClipboardComponent {
  @Input() text: string;
  copied: boolean;

  copyToClipboard(text: string) {
    // The check mark shows only when the copy worked (RPS-1623): over plain HTTP there is no
    // navigator.clipboard, and the util falls back to a hidden textarea.
    void copyToClipboard(text).then((copied) => {
      if (!copied) {
        return;
      }
      this.copied = true;
      setTimeout(() => {
        this.copied = false;
      }, 1000);
    });
  }
}
