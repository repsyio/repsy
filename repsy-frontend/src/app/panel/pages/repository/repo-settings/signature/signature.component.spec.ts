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
import {
  AllowedKeyserverItem,
  KeyStoreControllerService,
  KeyStoreItem,
  ProtocolRepoControllerService,
} from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { SignatureComponent } from './signature.component';
import { PgpPublicKeyItem } from './dto/pgp-public-key-item';

const REPO = 'maven-repo';
const UBUNTU: AllowedKeyserverItem = { id: 'ks-1', host: 'keyserver.ubuntu.com', displayName: 'Ubuntu Keyserver' };
const OPENPGP: AllowedKeyserverItem = { id: 'ks-2', host: 'keys.openpgp.org', displayName: 'OpenPGP Keyserver' };
const UBUNTU_LABEL = 'Ubuntu Keyserver (keyserver.ubuntu.com)';
const OPENPGP_LABEL = 'OpenPGP Keyserver (keys.openpgp.org)';

function keyStore(id: string): KeyStoreItem {
  return { id, allowedKeyserverId: 'ks-1', host: 'keyserver.ubuntu.com', displayName: 'Ubuntu Keyserver' };
}

function publicKey(uuid: string): PgpPublicKeyItem {
  return {
    uuid,
    keyId: 'ABCD1234',
    fingerprint: '1234567890ABCDEF1234567890ABCDEF12345678',
    userId: 'Test User <test@example.com>',
    createdAt: '2026-10-01T00:00:00Z',
  };
}

describe('SignatureComponent', () => {
  let component: SignatureComponent;
  let keyStoreService: jasmine.SpyObj<KeyStoreControllerService>;
  let repoApi: jasmine.SpyObj<ProtocolRepoControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let dangerModalService: DangerModalService;

  beforeEach(() => {
    keyStoreService = jasmine.createSpyObj<KeyStoreControllerService>('KeyStoreControllerService', [
      'listMavenAllowedKeyServers',
      'listMavenKeyStores',
      'createMavenKeyStore',
      'deleteMavenKeyStore',
      'listMavenPgpPublicKeys',
      'createMavenPgpPublicKey',
      'deleteMavenPgpPublicKey',
    ]);
    keyStoreService.listMavenAllowedKeyServers.and.returnValue(of({ data: [UBUNTU, OPENPGP] }) as never);
    keyStoreService.listMavenKeyStores.and.returnValue(of({ data: { content: [keyStore('k1')] } }) as never);
    keyStoreService.createMavenKeyStore.and.returnValue(of({}) as never);
    keyStoreService.deleteMavenKeyStore.and.returnValue(of({}) as never);
    keyStoreService.listMavenPgpPublicKeys.and.returnValue(of({ data: { content: [publicKey('pk1')] } }) as never);
    keyStoreService.createMavenPgpPublicKey.and.returnValue(of({ data: publicKey('pk2') }) as never);
    keyStoreService.deleteMavenPgpPublicKey.and.returnValue(of({}) as never);
    repoApi = jasmine.createSpyObj<ProtocolRepoControllerService>('ProtocolRepoControllerService', [
      'updateRepoSettings',
    ]);
    repoApi.updateRepoSettings.and.returnValue(of({}) as never);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    dangerModalService = new DangerModalService();
    component = new SignatureComponent(toastService, dangerModalService, keyStoreService, repoApi);
    component.activeRepository = permission(REPO, { canManage: true });
    component.repoType = 'MAVEN';
    component.parentForm = releaseAwareParentForm();
  });

  it('links to the documentation of the configured site and knows the well-known keyservers', () => {
    expect(component.docsBaseUrl).toBe(environment.docsBase);
    expect(component.wellKnownServers.map((s) => s.host)).toEqual(['keyserver.ubuntu.com', 'keys.openpgp.org']);
  });

  describe('signature verification settings (RPS-1188, RPS-1204)', () => {
    it('starts from the values the settings page loaded', () => {
      component.parentForm = releaseAwareParentForm({
        pgpVerifyAllSignaturesEnabled: true,
        pgpKeyServerLookupEnabled: false,
      });

      component.ngOnInit();

      expect(component.verifyAllSignaturesEnabled).toBeTrue();
      expect(component.keyServerLookupEnabled).toBeFalse();
    });

    it('defaults to verifying the POM only and looking keys up', () => {
      component.ngOnInit();

      expect(component.verifyAllSignaturesEnabled).toBeFalse();
      expect(component.keyServerLookupEnabled).toBeTrue();
    });

    it('sends only its own field when every signature verification is switched on, then reloads', () => {
      const reloaded = jasmine.createSpy('reloaded');
      component.fetch.subscribe(reloaded);
      component.ngOnInit();
      component.verifyAllSignaturesEnabled = true;

      component.changeVerifyAllSignatures();

      expect(repoApi.updateRepoSettings).toHaveBeenCalledOnceWith(REPO, { pgpVerifyAllSignaturesEnabled: true });
      expect(component.parentForm.get('pgpVerifyAllSignaturesEnabled').value).toBeTrue();
      expect(toastService.show).toHaveBeenCalledOnceWith('Every signature is now verified', 'success');
      expect(reloaded).toHaveBeenCalledTimes(1);
    });

    it('sends only its own field when the key server lookup is switched off, then reloads', () => {
      const reloaded = jasmine.createSpy('reloaded');
      component.fetch.subscribe(reloaded);
      component.ngOnInit();
      component.keyServerLookupEnabled = false;

      component.changeKeyServerLookup();

      expect(repoApi.updateRepoSettings).toHaveBeenCalledOnceWith(REPO, { pgpKeyServerLookupEnabled: false });
      expect(component.parentForm.get('pgpKeyServerLookupEnabled').value).toBeFalse();
      expect(toastService.show).toHaveBeenCalledOnceWith('Key server lookup is now disabled', 'success');
      expect(reloaded).toHaveBeenCalledTimes(1);
    });

    it('puts a toggle back, and neither toasts nor reloads, when the update fails', () => {
      const reloaded = jasmine.createSpy('reloaded');
      component.fetch.subscribe(reloaded);
      repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
      component.ngOnInit();
      component.verifyAllSignaturesEnabled = true;
      component.keyServerLookupEnabled = false;

      component.changeVerifyAllSignatures();
      component.changeKeyServerLookup();

      expect(component.verifyAllSignaturesEnabled).toBeFalse();
      expect(component.keyServerLookupEnabled).toBeTrue();
      expect(component.parentForm.get('pgpVerifyAllSignaturesEnabled').value).toBeFalse();
      expect(component.parentForm.get('pgpKeyServerLookupEnabled').value).toBeTrue();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(reloaded).not.toHaveBeenCalled();
    });

    it('locks both toggles while a settings save is on its way and unlocks them afterwards (RPS-1618)', () => {
      const inFlight = new Subject<object>();
      repoApi.updateRepoSettings.and.returnValue(inFlight as never);
      component.ngOnInit();
      component.verifyAllSignaturesEnabled = true;

      component.changeVerifyAllSignatures();

      expect(component.saving).toBeTrue();

      inFlight.next({});
      inFlight.complete();

      expect(component.saving).toBeFalse();
    });
  });

  describe('ngOnInit', () => {
    it('loads the first page of key stores, public keys and the allowed keyservers', () => {
      component.ngOnInit();

      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith(REPO, 0, 5);
      expect(component.keyStores.map((k) => k.id)).toEqual(['k1']);
      expect(component.pageNum).toBe(1);
      expect(keyStoreService.listMavenPgpPublicKeys).toHaveBeenCalledOnceWith(REPO, 0, 5);
      expect(component.publicKeys.map((k) => k.uuid)).toEqual(['pk1']);
      expect(component.publicKeyPageNum).toBe(1);
    });

    it('offers the allowed keyservers as "name (host)" and selects the first', () => {
      component.ngOnInit();

      expect(component.serverLabels).toEqual([UBUNTU_LABEL, OPENPGP_LABEL]);
      expect(component.selectedServerLabel).toBe(UBUNTU_LABEL);
    });

    it('selects nothing when no keyserver is allowed', () => {
      keyStoreService.listMavenAllowedKeyServers.and.returnValue(of({}) as never);

      component.ngOnInit();

      expect(component.serverLabels).toEqual([]);
      expect(component.selectedServerLabel).toBe('');
    });

    it('keeps its defaults when the lists cannot be loaded', () => {
      keyStoreService.listMavenAllowedKeyServers.and.returnValue(throwError(() => new Error('boom')));
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
      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith(REPO, 0, 5);
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

      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledWith(REPO, 1, 5);
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
      expect(keyStoreService.listMavenKeyStores).toHaveBeenCalledOnceWith(REPO, 0, 5);
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

  describe('addPublicKey (RPS-1803)', () => {
    beforeEach(() => {
      component.ngOnInit();
      keyStoreService.listMavenPgpPublicKeys.calls.reset();
    });

    it('adds a public key from an armored key string, then reloads and toasts', () => {
      const armoredKey = '-----BEGIN PGP PUBLIC KEY BLOCK-----\n...key...\n-----END PGP PUBLIC KEY BLOCK-----';

      component.addPublicKey(armoredKey);

      expect(keyStoreService.createMavenPgpPublicKey).toHaveBeenCalledOnceWith(REPO, { armoredKey });
      expect(keyStoreService.listMavenPgpPublicKeys).toHaveBeenCalledOnceWith(REPO, 0, 5);
      expect(toastService.show).toHaveBeenCalledOnceWith('Public key added', 'success');
      expect(component.isSubmitting).toBeFalse();
    });

    it('trims whitespace from the armored key before submitting', () => {
      const armoredKey = '  -----BEGIN PGP PUBLIC KEY BLOCK-----\n...key...\n-----END PGP PUBLIC KEY BLOCK-----  \n';

      component.addPublicKey(armoredKey);

      expect(keyStoreService.createMavenPgpPublicKey).toHaveBeenCalledOnceWith(REPO, {
        armoredKey: '-----BEGIN PGP PUBLIC KEY BLOCK-----\n...key...\n-----END PGP PUBLIC KEY BLOCK-----',
      });
    });

    it('shows an error and does not submit when the key is empty or whitespace', () => {
      component.addPublicKey('  \n  ');

      expect(toastService.show).toHaveBeenCalledOnceWith('Please paste an armored PGP public key', 'error');
      expect(keyStoreService.createMavenPgpPublicKey).not.toHaveBeenCalled();
      expect(component.isSubmitting).toBeFalse();
    });

    it('ignores a second click while the first is still being submitted', () => {
      const answer = new Subject<unknown>();
      keyStoreService.createMavenPgpPublicKey.and.returnValue(answer as never);

      component.addPublicKey('key1');
      expect(component.isSubmitting).toBeTrue();
      component.addPublicKey('key2');

      expect(keyStoreService.createMavenPgpPublicKey).toHaveBeenCalledTimes(1);

      answer.next({});
      answer.complete();
      expect(component.isSubmitting).toBeFalse();
    });

    it('does not reload or toast, and can submit again, when adding fails', () => {
      keyStoreService.createMavenPgpPublicKey.and.returnValue(throwError(() => new Error('boom')));

      component.addPublicKey('-----BEGIN PGP PUBLIC KEY BLOCK-----\n...key...\n-----END PGP PUBLIC KEY BLOCK-----');

      expect(toastService.show).not.toHaveBeenCalledWith('Public key added', 'success');
      expect(keyStoreService.listMavenPgpPublicKeys).not.toHaveBeenCalled();
      expect(component.isSubmitting).toBeFalse();
    });
  });

  describe('paging public keys by scrolling', () => {
    beforeEach(() => component.ngOnInit());

    it('loadMorePublicKeys appends the next page and moves on', () => {
      keyStoreService.listMavenPgpPublicKeys.and.returnValue(of({ data: { content: [publicKey('pk2')] } }) as never);

      component.loadMorePublicKeys();

      expect(keyStoreService.listMavenPgpPublicKeys).toHaveBeenCalledWith(REPO, 1, 5);
      expect(component.publicKeys.map((k) => k.uuid)).toEqual(['pk1', 'pk2']);
      expect(component.publicKeyPageNum).toBe(2);
    });

    it('loadMorePublicKeys keeps the list when the page cannot be loaded', () => {
      keyStoreService.listMavenPgpPublicKeys.and.returnValue(throwError(() => new Error('boom')));

      component.loadMorePublicKeys();

      expect(component.publicKeys.map((k) => k.uuid)).toEqual(['pk1']);
      expect(component.publicKeyPageNum).toBe(1);
    });

    function scrolled(scrollHeight: number, scrollTop: number, clientHeight: number): Event {
      return { target: { scrollHeight, scrollTop, clientHeight } } as unknown as Event;
    }

    it('loads more public keys only once the list is scrolled to the bottom', () => {
      keyStoreService.listMavenPgpPublicKeys.calls.reset();

      component.onScrollPublicKeys(scrolled(500, 100, 200));
      expect(keyStoreService.listMavenPgpPublicKeys).not.toHaveBeenCalled();

      component.onScrollPublicKeys(scrolled(500, 300, 200));
      expect(keyStoreService.listMavenPgpPublicKeys).toHaveBeenCalledTimes(1);
    });
  });

  describe('deletePublicKey (RPS-1803)', () => {
    beforeEach(() => {
      component.ngOnInit();
      keyStoreService.listMavenPgpPublicKeys.calls.reset();
    });

    it('asks for confirmation before deleting anything', () => {
      component.deletePublicKey('pk1');

      expect(dangerModalService.modal).toEqual({ title: 'Delete Public Key', action: 'Delete', message: null });
      expect(keyStoreService.deleteMavenPgpPublicKey).not.toHaveBeenCalled();
    });

    it('deletes once confirmed, then reloads from the first page and toasts', () => {
      component.publicKeyPageNum = 4;
      component.deletePublicKey('pk1');

      dangerModalService.call();

      expect(keyStoreService.deleteMavenPgpPublicKey).toHaveBeenCalledOnceWith('pk1', REPO);
      expect(keyStoreService.listMavenPgpPublicKeys).toHaveBeenCalledOnceWith(REPO, 0, 5);
      expect(component.publicKeyPageNum).toBe(1);
      expect(toastService.show).toHaveBeenCalledOnceWith('Public key deleted', 'success');
    });

    it('neither reloads nor toasts when the delete fails', () => {
      keyStoreService.deleteMavenPgpPublicKey.and.returnValue(throwError(() => new Error('boom')));
      component.deletePublicKey('pk1');

      dangerModalService.call();

      expect(keyStoreService.listMavenPgpPublicKeys).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });
});
