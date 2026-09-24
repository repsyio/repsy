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

import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { GoModuleInfo, GoModuleVersionListItem, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { GolangService } from '../../service/golang.service';
import { GolangModuleVersionDetailComponent } from './golang-module-version-detail.component';

const REPO = 'go-repo';
const FOUND = { version: 'v1.2.3' } as GoModuleVersionListItem;

function moduleInfo(modulePath: string | undefined, versions: GoModuleVersionListItem[]): GoModuleInfo {
  return { modulePath, versions } as GoModuleInfo;
}

describe('GolangModuleVersionDetailComponent', () => {
  let component: GolangModuleVersionDetailComponent;
  let golangService: jasmine.SpyObj<GolangService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(query: Record<string, string> = { modulePath: 'github.com/acme/lib', version: 'v1.2.3' }): void {
    component?.ngOnDestroy();
    component = new GolangModuleVersionDetailComponent(
      { snapshot: { queryParamMap: convertToParamMap(query) } } as ActivatedRoute,
      router,
      golangService,
      toastService,
      dangerModalService,
    );
  }

  beforeEach(() => {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    golangService = jasmine.createSpyObj<GolangService>('GolangService', ['fetchModuleInfo', 'deleteModuleVersion'], {
      repoChanges,
    });
    golangService.fetchModuleInfo.and.returnValue(of(moduleInfo('github.com/acme/lib', [FOUND])));
    golangService.deleteModuleVersion.and.returnValue(of(undefined));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    build();
  });

  afterEach(() => component.ngOnDestroy());

  function select(canManage = true): void {
    repoChanges.next(permission(REPO, { canManage }));
  }

  describe('when a repository is selected', () => {
    it('finds the version of the query in the module info', () => {
      select();

      expect(golangService.fetchModuleInfo).toHaveBeenCalledOnceWith('github.com/acme/lib');
      expect(component.modulePath).toBe('github.com/acme/lib');
      expect(component.versionName).toBe('v1.2.3');
      expect(component.versionInfo).toBe(FOUND);
      expect(component.error).toBeUndefined();
      expect(component.loading).toBeFalse();
    });

    it('builds the go get and go env commands for the repository', () => {
      select();
      const proxy = `${environment.repoBaseUrl}/${REPO}`;

      expect(component.getCommand).toBe(`GONOSUMDB=* GOPROXY=${proxy},off go get github.com/acme/lib@v1.2.3`);
      expect(component.goEnvCommand).toBe(`go env -w GONOSUMDB="*" GOPROXY="${proxy},off"`);
    });

    it('lists the .info, .mod and .zip endpoints of the version', () => {
      select();
      const base = `${environment.repoBaseUrl}/${REPO}/github.com/acme/lib/@v/v1.2.3`;

      expect(component.goproxyEndpoints).toEqual([
        { label: '.info', url: `${base}.info` },
        { label: '.mod', url: `${base}.mod` },
        { label: '.zip', url: `${base}.zip` },
      ]);
    });

    it('shows an error when the module has no such version', () => {
      golangService.fetchModuleInfo.and.returnValue(
        of(moduleInfo('github.com/acme/lib', [{ version: 'v0.1.0' } as never])),
      );

      select();

      expect(component.error).toBe("Version 'v1.2.3' not found");
      expect(component.versionInfo).toBeUndefined();
      expect(component.loading).toBeFalse();
    });

    it('goes back to the repository without loading anything when the module path or version is missing', () => {
      build({ modulePath: 'github.com/acme/lib' });

      select();

      expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO}`]);
      expect(golangService.fetchModuleInfo).not.toHaveBeenCalled();
      expect(component.getCommand).toBeUndefined();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(router.navigate).not.toHaveBeenCalled();
      expect(golangService.fetchModuleInfo).not.toHaveBeenCalled();
    });

    it('stops loading when the request fails', () => {
      golangService.fetchModuleInfo.and.returnValue(throwError(() => new Error('boom')));

      select();

      expect(component.loading).toBeFalse();
      expect(component.versionInfo).toBeUndefined();
    });

    it('lets only a repository manager manage', () => {
      expect(component.canManage).toBeFalse();

      select(true);
      expect(component.canManage).toBeTrue();

      select(false);
      expect(component.canManage).toBeFalse();
    });

    it('stops following repository changes when destroyed', () => {
      component.ngOnDestroy();

      select();

      expect(golangService.fetchModuleInfo).not.toHaveBeenCalled();
    });
  });

  describe('securityArtifactName', () => {
    it('is the module path from the query until the module info has arrived', () => {
      golangService.fetchModuleInfo.and.returnValue(new Subject<GoModuleInfo>());

      select();

      expect(component.securityArtifactName).toBe('github.com/acme/lib');
    });

    it('is the canonical module path the server reports', () => {
      golangService.fetchModuleInfo.and.returnValue(of(moduleInfo('github.com/Acme/Lib', [FOUND])));

      select();

      expect(component.canonicalModulePath).toBe('github.com/Acme/Lib');
      expect(component.securityArtifactName).toBe('github.com/Acme/Lib');
    });

    it('falls back to the module path from the query when the server reports none', () => {
      golangService.fetchModuleInfo.and.returnValue(of(moduleInfo(undefined, [FOUND])));

      select();

      expect(component.securityArtifactName).toBe('github.com/acme/lib');
    });
  });

  describe('deleteVersion', () => {
    beforeEach(() => select());

    it('asks for confirmation before deleting anything', () => {
      component.deleteVersion();

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(golangService.deleteModuleVersion).not.toHaveBeenCalled();
    });

    it('deletes the version, then goes to the module page and toasts', async () => {
      golangService.fetchModuleInfo.and.returnValue(
        of(moduleInfo('github.com/acme/lib', [FOUND, { version: 'v1.0.0' } as GoModuleVersionListItem])),
      );
      select();
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(golangService.deleteModuleVersion).toHaveBeenCalledOnceWith('github.com/acme/lib', 'v1.2.3');
      expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO}/modules`], {
        queryParams: { modulePath: 'github.com/acme/lib' },
      });
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('goes to the module list when it deleted the last version, which removes the module', async () => {
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(golangService.deleteModuleVersion).toHaveBeenCalledOnceWith('github.com/acme/lib', 'v1.2.3');
      expect(router.navigate).toHaveBeenCalledOnceWith([`/${REPO}`]);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('stays on the page, without a toast, when the delete fails', () => {
      golangService.deleteModuleVersion.and.returnValue(throwError(() => new Error('boom')));
      component.deleteVersion();

      dangerModalService.call();

      expect(router.navigate).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
    });
  });
});
