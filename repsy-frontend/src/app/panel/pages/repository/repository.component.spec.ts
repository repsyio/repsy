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
import { of, Subject } from 'rxjs';

import { ProtocolRepoControllerService, RepoSecuritySummary } from '../../../../generated/api';
import { DangerModalService } from '../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../shared/components/toast/toast.service';
import { RepoType } from '../../shared/dto/repo/repo-type';
import { ProfileService } from '../profile/service/profile.service';
import { SecurityService } from '../security/service/security.service';
import { RepositoryComponent } from './repository.component';

const SCANNING: Record<string, RepoSecuritySummary> = {
  'my-repo': { scanned: false, unscannedInProgressCount: 1 } as RepoSecuritySummary,
};

describe('RepositoryComponent security summary', () => {
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let securityService: jasmine.SpyObj<SecurityService>;
  let watched: Subject<Record<string, RepoSecuritySummary>>;

  function create(): RepositoryComponent {
    return new RepositoryComponent(
      repoApi,
      securityService,
      { get: () => of({ role: 'ADMIN' }) } as unknown as ProfileService,
      jasmine.createSpyObj<ToastService>('ToastService', ['show']),
      new DangerModalService(),
    );
  }

  beforeEach(() => {
    watched = new Subject();
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['getInfo']);
    repoApi.getInfo.and.returnValue(of({ data: [{ name: 'my-repo' }] }) as never);
    securityService = jasmine.createSpyObj<SecurityService>('SecurityService', ['watchSecuritySummary']);
    securityService.watchSecuritySummary.and.returnValue(watched);
  });

  it('watches the summary of the listed repositories and shows every emitted summary', () => {
    const component = create();
    component.filterRepos(RepoType.MAVEN);

    expect(securityService.watchSecuritySummary).toHaveBeenCalledWith(['my-repo']);
    watched.next(SCANNING);
    expect(component.securitySummary).toEqual(SCANNING);
    watched.next({ 'my-repo': { scanned: true } as RepoSecuritySummary });
    expect(component.securitySummary['my-repo'].scanned).toBeTrue();

    component.ngOnDestroy();
  });

  it('stops watching when the component is destroyed', () => {
    const component = create();
    expect(watched.observed).toBeTrue();

    component.ngOnDestroy();

    expect(watched.observed).toBeFalse();
  });

  it('replaces the watch when the list is loaded again', () => {
    const component = create();
    const second = new Subject<Record<string, RepoSecuritySummary>>();
    securityService.watchSecuritySummary.and.returnValue(second);

    component.filterRepos(RepoType.MAVEN);

    expect(watched.observed).toBeFalse();
    expect(second.observed).toBeTrue();

    component.ngOnDestroy();
  });

  it('does not watch when there are no repositories', () => {
    repoApi.getInfo.and.returnValue(of({ data: [] }) as never);
    securityService.watchSecuritySummary.calls.reset();

    const component = create();

    expect(securityService.watchSecuritySummary).not.toHaveBeenCalled();
    component.ngOnDestroy();
  });
});
