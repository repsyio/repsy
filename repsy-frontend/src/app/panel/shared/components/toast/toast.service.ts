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

import { Injectable } from '@angular/core';

interface Toast {
  id: number;
  message: string;
  type: 'success' | 'error';
}

@Injectable({
  providedIn: 'root',
})
export class ToastService {
  toasts: Toast[] = [];
  private nextId = 0;

  show(message: string, type: 'success' | 'error' = 'success', duration = 3000) {
    if (type === 'error') {
      console.error(message);
    }

    const toast: Toast = { id: this.nextId++, message, type };
    this.toasts.push(toast);

    if (this.toasts.length > 3) {
      this.toasts.pop();
    }

    setTimeout(() => this.remove(toast.id), duration);
  }

  remove(id: number) {
    const index = this.toasts.findIndex((toast) => toast.id === id);
    if (index !== -1) {
      this.toasts.splice(index, 1);
    }
  }
}
