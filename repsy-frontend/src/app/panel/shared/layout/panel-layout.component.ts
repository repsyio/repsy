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

// panel-layout.component.ts
import { CommonModule } from '@angular/common';
import { Component, OnInit, ViewEncapsulation } from '@angular/core';
import { RouterModule, RouterOutlet } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { FooterComponent } from '../../../shared/components/footer/footer.component';
import { PanelHeaderComponent } from '../../../shared/components/panel-header/panel-header.component';
import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { SidebarComponent } from '../components/sidebar/sidebar.component';

@Component({
  selector: 'app-panel-layout',
  imports: [CommonModule, SidebarComponent, FooterComponent, PanelHeaderComponent, RouterModule, RouterOutlet],
  encapsulation: ViewEncapsulation.None,
  templateUrl: './panel-layout.component.html',
  standalone: true,
})
export class PanelLayoutComponent implements OnInit {
  public loading = true;
  public isMobileMenuOpen = false;
  public isAuthenticated = false;

  constructor(
    private readonly splashService: SplashService,
    private readonly authService: AuthService,
  ) {}

  public ngOnInit(): void {
    this.isAuthenticated = this.authService.isAuthenticated();

    this.splashService.setLoading = true;
    setTimeout(() => {
      this.loading = false;
      this.splashService.setLoading = false;
    }, 500);
  }

  public closeMobileMenu(): void {
    this.isMobileMenuOpen = false;
  }
}
