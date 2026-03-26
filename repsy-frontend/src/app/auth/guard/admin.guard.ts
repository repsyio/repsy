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

import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { ProfileService } from '../../panel/pages/profile/service/profile.service';

export const adminGuard: CanActivateFn = async () => {
  const profileService = inject(ProfileService);
  const router = inject(Router);

  const profile = await profileService.get();
  if (profile.role === 'ADMIN') {
    return true;
  }

  router.navigate(['/']);
  return false;
};
