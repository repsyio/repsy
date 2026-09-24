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

import { Observable } from 'rxjs';
import { filter } from 'rxjs/operators';

import { RepoContext, RepoLookupService, RepoType } from './repo-lookup.service';

/**
 * The repository the panel is on, as long as it is of the given protocol.
 *
 * `currentRepo$` is a BehaviorSubject: it replays the repository that is already current on subscribe. A protocol
 * shell component therefore loads the repository permissions from this stream alone, once per page load and again
 * only when the repository changes. It must not also read `currentRepo` and load by hand: that requests the
 * permissions twice, and the second answer makes the pages below re-emit and reset their state (RPS-1305).
 */
export function currentRepoOfType(
  repoLookupService: Pick<RepoLookupService, 'currentRepo$'>,
  repoType: RepoType,
): Observable<RepoContext> {
  return repoLookupService.currentRepo$.pipe(
    filter((repo): repo is RepoContext => repo !== null && repo.repoType === repoType),
  );
}
