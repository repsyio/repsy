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

import { of, throwError } from 'rxjs';

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { RepoStorageComponent } from './repo-storage.component';

describe('RepoStorageComponent', () => {
  let component: RepoStorageComponent;
  let repoService: jasmine.SpyObj<ProtocolRepoControllerService>;

  beforeEach(() => {
    repoService = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getUsage']);
    repoService.getUsage.and.returnValue(of({ data: { totalSize: 42 } }) as never);
    component = new RepoStorageComponent(repoService);
    component.repoName = 'acme-repo';
    component.repoType = 'MAVEN';
  });

  it('shows no usage before it is initialised', () => {
    expect(component.usage).toBeUndefined();
    expect(repoService.getUsage).not.toHaveBeenCalled();
  });

  it('loads the usage of its repository on init', () => {
    component.ngOnInit();

    expect(repoService.getUsage).toHaveBeenCalledOnceWith('acme-repo');
    expect(component.usage as unknown).toEqual({ totalSize: 42 });
  });

  it('loads the usage again when asked to', () => {
    component.ngOnInit();
    repoService.getUsage.and.returnValue(of({ data: { totalSize: 50 } }) as never);

    component.fetchRepoUsage();

    expect(component.usage as unknown).toEqual({ totalSize: 50 });
  });

  it('keeps the last usage when it cannot be loaded', () => {
    component.ngOnInit();
    repoService.getUsage.and.returnValue(throwError(() => new Error('boom')));

    component.fetchRepoUsage();

    expect(component.usage as unknown).toEqual({ totalSize: 42 });
  });
});
