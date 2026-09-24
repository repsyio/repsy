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

export interface Toast {
  id: number;
  message: string;
  type: 'success' | 'error';
}

/** How long a toast stays when the caller gives no duration (RPS-1266: an error is read, not glanced at). */
export const SUCCESS_TOAST_DURATION = 3000;
export const ERROR_TOAST_DURATION = 7000;

interface Timer {
  handle: ReturnType<typeof setTimeout>;
  remaining: number;
  startedAt: number;
}

@Injectable({
  providedIn: 'root',
})
export class ToastService {
  toasts: Toast[] = [];
  private nextId = 0;
  private readonly timers = new Map<number, Timer>();

  /** `duration` defaults to 3 s for a success toast and to 7 s for an error one. */
  show(message: string, type: 'success' | 'error' = 'success', duration?: number) {
    if (type === 'error') {
      console.error(message);
    }

    const toast: Toast = { id: this.nextId++, message, type };
    this.toasts.push(toast);

    // RPS-1089: keep at most three toasts and evict the oldest, so the newest one (typically the
    // latest error of a burst) stays visible.
    if (this.toasts.length > 3) {
      this.discardTimer(this.toasts.shift().id);
    }

    this.startTimer(toast.id, duration ?? (type === 'error' ? ERROR_TOAST_DURATION : SUCCESS_TOAST_DURATION));
  }

  remove(id: number) {
    this.discardTimer(id);
    const index = this.toasts.findIndex((toast) => toast.id === id);
    if (index !== -1) {
      this.toasts.splice(index, 1);
    }
  }

  /** Stops the countdown of a toast the user is hovering or has focused, keeping what is left of it. */
  pause(id: number) {
    const timer = this.timers.get(id);
    if (!timer || timer.handle === undefined) {
      return;
    }
    clearTimeout(timer.handle);
    timer.handle = undefined;
    timer.remaining = Math.max(0, timer.remaining - (Date.now() - timer.startedAt));
  }

  /** Restarts the countdown of a paused toast with the time it had left. */
  resume(id: number) {
    const timer = this.timers.get(id);
    if (!timer || timer.handle !== undefined) {
      return;
    }
    this.startTimer(id, timer.remaining);
  }

  private startTimer(id: number, duration: number) {
    this.timers.set(id, {
      handle: setTimeout(() => this.remove(id), duration),
      remaining: duration,
      startedAt: Date.now(),
    });
  }

  private discardTimer(id: number) {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer.handle);
      this.timers.delete(id);
    }
  }
}
