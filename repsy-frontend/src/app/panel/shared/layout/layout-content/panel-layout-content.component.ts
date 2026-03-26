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

import { Component, ViewEncapsulation } from '@angular/core';
import { RouterModule } from '@angular/router';

import { FooterComponent } from '../../../../shared/components/footer/footer.component';
import { PanelHeaderComponent } from '../../../../shared/components/panel-header/panel-header.component';
import { SidebarComponent } from '../../components/sidebar/sidebar.component';

@Component({
  selector: 'app-panel-layout-content',
  imports: [SidebarComponent, FooterComponent, PanelHeaderComponent, RouterModule],
  encapsulation: ViewEncapsulation.None,
  templateUrl: './panel-layout-content.component.html',
})
export class PanelLayoutContentComponent {
  public loading = true;
  public isMobileMenuOpen = false;

  closeMobileMenu(): void {
    this.isMobileMenuOpen = false;
  }
}
