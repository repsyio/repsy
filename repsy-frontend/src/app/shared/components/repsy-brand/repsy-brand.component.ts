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

import { NgOptimizedImage, NgTemplateOutlet } from '@angular/common';
import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-repsy-brand',
  standalone: true,
  imports: [RouterLink, NgOptimizedImage, NgTemplateOutlet],
  template: `
    @if (externalHref) {
      <a
        [href]="externalHref"
        class="repsy-brand-lockup inline-flex shrink-0 items-center justify-center"
        [class.gap-2]="showWordmark"
        [class.gap-2.5]="showWordmark && size === 'lg'"
        [attr.aria-label]="ariaLabel"
      >
        <ng-container *ngTemplateOutlet="brandMark" />
      </a>
    } @else {
      <a
        [routerLink]="link"
        class="repsy-brand-lockup inline-flex shrink-0 items-center justify-center"
        [class.gap-2]="showWordmark"
        [class.gap-2.5]="showWordmark && size === 'lg'"
        [attr.aria-label]="ariaLabel"
      >
        <ng-container *ngTemplateOutlet="brandMark" />
      </a>
    }

    <ng-template #brandMark>
      <img
        ngSrc="/assets/icons/repsy/repsy-logo.svg"
        alt=""
        aria-hidden="true"
        [width]="markSize"
        [height]="markSize"
        [class]="markClass"
        [priority]="priority"
      />
      @if (showWordmark) {
        <span class="originhub-mark leading-none" [class]="wordmarkClass">
          <span class="originhub-mark__origin">Rep</span><span class="originhub-mark__hub">sy</span>
        </span>
      }
    </ng-template>
  `,
})
export class RepsyBrandComponent {
  @Input() link = '/repositories';
  /** When set, navigates to an absolute URL instead of an in-app route. */
  @Input() externalHref?: string;
  @Input() showWordmark = false;
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() priority = false;

  get ariaLabel(): string {
    if (this.externalHref) {
      return 'OriginHub home';
    }
    return this.showWordmark ? 'Repsy home' : 'Repsy';
  }

  get markSize(): number {
    switch (this.size) {
      case 'sm':
        return 24;
      case 'lg':
        return 40;
      default:
        return 34;
    }
  }

  get markClass(): string {
    switch (this.size) {
      case 'sm':
        return 'h-6 w-6 shrink-0 object-contain';
      case 'lg':
        return 'h-10 w-10 shrink-0 object-contain';
      default:
        return 'h-[34px] w-[34px] shrink-0 object-contain';
    }
  }

  get wordmarkClass(): string {
    switch (this.size) {
      case 'sm':
        return 'text-lg';
      case 'lg':
        return 'text-2xl sm:text-[1.65rem]';
      default:
        return 'text-xl sm:text-2xl';
    }
  }
}
