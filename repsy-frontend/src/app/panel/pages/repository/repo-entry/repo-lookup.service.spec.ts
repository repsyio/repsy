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

import { HttpContext } from '@angular/common/http';
import { firstValueFrom, of, Subject, throwError } from 'rxjs';

import { ProtocolRepoControllerService, RepoType } from '../../../../../generated/api';
import { SILENT_ERROR } from '../../../../shared/interceptor/error-handler.interceptor';
import { RepoLookupService } from './repo-lookup.service';

describe('RepoLookupService', () => {
  let getRepoFormat: jasmine.Spy;
  let service: RepoLookupService;

  beforeEach(() => {
    getRepoFormat = jasmine
      .createSpy('getRepoFormat')
      .and.callFake((repoName: string) => of({ data: repoName.startsWith('npm') ? 'NPM' : 'MAVEN' }));
    service = new RepoLookupService({ getRepoFormat } as unknown as ProtocolRepoControllerService);
  });

  it('has no current repo until one is looked up', () => {
    expect(service.currentRepo).toBeNull();
  });

  it('marks the lookup silent, so a resolver or canMatch guard that 404s does not also toast (RPS-1670)', async () => {
    await firstValueFrom(service.getRepoType('acme-maven'));

    const context = getRepoFormat.calls.mostRecent().args[3]?.context as HttpContext;
    expect(context).toBeInstanceOf(HttpContext);
    expect(context.get(SILENT_ERROR)).toBeTrue();
  });

  it('shares one in-flight request between concurrent callers instead of firing one each (RPS-1670)', async () => {
    const response = new Subject<{ data: string }>();
    getRepoFormat.and.returnValue(response.asObservable());

    // Nine canMatch guards and the resolver, all asking for the same unknown name before any of them
    // can see it in the cache: this used to fire one HTTP call per caller (about ten in the real route
    // tree) and toast "not found" once per call.
    const callers = Array.from({ length: 10 }, () => firstValueFrom(service.checkRepoType('acme-maven')));

    expect(getRepoFormat).toHaveBeenCalledTimes(1);
    response.next({ data: 'MAVEN' });
    response.complete();
    expect(await Promise.all(callers)).toEqual(new Array(10).fill('maven'));
    expect(getRepoFormat).toHaveBeenCalledTimes(1);
  });

  it('lets a lookup started after the in-flight one settled start a request of its own', async () => {
    await firstValueFrom(service.checkRepoType('acme-maven'));
    // A different name is not cached, so it is a new request, not a replay of the first one's.
    await firstValueFrom(service.checkRepoType('npm-one'));

    expect(getRepoFormat).toHaveBeenCalledTimes(2);
  });

  it('shares an in-flight request that fails too, and lets the next lookup retry', async () => {
    const response = new Subject<{ data: string }>();
    getRepoFormat.and.returnValue(response.asObservable());

    const callers = Array.from({ length: 3 }, () =>
      firstValueFrom(service.checkRepoType('acme-maven')).catch((error: Error) => error.message),
    );
    response.error(new Error('not found'));
    expect(await Promise.all(callers)).toEqual(['not found', 'not found', 'not found']);
    expect(getRepoFormat).toHaveBeenCalledTimes(1);

    getRepoFormat.and.returnValue(of({ data: 'MAVEN' }));
    expect(await firstValueFrom(service.checkRepoType('acme-maven'))).toBe('maven');
    expect(getRepoFormat).toHaveBeenCalledTimes(2);
  });

  describe('getRepoType', () => {
    it('fetches the type and publishes it as the current repo', async () => {
      expect(await firstValueFrom(service.getRepoType('acme-maven'))).toBe('maven');

      expect(getRepoFormat).toHaveBeenCalledTimes(1);
      expect(getRepoFormat.calls.mostRecent().args[0]).toBe('acme-maven');
      expect(service.currentRepo).toEqual({ repoName: 'acme-maven', repoType: 'maven' });
    });

    it('maps the API type (upper case) to the lower-case route slug, for every type', async () => {
      for (const type of Object.values(RepoType)) {
        getRepoFormat.and.returnValue(of({ data: type }));

        expect(await firstValueFrom(service.checkRepoType(`repo-${type}`))).toBe(type.toLowerCase());
      }
    });

    it('fails a lookup that answers a type it does not know', async () => {
      getRepoFormat.and.returnValue(of({ data: 'BOGUS' }));

      await expectAsync(firstValueFrom(service.getRepoType('acme-maven'))).toBeRejectedWithError(
        'Unknown repository type "BOGUS" for acme-maven',
      );
      expect(service.currentRepo).toBeNull();
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
        of({ data: 'MAVEN' }),
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
