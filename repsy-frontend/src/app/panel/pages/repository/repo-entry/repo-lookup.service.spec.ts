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

import { firstValueFrom, of, throwError } from 'rxjs';

import { ProtocolRepoControllerService } from '../../../../../generated/api';
import { RepoLookupService } from './repo-lookup.service';

describe('RepoLookupService', () => {
  let getRepoFormat: jasmine.Spy;
  let service: RepoLookupService;

  beforeEach(() => {
    getRepoFormat = jasmine
      .createSpy('getRepoFormat')
      .and.callFake((repoName: string) => of({ data: repoName.startsWith('npm') ? 'npm' : 'maven' }));
    service = new RepoLookupService({ getRepoFormat } as unknown as ProtocolRepoControllerService);
  });

  it('has no current repo until one is looked up', () => {
    expect(service.currentRepo).toBeNull();
  });

  describe('getRepoType', () => {
    it('fetches the type and publishes it as the current repo', async () => {
      expect(await firstValueFrom(service.getRepoType('acme-maven'))).toBe('maven');

      expect(getRepoFormat).toHaveBeenCalledOnceWith('acme-maven');
      expect(service.currentRepo).toEqual({ repoName: 'acme-maven', repoType: 'maven' });
    });

    it('serves a repeated lookup from the cache', async () => {
      await firstValueFrom(service.getRepoType('acme-maven'));
      expect(await firstValueFrom(service.getRepoType('acme-maven'))).toBe('maven');

      expect(getRepoFormat).toHaveBeenCalledTimes(1);
    });

    it('still switches the current repo when the type comes from the cache', async () => {
      await firstValueFrom(service.getRepoType('acme-maven'));
      await firstValueFrom(service.getRepoType('npm-one'));
      expect(service.currentRepo).toEqual({ repoName: 'npm-one', repoType: 'npm' });

      await firstValueFrom(service.getRepoType('acme-maven'));

      expect(getRepoFormat).toHaveBeenCalledTimes(2);
      expect(service.currentRepo).toEqual({ repoName: 'acme-maven', repoType: 'maven' });
    });

    it('emits every switch on currentRepo$', async () => {
      const seen: (string | undefined)[] = [];
      service.currentRepo$.subscribe((repo) => seen.push(repo?.repoName));

      await firstValueFrom(service.getRepoType('acme-maven'));
      await firstValueFrom(service.getRepoType('npm-one'));

      expect(seen).toEqual([undefined, 'acme-maven', 'npm-one']);
    });

    it('does not cache or publish a failed lookup, so the next call retries', async () => {
      getRepoFormat.and.returnValues(
        throwError(() => new Error('not found')),
        of({ data: 'maven' }),
      );

      await expectAsync(firstValueFrom(service.getRepoType('acme-maven'))).toBeRejectedWithError('not found');
      expect(service.currentRepo).toBeNull();

      expect(await firstValueFrom(service.getRepoType('acme-maven'))).toBe('maven');
      expect(getRepoFormat).toHaveBeenCalledTimes(2);
    });
  });

  describe('checkRepoType', () => {
    it('fetches and caches the type without changing the current repo', async () => {
      expect(await firstValueFrom(service.checkRepoType('acme-maven'))).toBe('maven');
      expect(await firstValueFrom(service.checkRepoType('acme-maven'))).toBe('maven');

      expect(getRepoFormat).toHaveBeenCalledTimes(1);
      expect(service.currentRepo).toBeNull();
    });

    it('shares its cache with getRepoType', async () => {
      await firstValueFrom(service.checkRepoType('acme-maven'));
      await firstValueFrom(service.getRepoType('acme-maven'));

      expect(getRepoFormat).toHaveBeenCalledTimes(1);
      expect(service.currentRepo).toEqual({ repoName: 'acme-maven', repoType: 'maven' });
    });
  });
});
