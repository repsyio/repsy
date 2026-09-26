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

import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterOutlet } from '@angular/router';

import { AuthService } from './auth/pages/service/auth.service';
import { loginUrlReturningTo } from './auth/util/return-url';
import { DangerModalComponent } from './panel/shared/components/modals/danger-modal/danger-modal.component';
import { ToastComponent } from './panel/shared/components/toast/toast.component';
import { SplashComponent } from './shared/components/splash-screen/splash-screen.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, ToastComponent, DangerModalComponent, SplashComponent],
  templateUrl: './app.component.html',
})
export class AppComponent {
  private readonly router = inject(Router);

  constructor() {
    // Another tab logged out (or its refresh failed): the session is gone here too (RPS-1621).
    inject(AuthService)
      .sessionEndedElsewhere$.pipe(takeUntilDestroyed())
      .subscribe(() => this.router.navigateByUrl(loginUrlReturningTo(this.router.url)));
  }
}
