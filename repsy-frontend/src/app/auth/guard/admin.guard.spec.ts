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

import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';

import { ProfileInfo } from '../../../generated/api';
import { ProfileService } from '../../panel/pages/profile/service/profile.service';
import { adminGuard } from './admin.guard';

describe('adminGuard', () => {
  let profileService: jasmine.SpyObj<ProfileService>;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    profileService = jasmine.createSpyObj<ProfileService>('ProfileService', ['get']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);

    TestBed.configureTestingModule({
      providers: [
        { provide: ProfileService, useValue: profileService },
        { provide: Router, useValue: router },
      ],
    });
  });

  function activate(): Promise<boolean> {
    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
    return firstValueFrom(result as Observable<boolean>);
  }

  it('lets an admin through', async () => {
    profileService.get.and.returnValue(of({ role: 'ADMIN' } as ProfileInfo));

    expect(await activate()).toBeTrue();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('sends a non-admin home and blocks the route', async () => {
    profileService.get.and.returnValue(of({ role: 'USER' } as ProfileInfo));

    expect(await activate()).toBeFalse();
    expect(router.navigate).toHaveBeenCalledOnceWith(['/']);
  });

  it('propagates a failed profile lookup instead of letting the user through', async () => {
    profileService.get.and.returnValue(throwError(() => new Error('profile failed')));

    await expectAsync(activate()).toBeRejectedWithError('profile failed');
    expect(router.navigate).not.toHaveBeenCalled();
  });
});
