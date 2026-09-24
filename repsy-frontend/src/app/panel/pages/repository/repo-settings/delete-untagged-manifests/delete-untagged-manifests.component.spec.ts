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

import {
  DockerImageControllerService,
  RestResponseUntaggedManifestCleanupResult,
  UntaggedManifestCleanupResult,
} from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { DeleteUntaggedManifestsComponent } from './delete-untagged-manifests.component';

function answer(data: UntaggedManifestCleanupResult): RestResponseUntaggedManifestCleanupResult {
  return { msgId: 'untaggedManifestsDeleted', type: 'SUCCESS', data };
}

describe('DeleteUntaggedManifestsComponent', () => {
  let component: DeleteUntaggedManifestsComponent;
  let dockerService: jasmine.SpyObj<DockerImageControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    dockerService = jasmine.createSpyObj<DockerImageControllerService>('DockerImageControllerService', [
      'deleteUntaggedManifests',
    ]);
    dockerService.deleteUntaggedManifests.and.returnValue(
      of(
        answer({ deletedManifests: 3, freedManifestBytes: 1024, orphanLayersScheduled: 2, orphanLayerBytes: 3072 }),
      ) as never,
    );
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new DeleteUntaggedManifestsComponent(dockerService, dangerModalService, toastService);
    component.activeRepository = permission('docker-repo', { canManage: true });
  });

  it('explains what will be deleted and asks for confirmation before deleting anything', () => {
    component.deleteUntaggedManifests();

    expect(dangerModalService.modal.title).toBe('Delete Untagged Manifests');
    expect(dangerModalService.modal.action).toBe('Delete');
    expect(dangerModalService.modal.message).toContain('no tag points to');
    expect(dangerModalService.modal.message).toContain('stop being pullable by digest');
    expect(dockerService.deleteUntaggedManifests).not.toHaveBeenCalled();
    expect(component.deleting).toBeFalse();
  });

  it('deletes the untagged manifests of its whole repository once confirmed, then toasts the counts', () => {
    component.deleteUntaggedManifests();

    dangerModalService.call();

    expect(dockerService.deleteUntaggedManifests).toHaveBeenCalledOnceWith('docker-repo');
    expect(toastService.show).toHaveBeenCalledOnceWith(
      'Deleted 3 untagged manifests and 2 unused layers (4 K freed)',
      'success',
    );
    expect(component.deleting).toBeFalse();
  });

  it('uses the singular for one manifest and one layer', () => {
    dockerService.deleteUntaggedManifests.and.returnValue(
      of(
        answer({ deletedManifests: 1, freedManifestBytes: 512, orphanLayersScheduled: 1, orphanLayerBytes: 512 }),
      ) as never,
    );
    component.deleteUntaggedManifests();

    dangerModalService.call();

    expect(toastService.show).toHaveBeenCalledOnceWith(
      'Deleted 1 untagged manifest and 1 unused layer (1 K freed)',
      'success',
    );
  });

  it('says so when there was nothing to delete', () => {
    dockerService.deleteUntaggedManifests.and.returnValue(
      of(
        answer({ deletedManifests: 0, freedManifestBytes: 0, orphanLayersScheduled: 0, orphanLayerBytes: 0 }),
      ) as never,
    );
    component.deleteUntaggedManifests();

    dangerModalService.call();

    expect(toastService.show).toHaveBeenCalledOnceWith('No untagged manifests to delete', 'success');
  });

  it('still toasts when the answer carries no data', () => {
    dockerService.deleteUntaggedManifests.and.returnValue(of({}) as never);
    component.deleteUntaggedManifests();

    dangerModalService.call();

    expect(toastService.show).toHaveBeenCalledOnceWith('No untagged manifests to delete', 'success');
  });

  it('shows the deletion as running until the request answers', () => {
    const pending = new Subject<unknown>();
    dockerService.deleteUntaggedManifests.and.returnValue(pending as never);
    component.deleteUntaggedManifests();

    dangerModalService.call();
    expect(component.deleting).toBeTrue();

    pending.next({});
    pending.complete();
    expect(component.deleting).toBeFalse();
  });

  it('does not toast, and stops showing it as running, when the deletion fails', () => {
    dockerService.deleteUntaggedManifests.and.returnValue(throwError(() => new Error('boom')));
    component.deleteUntaggedManifests();

    dangerModalService.call();

    expect(toastService.show).not.toHaveBeenCalled();
    expect(component.deleting).toBeFalse();
  });
});
