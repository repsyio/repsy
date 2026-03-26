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

import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { PanelLayoutContentComponent } from '../../shared/layout/layout-content/panel-layout-content.component';
import { NotFoundComponent } from './not-found.component';

@Component({
  selector: 'app-not-found-wrapper',
  standalone: true,
  imports: [CommonModule, NotFoundComponent, PanelLayoutContentComponent],
  template: `
    @if (isAuthenticated) {
      <app-panel-layout-content>
        <app-not-found />
      </app-panel-layout-content>
    } @else {
      <app-landing-layout-content>
        <app-not-found />
      </app-landing-layout-content>
    }
  `,
})
export class NotFoundWrapperComponent {
  private readonly authService = inject(AuthService);

  protected get isAuthenticated(): boolean {
    return this.authService.isAuthenticated();
  }
}
