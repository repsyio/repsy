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

import { RepoType } from '../../../../generated/api';
import { toApiRepoType, toRouteSlug } from './repo-api-type';

describe('toApiRepoType', () => {
  it('maps every generated type from its lower-case UI spelling and from its own', () => {
    for (const type of Object.values(RepoType)) {
      expect(toApiRepoType(type.toLowerCase())).toBe(type);
      expect(toApiRepoType(type)).toBe(type);
    }
  });

  it('gives undefined for the "all" option, an unknown type and nothing', () => {
    expect(toApiRepoType('all')).toBeUndefined();
    expect(toApiRepoType('bogus')).toBeUndefined();
    expect(toApiRepoType('')).toBeUndefined();
    expect(toApiRepoType(null)).toBeUndefined();
    expect(toApiRepoType(undefined)).toBeUndefined();
  });
});

describe('toRouteSlug', () => {
  it('maps every generated type, in either case, to its lower-case route slug', () => {
    for (const type of Object.values(RepoType)) {
      expect(toRouteSlug(type)).toBe(type.toLowerCase() as Lowercase<RepoType>);
      expect(toRouteSlug(type.toLowerCase())).toBe(type.toLowerCase() as Lowercase<RepoType>);
    }
  });

  it('round-trips with toApiRepoType', () => {
    for (const type of Object.values(RepoType)) {
      expect(toApiRepoType(toRouteSlug(type))).toBe(type);
    }
  });

  it('gives undefined for the "all" option, an unknown type and nothing', () => {
    expect(toRouteSlug('all')).toBeUndefined();
    expect(toRouteSlug('')).toBeUndefined();
    expect(toRouteSlug(undefined)).toBeUndefined();
  });
});
