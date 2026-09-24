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

import { ActivatedRoute, Router } from '@angular/router';
import { Observable } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

import { ToastService } from '../components/toast/toast.service';
import { Sort } from '../dto/sort';

/**
 * Where the panel goes after a version is deleted from its detail page (RPS-1288), one rule for every
 * protocol: the package's versions page, or, when that was the package's last version (the package is
 * gone with it, so its versions page would answer 404), the package list of the repository.
 */

/** The sort of the probe below: any valid one will do, the probe only counts. */
export const VERSION_PROBE_SORT: Sort = { name: 'Newest', column: 'createdAt', type: 'DESC' };

/** A page of two versions is enough to tell "the last one" from "one of several". */
export const VERSION_PROBE_SIZE = 2;

/** The first versions of a package, as much of a page as the rule reads. */
export interface VersionProbe {
  content: readonly unknown[];
}

/** The package has at most one version left to delete: deleting it deletes the package. */
export function isLastVersion(probe: VersionProbe): boolean {
  return probe.content.length <= 1;
}

/**
 * Reads the package's first versions, deletes the version, and emits whether it was the last one. The
 * delete is started (`deleteVersion` is called) only after the probe answered, and not when it failed.
 */
export function deleteVersionAndCheckLast$(
  probe$: Observable<VersionProbe>,
  deleteVersion: () => Observable<unknown>,
): Observable<boolean> {
  return probe$.pipe(switchMap((probe) => deleteVersion().pipe(map(() => isLastVersion(probe)))));
}

/**
 * Navigates to where the rule above says and then toasts. `route` is the version detail route: its
 * parent page is the versions page.
 */
export function landAfterVersionDelete(
  router: Router,
  route: ActivatedRoute,
  toastService: ToastService,
  repoName: string,
  wasLastVersion: boolean,
): Promise<void> {
  const navigation = wasLastVersion ? router.navigate(['/', repoName]) : router.navigate(['..'], { relativeTo: route });
  return navigation.then(() => {
    toastService.show('Version deleted successfully', 'success');
  });
}
