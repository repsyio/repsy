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

import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

/**
 * {@code app-repository-breadcrumb} is rendered once per protocol, as a sibling of the routed page
 * (see e.g. {@code maven.component.html}: {@code <app-repository-breadcrumb /><router-outlet />}) —
 * not an ancestor of it — so a routed version-detail page can't reach it via content projection to
 * put a "See Security Details" link on the same row. This lets a version-detail page announce its
 * repo type on init and withdraw it on destroy; the breadcrumb renders the link for whatever repo
 * type is currently announced (or nothing, on every other page).
 */
@Injectable({
  providedIn: 'root',
})
export class BreadcrumbSecurityLinkService {
  private readonly repoType = new BehaviorSubject<string | null>(null);

  public readonly repoType$: Observable<string | null> = this.repoType.asObservable();

  public show(repoType: string): void {
    this.repoType.next(repoType);
  }

  public clear(): void {
    this.repoType.next(null);
  }
}
