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

import { UntypedFormControl } from '@angular/forms';

import { isReservedRepoName, RESERVED_REPO_NAMES, reservedRepoNameValidator } from './reserved-repo-names';

describe('reserved-repo-names', () => {
  describe('RESERVED_REPO_NAMES', () => {
    it('covers every fixed top-level route plus the API-port and /api/repos paths', () => {
      expect([...RESERVED_REPO_NAMES]).toEqual(
        jasmine.arrayWithExactContents([
          'login',
          'profile',
          'repositories',
          'users',
          'security',
          'not-found',
          'api',
          'assets',
          'favicon.ico',
          'counts',
          'security-summary',
        ]),
      );
    });
  });

  describe('isReservedRepoName', () => {
    it('is true for a reserved name, any case', () => {
      expect(isReservedRepoName('login')).toBeTrue();
      expect(isReservedRepoName('LOGIN')).toBeTrue();
      expect(isReservedRepoName('Repositories')).toBeTrue();
    });

    it('is false for a name that is not reserved', () => {
      expect(isReservedRepoName('my-repo')).toBeFalse();
      expect(isReservedRepoName('logins')).toBeFalse();
    });

    it('is false for null, undefined or empty', () => {
      expect(isReservedRepoName(null)).toBeFalse();
      expect(isReservedRepoName(undefined)).toBeFalse();
      expect(isReservedRepoName('')).toBeFalse();
    });
  });

  describe('reservedRepoNameValidator', () => {
    const validator = reservedRepoNameValidator();

    it('flags a reserved name with a reservedName error', () => {
      expect(validator(new UntypedFormControl('security'))).toEqual({ reservedName: true });
    });

    it('returns null for a name that is not reserved', () => {
      expect(validator(new UntypedFormControl('my-repo'))).toBeNull();
    });
  });
});
