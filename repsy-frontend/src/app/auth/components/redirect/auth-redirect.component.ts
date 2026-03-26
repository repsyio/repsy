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

import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  Inject,
  OnInit,
  PLATFORM_ID,
  ViewChild,
  ViewContainerRef,
} from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';

import { SplashService } from '../../../shared/components/splash-screen/splasht.service';
import { AuthService } from '../../pages/service/auth.service';

@Component({
  selector: 'app-auth-redirect',
  templateUrl: './auth-redirect.component.html',
  imports: [ReactiveFormsModule, RouterModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
})
export class AuthRedirectComponent implements OnInit {
  public isAuthenticated = false;

  @ViewChild('container', { read: ViewContainerRef })
  private readonly container!: ViewContainerRef;

  constructor(
    private readonly authService: AuthService,
    private readonly splashService: SplashService,
    @Inject(PLATFORM_ID) private platformId: object,
  ) {
    this.isAuthenticated = this.authService.isAuthenticated();
  }

  public ngOnInit(): void {
    this.splashService.setLoading = true;
    this.lazyLoadComponent();
  }

  private lazyLoadComponent(): void {
    if (this.isAuthenticated) {
      import('../../../../../src/app/panel/pages/dashboard/dashboard.component').then((m) => {
        this.container.createComponent(m.DashboardComponent);
        if (isPlatformBrowser(this.platformId)) {
          document.title = 'repsy | Dashboard';
          this.splashService.setLoading = false;
        }
      });
    } else {
      import('../../../../../src/app/auth/pages/login/login.component').then((m) => {
        this.container.createComponent(m.LoginComponent);
        if (isPlatformBrowser(this.platformId)) {
          document.title = 'repsy | Login';
          this.splashService.setLoading = false;
        }
      });
    }
  }
}
