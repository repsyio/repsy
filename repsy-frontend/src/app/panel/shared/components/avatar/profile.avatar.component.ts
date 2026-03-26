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

import { NgStyle } from '@angular/common';
import { Component, computed, Input, signal } from '@angular/core';

import { gravatarUrl } from '../../util/gravatar.util';

@Component({
  selector: 'app-profile-avatar',
  standalone: true,
  templateUrl: './profile-avatar.component.html',
  imports: [NgStyle],
})
export class ProfileAvatarComponent {
  private emailSig = signal<string | null>(null);
  private imageErrorSig = signal(false);

  @Input()
  set email(value: string | null) {
    this.emailSig.set(value);
    this.imageErrorSig.set(false);
  }

  @Input() size = 110;

  @Input() fallbackChar = '?';

  avatarUrl = computed(() => gravatarUrl(this.emailSig(), this.size));

  avatarStyle = computed(() => ({
    width: `${this.size}px`,
    height: `${this.size}px`,
  }));

  fontSize = computed(() => Math.round(this.size * 0.5));

  onImageError(): void {
    this.imageErrorSig.set(true);
  }

  showFallback = computed(() => this.imageErrorSig());
}
