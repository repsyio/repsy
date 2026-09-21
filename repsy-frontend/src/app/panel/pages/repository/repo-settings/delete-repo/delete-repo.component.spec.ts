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

import { fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';

import { ProtocolRepoControllerService } from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { DeleteRepoComponent } from './delete-repo.component';

const REPO = 'acme-repo';

describe('DeleteRepoComponent', () => {
  let component: DeleteRepoComponent;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', ['deleteRepo']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    dangerModalService = new DangerModalService();
    repoApi.deleteRepo.and.returnValue(of({}) as never);
    router.navigate.and.resolveTo(true);

    component = new DeleteRepoComponent(repoApi, toastService, dangerModalService, router);
    component.activeRepository = permission(REPO, { canManage: true });
  });

  it('starts with a non-public repository in its form', () => {
    expect(component.visibilityForm.get('publicRepository').value).toBeFalse();
  });

  it('asks for confirmation before deleting anything', () => {
    component.deleteRepo();

    expect(dangerModalService.modal).toEqual({ title: 'Delete Repository', action: 'Delete', message: null });
    expect(repoApi.deleteRepo).not.toHaveBeenCalled();
  });

  it('deletes the active repository once the modal confirms, then returns to the list with a toast', fakeAsync(() => {
    component.deleteRepo();

    dangerModalService.call();

    expect(repoApi.deleteRepo).toHaveBeenCalledOnceWith(REPO);
    expect(router.navigate).toHaveBeenCalledOnceWith(['/repositories']);
    expect(toastService.show).not.toHaveBeenCalled();

    flushMicrotasks();

    expect(toastService.show).toHaveBeenCalledOnceWith('Repository deleted successfully', 'success');
    expect(component.loading).toBeFalse();
  }));

  it('shows a loading state while the request runs', () => {
    const response = new Subject<unknown>();
    repoApi.deleteRepo.and.returnValue(response as never);
    component.deleteRepo();

    dangerModalService.call();

    expect(component.loading).toBeTrue();

    response.error(new Error('boom'));

    expect(component.loading).toBeFalse();
  });

  it('stays on the page without a toast when deleting fails, leaving the error to the interceptor', () => {
    repoApi.deleteRepo.and.returnValue(throwError(() => new Error('boom')));
    component.deleteRepo();

    dangerModalService.call();

    expect(router.navigate).not.toHaveBeenCalled();
    expect(toastService.show).not.toHaveBeenCalled();
    expect(component.loading).toBeFalse();
  });
});
