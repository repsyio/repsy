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

import { NgOptimizedImage } from '@angular/common';
import { Component, inject } from '@angular/core';

import { ThemeService } from '../../services/theme.service';
import { RepsyBrandComponent } from '../repsy-brand/repsy-brand.component';

@Component({
  selector: 'app-panel-header',
  templateUrl: './panel-header.component.html',
  styleUrls: ['./panel-header.component.css'],
  imports: [NgOptimizedImage, RepsyBrandComponent],
  standalone: true,
})
export class PanelHeaderComponent {
  protected readonly originHubHomeUrl = 'https://repo-originhub.nuricanozturk.com';
  protected readonly themeService = inject(ThemeService);

  toggleTheme(event: Event): void {
    event.stopPropagation();
    this.themeService.toggle();
  }
}
