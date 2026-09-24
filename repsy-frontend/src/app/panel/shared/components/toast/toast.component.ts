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

import { Component } from '@angular/core';

import { Toast, ToastService } from './toast.service';

type Hold = 'hover' | 'focus';

@Component({
  selector: 'app-toast',
  templateUrl: './toast.component.html',
  imports: [],
  styleUrls: ['./toast.component.css'],
})
export class ToastComponent {
  /** What currently keeps an error toast on screen: the pointer over it and/or focus inside it. */
  private readonly holds = new Map<number, Set<Hold>>();

  constructor(public toastService: ToastService) {}

  removeToast(index: number) {
    this.holds.delete(index);
    this.toastService.remove(index);
  }

  /** An error toast stays while it is hovered or focused, so it can be read and its text selected. */
  hold(toast: Toast, reason: Hold) {
    if (toast.type !== 'error') {
      return;
    }
    const reasons = this.holds.get(toast.id) ?? new Set<Hold>();
    reasons.add(reason);
    this.holds.set(toast.id, reasons);
    this.toastService.pause(toast.id);
  }

  release(toast: Toast, reason: Hold) {
    const reasons = this.holds.get(toast.id);
    if (!reasons) {
      return;
    }
    reasons.delete(reason);
    if (reasons.size === 0) {
      this.holds.delete(toast.id);
      this.toastService.resume(toast.id);
    }
  }
}
