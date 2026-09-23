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

import { of, Subject, throwError } from 'rxjs';

import { DockerImageControllerService } from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { DeleteOrphanLayersComponent } from './delete-orphan-layers.component';

describe('DeleteOrphanLayersComponent', () => {
  let component: DeleteOrphanLayersComponent;
  let dockerService: jasmine.SpyObj<DockerImageControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    dockerService = jasmine.createSpyObj<DockerImageControllerService>('DockerImageControllerService', [
      'deleteOrphanLayers',
    ]);
    dockerService.deleteOrphanLayers.and.returnValue(of({}) as never);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new DeleteOrphanLayersComponent(dockerService, dangerModalService, toastService);
    component.activeRepository = permission('docker-repo', { canManage: true });
  });

  it('asks for confirmation before deleting anything', () => {
    component.deleteOrphanLayers();

    expect(dangerModalService.modal).toEqual({ title: 'Delete Orphan Layers', action: 'Delete', message: null });
    expect(dockerService.deleteOrphanLayers).not.toHaveBeenCalled();
    expect(component.deleting).toBeFalse();
  });

  it('deletes the orphan layers of its repository once confirmed, then toasts', () => {
    component.deleteOrphanLayers();

    dangerModalService.call();

    expect(dockerService.deleteOrphanLayers).toHaveBeenCalledOnceWith('docker-repo');
    expect(toastService.show).toHaveBeenCalledOnceWith('Orphan layers deleted successfully', 'success');
    expect(component.deleting).toBeFalse();
  });

  it('shows the deletion as running until the request answers', () => {
    const answer = new Subject<unknown>();
    dockerService.deleteOrphanLayers.and.returnValue(answer as never);
    component.deleteOrphanLayers();

    dangerModalService.call();
    expect(component.deleting).toBeTrue();

    answer.next({});
    answer.complete();
    expect(component.deleting).toBeFalse();
  });

  it('does not toast, and stops showing it as running, when the deletion fails', () => {
    dockerService.deleteOrphanLayers.and.returnValue(throwError(() => new Error('boom')));
    component.deleteOrphanLayers();

    dangerModalService.call();

    expect(toastService.show).not.toHaveBeenCalled();
    expect(component.deleting).toBeFalse();
  });
});
