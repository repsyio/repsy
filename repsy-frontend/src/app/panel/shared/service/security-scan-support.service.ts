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
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';

import { SecurityScanControllerService } from '../../../../generated/api';

/**
 * Single source of truth for "which repo types are scannable" on the frontend, backed by {@link
 * SecurityScanControllerService#getSupportedRepoTypes}. The backend registry (and therefore this
 * list) only grows/shrinks when scanner beans change, so a single shared, cached request is enough
 * for the whole app session rather than every gated component issuing its own call.
 */
@Injectable({
  providedIn: 'root',
})
export class SecurityScanSupportService {
  private readonly supportedRepoTypes$: Observable<Set<string>>;

  constructor(private readonly securityScanControllerService: SecurityScanControllerService) {
    this.supportedRepoTypes$ = this.securityScanControllerService.getSupportedRepoTypes().pipe(
      map((response) => new Set((response.data ?? []).map((repoType) => repoType.toUpperCase()))),
      catchError(() => of(new Set<string>())),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }

  public isSupported(repoType: string): Observable<boolean> {
    return this.supportedRepoTypes$.pipe(map((supported) => supported.has(repoType.toUpperCase())));
  }

  public getSupportedRepoTypes(): Observable<Set<string>> {
    return this.supportedRepoTypes$;
  }
}
