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
import { of } from 'rxjs';

import { PagedModelVulnerabilityScanInfo } from '../../../../generated/api';
import { ToastService } from '../../shared/components/toast/toast.service';
import { SecurityScanSupportService } from '../../shared/service/security-scan-support.service';
import { SecurityComponent } from './security.component';
import { SecurityService } from './service/security.service';

describe('SecurityComponent', () => {
  let securityService: jasmine.SpyObj<SecurityService>;
  let supportService: jasmine.SpyObj<SecurityScanSupportService>;
  let component: SecurityComponent;

  beforeEach(() => {
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['listScans', 'getScansSummary']);
    supportService = jasmine.createSpyObj<SecurityScanSupportService>('SecurityScanSupportService', [
      'getSupportedRepoTypes',
    ]);
    securityService.listScans.and.returnValue(
      of({ content: [], page: { totalPages: 0 } } as PagedModelVulnerabilityScanInfo),
    );
    securityService.getScansSummary.and.returnValue(of({ totalCount: 0 }));
    supportService.getSupportedRepoTypes.and.returnValue(of(new Set(['PYPI', 'MAVEN', 'NPM', 'DOCKER'])));

    component = new SecurityComponent(
      securityService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl']),
      supportService,
    );
  });

  describe('repository type options', () => {
    it('lists ALL first and the supported repository types in alphabetical order', () => {
      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL', 'DOCKER', 'MAVEN', 'NPM', 'PYPI']);
    });

    it('keeps ALL first even when a supported type sorts before it', () => {
      supportService.getSupportedRepoTypes.and.returnValue(of(new Set(['CARGO', 'ACME'])));

      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL', 'ACME', 'CARGO']);
    });

    it('offers only ALL when no type is supported', () => {
      supportService.getSupportedRepoTypes.and.returnValue(of(new Set<string>()));

      component.ngOnInit();

      expect(component.repoTypeOptions).toEqual(['ALL']);
    });
  });
});
