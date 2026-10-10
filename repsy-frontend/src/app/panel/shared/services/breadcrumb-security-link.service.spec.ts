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

import { BreadcrumbSecurityLinkService } from './breadcrumb-security-link.service';

describe('BreadcrumbSecurityLinkService', () => {
  let service: BreadcrumbSecurityLinkService;
  let seen: (string | null)[];

  beforeEach(() => {
    service = new BreadcrumbSecurityLinkService();
    seen = [];
    service.repoType$.subscribe((repoType) => seen.push(repoType));
  });

  it('starts with no repo type', () => {
    expect(seen).toEqual([null]);
  });

  it('publishes the repo type it is shown for, and clears it again', () => {
    service.show('MAVEN');
    service.show('NPM');
    service.clear();

    expect(seen).toEqual([null, 'MAVEN', 'NPM', null]);
  });

  it('replays the latest value to a late subscriber', () => {
    service.show('DOCKER');

    let late: string | null | undefined;
    service.repoType$.subscribe((repoType) => (late = repoType));

    expect(late).toBe('DOCKER');
  });
});
