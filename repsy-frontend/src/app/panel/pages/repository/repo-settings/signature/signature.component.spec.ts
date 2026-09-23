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

import { environment } from '../../../../../../environments/environment';
import { AllowedKeyserverItem, KeyStoreControllerService, KeyStoreItem } from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { SignatureComponent } from './signature.component';

const REPO = 'maven-repo';
const UBUNTU: AllowedKeyserverItem = { id: 'ks-1', host: 'keyserver.ubuntu.com', displayName: 'Ubuntu Keyserver' };
const OPENPGP: AllowedKeyserverItem = { id: 'ks-2', host: 'keys.openpgp.org', displayName: 'OpenPGP Keyserver' };
const UBUNTU_LABEL = 'Ubuntu Keyserver (keyserver.ubuntu.com)';
const OPENPGP_LABEL = 'OpenPGP Keyserver (keys.openpgp.org)';

function keyStore(id: string): KeyStoreItem {
  return { id, allowedKeyserverId: 'ks-1', host: 'keyserver.ubuntu.com', displayName: 'Ubuntu Keyserver' };
}

describe('SignatureComponent', () => {
  let component: SignatureComponent;
  let keyStoreService: jasmine.SpyObj<KeyStoreControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    keyStoreService = jasmine.createSpyObj<KeyStoreControllerService>('KeyStoreControllerService', [
      'listAllowedKeyServers',
      'listMavenKeyStores',
      'createMavenKeyStore',
      'deleteMavenKeyStore',
    ]);
    keyStoreService.listAllowedKeyServers.and.returnValue(of({ data: [UBUNTU, OPENPGP] }) as never);
    keyStoreService.listMavenKeyStores.and.returnValue(of({ data: { content: [keyStore('k1')] } }) as never);
    keyStoreService.createMavenKeyStore.and.returnValue(of({}) as never);
    keyStoreService.deleteMavenKeyStore.and.returnValue(of({}) as never);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new SignatureComponent(toastService, dangerModalService, keyStoreService);
    component.activeRepository = permission(REPO, { canManage: true });
    component.repoType = 'MAVEN';
  });

  it('links to the documentation of the configured site and knows the well-known keyservers', () => {
    expect(component.docsBaseUrl).toBe(environment.docsBase);
    expect(component.wellKnownServers.map((s) => s.host)).toEqual(['keyserver.ubuntu.com', 'keys.openpgp.org']);
  });

  describe('ngOnInit', () => {
    it('loads the first page of key stores and the allowed keyservers', () => {
      component.ngOnInit();

      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith({ page: 0, size: 5 }, REPO);
      expect(component.keyStores.map((k) => k.id)).toEqual(['k1']);
      expect(component.pageNum).toBe(1);
    });

    it('offers the allowed keyservers as "name (host)" and selects the first', () => {
      component.ngOnInit();

      expect(component.serverLabels).toEqual([UBUNTU_LABEL, OPENPGP_LABEL]);
      expect(component.selectedServerLabel).toBe(UBUNTU_LABEL);
    });

    it('selects nothing when no keyserver is allowed', () => {
      keyStoreService.listAllowedKeyServers.and.returnValue(of({}) as never);

      component.ngOnInit();

      expect(component.serverLabels).toEqual([]);
      expect(component.selectedServerLabel).toBe('');
    });

    it('keeps its defaults when the lists cannot be loaded', () => {
      keyStoreService.listAllowedKeyServers.and.returnValue(throwError(() => new Error('boom')));
      keyStoreService.listMavenKeyStores.and.returnValue(throwError(() => new Error('boom')));

      component.ngOnInit();

      expect(component.keyStores).toEqual([]);
      expect(component.serverLabels).toEqual([]);
    });
  });

  describe('createKeyStore', () => {
    beforeEach(() => {
      component.ngOnInit();
      keyStoreService.listMavenKeyStores.calls.reset();
    });

    it('creates a key store for the selected keyserver, then reloads and toasts', () => {
      component.selectedServerLabel = OPENPGP_LABEL;

      component.createKeyStore();

      expect(keyStoreService.createMavenKeyStore).toHaveBeenCalledOnceWith(REPO, { allowedKeyserverId: 'ks-2' });
      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith({ page: 0, size: 5 }, REPO);
      expect(toastService.show).toHaveBeenCalledOnceWith('Key Store added', 'success');
      expect(component.isSubmitting).toBeFalse();
    });

    it('asks for a keyserver when the selection matches none', () => {
      component.selectedServerLabel = 'Nothing (nowhere)';

      component.createKeyStore();

      expect(toastService.show).toHaveBeenCalledOnceWith('Please select a keyserver', 'error');
      expect(keyStoreService.createMavenKeyStore).not.toHaveBeenCalled();
      expect(component.isSubmitting).toBeFalse();
    });

    it('ignores a second click while the first is still being submitted', () => {
      const answer = new Subject<unknown>();
      keyStoreService.createMavenKeyStore.and.returnValue(answer as never);

      component.createKeyStore();
      expect(component.isSubmitting).toBeTrue();
      component.createKeyStore();

      expect(keyStoreService.createMavenKeyStore).toHaveBeenCalledTimes(1);

      answer.next({});
      answer.complete();
      expect(component.isSubmitting).toBeFalse();
    });

    it('does not reload or toast, and can submit again, when creating fails', () => {
      keyStoreService.createMavenKeyStore.and.returnValue(throwError(() => new Error('boom')));

      component.createKeyStore();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(keyStoreService.listMavenKeyStores).not.toHaveBeenCalled();
      expect(component.isSubmitting).toBeFalse();
    });
  });

  describe('paging by scrolling', () => {
    beforeEach(() => component.ngOnInit());

    it('loadMoreKeyStores appends the next page and moves on', () => {
      keyStoreService.listMavenKeyStores.and.returnValue(of({ data: { content: [keyStore('k2')] } }) as never);

      component.loadMoreKeyStores();

      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledWith({ page: 1, size: 5 }, REPO);
      expect(component.keyStores.map((k) => k.id)).toEqual(['k1', 'k2']);
      expect(component.pageNum).toBe(2);
    });

    it('loadMoreKeyStores keeps the list when the page cannot be loaded', () => {
      keyStoreService.listMavenKeyStores.and.returnValue(throwError(() => new Error('boom')));

      component.loadMoreKeyStores();

      expect(component.keyStores.map((k) => k.id)).toEqual(['k1']);
      expect(component.pageNum).toBe(1);
    });

    function scrolled(scrollHeight: number, scrollTop: number, clientHeight: number): Event {
      return { target: { scrollHeight, scrollTop, clientHeight } } as unknown as Event;
    }

    it('loads more only once the list is scrolled to the bottom', () => {
      keyStoreService.listMavenKeyStores.calls.reset();

      component.onScroll(scrolled(500, 100, 200));
      expect(keyStoreService.listMavenKeyStores).not.toHaveBeenCalled();

      component.onScroll(scrolled(500, 300, 200));
      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledTimes(1);
    });
  });

  describe('deleteKeyStore', () => {
    beforeEach(() => {
      component.ngOnInit();
      keyStoreService.listMavenKeyStores.calls.reset();
    });

    it('asks for confirmation before deleting anything', () => {
      component.deleteKeyStore('k1');

      expect(dangerModalService.modal).toEqual({ title: 'Delete Key Store', action: 'Delete', message: null });
      expect(keyStoreService.deleteMavenKeyStore).not.toHaveBeenCalled();
    });

    it('deletes once confirmed, then reloads from the first page and toasts', () => {
      component.pageNum = 4;
      component.deleteKeyStore('k1');

      dangerModalService.call();

      expect(keyStoreService.deleteMavenKeyStore).toHaveBeenCalledOnceWith('k1', REPO);
      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith({ page: 0, size: 5 }, REPO);
      expect(component.pageNum).toBe(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Key Store deleted', 'success');
    });

    it('neither reloads nor toasts when the delete fails', () => {
      keyStoreService.deleteMavenKeyStore.and.returnValue(throwError(() => new Error('boom')));
      component.deleteKeyStore('k1');

      dangerModalService.call();

      expect(keyStoreService.listMavenKeyStores).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });
});
