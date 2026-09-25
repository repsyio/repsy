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

import { Router } from '@angular/router';

import { ArtifactDetailRoute } from './security-detail-route.util';

/**
 * The URL the security rows produced before they became links (RPS-1315): `navigate` for a route with query
 * parameters and `navigateByUrl` on the raw path otherwise, both on the security tab. A row link must lead to
 * exactly this URL, whatever characters the artifact name has.
 */
export function legacyNavigationUrl(router: Router, route: ArtifactDetailRoute): string {
  const tree = route.queryParams
    ? router.createUrlTree([route.path], { queryParams: route.queryParams, fragment: 'security' })
    : router.parseUrl(`${route.path}#security`);
  return router.serializeUrl(tree);
}
