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

import { BehaviorSubject, NEVER, of, Subject, throwError } from 'rxjs';

import { environment } from '../../../../../../environments/environment';
import { RepoPermissionInfo } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ByteFormatter } from '../../../../shared/util/byte-formatter';
import { permission } from '../../testing/protocol-service-spec-helpers';
import { FsItemInfo } from '../dto/fs-item-info';
import { MavenService } from '../service/maven.service';
import { MavenBrowserComponent } from './maven-browser.component';

const REPO = 'maven-repo';

function dir(name: string): FsItemInfo {
  return { name, directory: true, size: 0, createdAt: new Date(0) };
}

function file(name: string): FsItemInfo {
  return { name, directory: false, size: 10, createdAt: new Date(0) };
}

describe('MavenBrowserComponent', () => {
  let component: MavenBrowserComponent;
  let mavenService: jasmine.SpyObj<MavenService>;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;
  let contents: Record<string, FsItemInfo[]>;

  beforeEach(() => {
    contents = {
      '/': [dir('org/'), dir('com/'), file('readme.txt')],
      '/org/': [dir('../'), dir('acme/'), file('Maven-Metadata.xml')],
      '/org/acme/': [dir('../'), file('lib-1.0.jar')],
    };
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    mavenService = jasmine.createSpyObj<MavenService>('MavenService', ['getPathContent', 'createDownloadToken'], {
      repoChanges,
    });
    mavenService.getPathContent.and.callFake((path: string) => of(contents[path] ?? []));
    mavenService.createDownloadToken.and.returnValue(NEVER);
    component = new MavenBrowserComponent(mavenService, jasmine.createSpyObj<ToastService>('ToastService', ['show']));
  });

  afterEach(() => component.ngOnDestroy());

  function open(): void {
    repoChanges.next(permission(REPO, { canManage: true }));
  }

  const paths = (): string[] => component.directoryStack.map((d) => d.path);

  describe('before a repository is selected', () => {
    it('shows nothing and loads nothing', () => {
      expect(mavenService.getPathContent).not.toHaveBeenCalled();
      expect(component.directoryStack).toEqual([]);
      expect(component.loading).toBeFalse();
    });
  });

  describe('when a repository is selected', () => {
    it('lists its root directory', () => {
      open();

      expect(mavenService.getPathContent).toHaveBeenCalledOnceWith('/');
      expect(paths()).toEqual(['/']);
      expect(component.fsItems.map((i) => i.name)).toEqual(['org/', 'com/', 'readme.txt']);
      expect(component.filteredFsItems).toBe(component.fsItems);
      expect(component.repoUrl).toBe(`${environment.repoBaseUrl}/${REPO}/`);
      expect(component.baseUrl).toBe(environment.apiBaseUrl);
      expect(component.loading).toBeFalse();
      expect(component.operationLock).toBeFalse();
    });

    it('starts over at the root when another repository is selected', () => {
      open();
      component.go(dir('org/'));

      repoChanges.next(permission('other-repo'));

      expect(paths()).toEqual(['/']);
      expect(component.repoUrl).toBe(`${environment.repoBaseUrl}/other-repo/`);
    });

    it('forgets what next would have gone forward to when another repository is selected', () => {
      open();
      component.go(dir('org/'));
      component.prev();
      expect(component.forwardStack.length).toBe(1);

      repoChanges.next(permission('other-repo'));
      mavenService.getPathContent.calls.reset();
      component.next();

      expect(component.forwardStack).toEqual([]);
      expect(paths()).toEqual(['/']);
      expect(mavenService.getPathContent).not.toHaveBeenCalled();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(mavenService.getPathContent).not.toHaveBeenCalled();
    });

    it('keeps the lock and the loading state until the listing has arrived', () => {
      const answer = new Subject<FsItemInfo[]>();
      mavenService.getPathContent.and.returnValue(answer);

      open();

      expect(component.loading).toBeTrue();
      expect(component.operationLock).toBeTrue();

      answer.next([file('a')]);
      answer.complete();

      expect(component.loading).toBeFalse();
      expect(component.operationLock).toBeFalse();
    });

    it('releases the lock, and stops loading, when the listing cannot be loaded', () => {
      mavenService.getPathContent.and.returnValue(throwError(() => new Error('boom')));

      open();

      expect(component.loading).toBeFalse();
      expect(component.operationLock).toBeFalse();
    });
  });

  describe('navigating', () => {
    beforeEach(() => open());

    it('go into a directory adds it to the path and lists it', () => {
      component.go(dir('org/'));

      expect(paths()).toEqual(['/', '/org/']);
      expect(mavenService.getPathContent).toHaveBeenCalledWith('/org/');
      expect(component.repoUrl).toBe(`${environment.repoBaseUrl}/${REPO}/org/`);

      component.go(dir('acme/'));

      expect(paths()).toEqual(['/', '/org/', '/org/acme/']);
      expect(component.repoUrl).toBe(`${environment.repoBaseUrl}/${REPO}/org/acme/`);
    });

    it('go to ../ leaves the directory and remembers it for going forward', () => {
      component.go(dir('org/'));
      component.go(dir('acme/'));

      component.go(dir('../'));

      expect(paths()).toEqual(['/', '/org/']);
      expect(component.forwardStack.map((d) => d.path)).toEqual(['/org/acme/']);
      expect(mavenService.getPathContent).toHaveBeenCalledWith('/org/');
    });

    it('go into a directory after going back drops the directories next would have returned to', () => {
      component.go(dir('org/'));
      component.go(dir('acme/'));
      component.go(dir('../'));
      expect(component.forwardStack.length).toBe(1);

      component.go(dir('com/'));

      expect(paths()).toEqual(['/', '/org/', '/org/com/']);
      expect(component.forwardStack).toEqual([]);
    });

    it('goToDir drops the directories next would have returned to', () => {
      component.go(dir('org/'));
      component.go(dir('acme/'));
      component.prev();
      expect(component.forwardStack.length).toBe(1);

      component.goToDir(component.directoryStack[0]);

      expect(component.forwardStack).toEqual([]);
    });

    it('go on a file requests a download token for its full path', () => {
      component.go(dir('org/'));

      component.go(file('Maven-Metadata.xml'));

      expect(mavenService.createDownloadToken).toHaveBeenCalledOnceWith('/org/Maven-Metadata.xml');
    });

    it('go on a file whose download token cannot be created: no unhandled error, nothing is downloaded', async () => {
      component.go(dir('org/'));
      mavenService.createDownloadToken.and.returnValue(throwError(() => new Error('boom')));
      // RxJS reports an unhandled subscriber error asynchronously, and Jasmine fails the spec on it.
      const unhandled: unknown[] = [];
      const onError = (event: ErrorEvent): void => {
        unhandled.push(event.error);
        event.preventDefault();
      };
      window.addEventListener('error', onError);

      try {
        component.go(file('Maven-Metadata.xml'));
        await new Promise<void>((resolve) => setTimeout(resolve));

        expect(unhandled).toEqual([]);
        expect(mavenService.createDownloadToken).toHaveBeenCalledOnceWith('/org/Maven-Metadata.xml');
        expect(paths()).toEqual(['/', '/org/']);
        expect(component.loading).toBeFalse();
      } finally {
        window.removeEventListener('error', onError);
      }
    });

    it('go and goToDir do nothing while a listing is still loading', () => {
      mavenService.getPathContent.and.returnValue(NEVER);
      component.go(dir('org/'));
      const root = component.directoryStack[0];
      mavenService.getPathContent.calls.reset();

      component.go(dir('acme/'));
      component.go(file('x.jar'));
      component.goToDir(root);

      expect(paths()).toEqual(['/', '/org/']);
      expect(mavenService.getPathContent).not.toHaveBeenCalled();
      expect(mavenService.createDownloadToken).not.toHaveBeenCalled();
    });

    it('goToDir jumps back to a directory of the path and lists it', () => {
      component.go(dir('org/'));
      component.go(dir('acme/'));
      const org = component.directoryStack[1];

      component.goToDir(org);

      expect(paths()).toEqual(['/', '/org/']);
      expect(mavenService.getPathContent).toHaveBeenCalledWith('/org/');
      expect(component.repoUrl).toBe(`${environment.repoBaseUrl}/${REPO}/org/`);
    });

    it('prev steps up one directory and next steps down again', () => {
      component.go(dir('org/'));
      component.go(dir('acme/'));

      component.prev();
      expect(paths()).toEqual(['/', '/org/']);
      expect(component.forwardStack.map((d) => d.path)).toEqual(['/org/acme/']);

      component.next();
      expect(paths()).toEqual(['/', '/org/', '/org/acme/']);
      expect(component.forwardStack).toEqual([]);
      expect(mavenService.getPathContent.calls.mostRecent().args).toEqual(['/org/acme/']);
    });

    it('prev does nothing at the root, and next does nothing with nothing to go forward to', () => {
      mavenService.getPathContent.calls.reset();

      component.prev();
      component.next();

      expect(paths()).toEqual(['/']);
      expect(mavenService.getPathContent).not.toHaveBeenCalled();
    });
  });

  describe('search', () => {
    beforeEach(() => open());

    it('keeps the entries whose name contains the text, ignoring case', () => {
      component.search('OR');

      expect(component.searchText).toBe('OR');
      expect(component.filteredFsItems.map((i) => i.name)).toEqual(['org/']);
    });

    it('shows everything for an empty text, and nothing when no name matches', () => {
      component.search('');
      expect(component.filteredFsItems.length).toBe(3);

      component.search('zzz');
      expect(component.filteredFsItems).toEqual([]);
    });

    it('is replaced by the full listing of the next directory', () => {
      component.search('org');

      component.go(dir('org/'));

      expect(component.filteredFsItems).toBe(component.fsItems);
    });
  });

  describe('helpers', () => {
    it('openConfig toggles the config panel', () => {
      component.openConfig(true);
      expect(component.showConfig).toBeTrue();
      component.openConfig(false);
      expect(component.showConfig).toBeFalse();
    });

    it('formatBytes renders a size the way the byte formatter does, with two decimals by default', () => {
      expect(component.formatBytes(1536)).toBe(ByteFormatter.formatBytes(1536, 2));
      expect(component.formatBytes(1536, 0)).toBe(ByteFormatter.formatBytes(1536, 0));
    });
  });

  describe('ngOnDestroy', () => {
    it('stops following repository changes', () => {
      component.ngOnDestroy();

      open();

      expect(mavenService.getPathContent).not.toHaveBeenCalled();
    });
  });
});
