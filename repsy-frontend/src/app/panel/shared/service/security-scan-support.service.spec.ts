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

import { firstValueFrom, Observable, of, throwError } from 'rxjs';

import { SecurityScanControllerService } from '../../../../generated/api';
import { SecurityScanSupportService } from './security-scan-support.service';

describe('SecurityScanSupportService', () => {
  let getSupportedRepoTypes: jasmine.Spy;

  function createService(response: Observable<unknown>): SecurityScanSupportService {
    getSupportedRepoTypes = jasmine.createSpy('getSupportedRepoTypes').and.returnValue(response);
    return new SecurityScanSupportService({ getSupportedRepoTypes } as unknown as SecurityScanControllerService);
  }

  it('reports a repo type the scanner supports, ignoring case', async () => {
    const service = createService(of({ data: ['maven', 'NPM'] }));

    expect(await firstValueFrom(service.isSupported('MAVEN'))).toBeTrue();
    expect(await firstValueFrom(service.isSupported('npm'))).toBeTrue();
  });

  it('reports a repo type the scanner does not support', async () => {
    const service = createService(of({ data: ['maven'] }));

    expect(await firstValueFrom(service.isSupported('docker'))).toBeFalse();
  });

  it('exposes the supported types upper-cased', async () => {
    const service = createService(of({ data: ['maven', 'npm'] }));

    expect([...(await firstValueFrom(service.getSupportedRepoTypes()))]).toEqual(['MAVEN', 'NPM']);
  });

  it('treats a response without data as nothing supported', async () => {
    const service = createService(of({}));

    expect(await firstValueFrom(service.isSupported('maven'))).toBeFalse();
    expect((await firstValueFrom(service.getSupportedRepoTypes())).size).toBe(0);
  });

  it('treats a failed request as nothing supported instead of failing the caller', async () => {
    const service = createService(throwError(() => new Error('scanner down')));

    expect(await firstValueFrom(service.isSupported('maven'))).toBeFalse();
    expect((await firstValueFrom(service.getSupportedRepoTypes())).size).toBe(0);
  });

  it('asks the backend once, however many callers check', async () => {
    const service = createService(of({ data: ['maven'] }));

    await firstValueFrom(service.isSupported('maven'));
    await firstValueFrom(service.isSupported('npm'));
    await firstValueFrom(service.getSupportedRepoTypes());

    expect(getSupportedRepoTypes).toHaveBeenCalledTimes(1);
  });
});
