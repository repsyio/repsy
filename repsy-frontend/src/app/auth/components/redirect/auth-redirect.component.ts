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
  DestroyRef,
  Inject,
  OnInit,
  PLATFORM_ID,
  Type,
  ViewChild,
  ViewContainerRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
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
  private destroyed = false;

  @ViewChild('container', { read: ViewContainerRef })
  private readonly container!: ViewContainerRef;

  constructor(
    private readonly authService: AuthService,
    private readonly splashService: SplashService,
    private readonly destroyRef: DestroyRef,
    @Inject(PLATFORM_ID) private platformId: object,
  ) {
    this.isAuthenticated = this.authService.isAuthenticated();
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      // Leaving "/" while a chunk is still loading must not leave the splash (and its scroll lock) up.
      this.splashService.setLoading = false;
    });
  }

  public ngOnInit(): void {
    // "/" shows the login form in place, so a login does not change the route and the router has
    // nothing to re-evaluate: follow the session instead of deciding once (RPS-1278).
    this.authService.isAuthenticated$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((isAuthenticated) => {
      this.isAuthenticated = isAuthenticated;
      this.splashService.setLoading = true;
      void this.lazyLoadComponent(isAuthenticated);
    });
  }

  private async lazyLoadComponent(isAuthenticated: boolean): Promise<void> {
    const loaded: Type<unknown> = isAuthenticated
      ? await import('../../../../../src/app/panel/pages/dashboard/dashboard.component').then(
          (m) => m.DashboardComponent,
        )
      : await import('../../../../../src/app/auth/pages/login/login.component').then((m) => m.LoginComponent);

    // The route changed while the chunk loaded, or the session changed again: a newer call renders that state.
    if (this.destroyed || isAuthenticated !== this.isAuthenticated) {
      return;
    }

    this.container.clear();
    this.container.createComponent(loaded);
    if (isPlatformBrowser(this.platformId)) {
      document.title = isAuthenticated ? 'repsy | Dashboard' : 'repsy | Login';
      this.splashService.setLoading = false;
    }
  }
}
