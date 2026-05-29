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

import { computed, Injectable, signal } from '@angular/core';

export type AppTheme = 'originhub' | 'originhub-light';

const STORAGE_KEY = 'repsy-theme';
const DEFAULT_THEME: AppTheme = 'originhub-light';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<AppTheme>(DEFAULT_THEME);
  readonly isDark = computed(() => this.theme() === 'originhub');

  constructor() {
    this.theme.set(this.readStoredTheme());
    this.apply(this.theme());
  }

  toggle(): void {
    this.setTheme(this.theme() === 'originhub-light' ? 'originhub' : 'originhub-light');
  }

  setTheme(theme: AppTheme): void {
    this.theme.set(theme);
    localStorage.setItem(STORAGE_KEY, theme);
    this.apply(theme);
  }

  private readStoredTheme(): AppTheme {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'originhub' || stored === 'originhub-light' ? stored : DEFAULT_THEME;
  }

  private apply(theme: AppTheme): void {
    document.documentElement.setAttribute('data-theme', theme);
  }
}
