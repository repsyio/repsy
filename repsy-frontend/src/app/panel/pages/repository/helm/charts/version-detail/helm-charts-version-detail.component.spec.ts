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

import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';

import { environment } from '../../../../../../../environments/environment';
import { HelmChartDetail, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { ByteFormatter } from '../../../../../shared/util/byte-formatter';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { HelmService } from '../../service/helm.service';
import { HelmChartsVersionDetailComponent } from './helm-charts-version-detail.component';

const REPO = 'helm-repo';
const FULL: HelmChartDetail = {
  name: 'nginx',
  version: '1.2.3',
  description: 'A web server',
  appVersion: '2.4',
  type: 'application',
  size: 2048,
};

describe('HelmChartsVersionDetailComponent', () => {
  let component: HelmChartsVersionDetailComponent;
  let route: ActivatedRoute;
  let helmService: jasmine.SpyObj<HelmService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(params: Record<string, string> = { name: 'nginx', version: '1.2.3' }): void {
    component?.ngOnDestroy();
    route = { snapshot: { paramMap: convertToParamMap(params) } } as ActivatedRoute;
    component = new HelmChartsVersionDetailComponent(route, helmService, toastService, dangerModalService, router);
  }

  beforeEach(() => {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    helmService = jasmine.createSpyObj<HelmService>(
      'HelmService',
      ['getChartDetail', 'getChartVersions', 'deleteChart'],
      {
        repoChanges,
      },
    );
    helmService.getChartDetail.and.returnValue(of(FULL));
    helmService.getChartVersions.and.returnValue(of([{ version: '1.2.3' }, { version: '1.2.4' }] as never));
    helmService.deleteChart.and.returnValue(of(undefined));
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    router.navigate.and.resolveTo(true);
    dangerModalService = new DangerModalService();
    build();
  });

  afterEach(() => component.ngOnDestroy());

  function select(): void {
    repoChanges.next(permission(REPO, { canManage: true }));
  }

  describe('when a repository is selected', () => {
    it('loads the chart version of the route', () => {
      select();

      expect(helmService.getChartDetail).toHaveBeenCalledOnceWith('nginx', '1.2.3');
      expect(component.chartName).toBe('nginx');
      expect(component.versionName).toBe('1.2.3');
      expect(component.chart).toBe(FULL);
      expect(component.loading).toBeFalse();
      expect(component.error).toBeNull();
    });

    it('builds the classic install command and the OCI pull command without the URL scheme', () => {
      select();

      expect(component.classicInstallCommand).toBe(`helm install nginx ${REPO}/nginx --version 1.2.3`);
      const host = environment.repoBaseUrl.replace(/^https?:\/\//, '');
      expect(component.ociPullCommand).toBe(`helm pull oci://${host}/${REPO}/nginx --version 1.2.3`);
      expect(component.ociPullCommand).not.toContain('://http');
    });

    it('formats the size and writes a Chart.yaml from the chart details', () => {
      select();

      expect(component.formattedSize).toBe(ByteFormatter.formatBytes(2048));
      expect(component.chartYaml).toBe(
        [
          'apiVersion: v2',
          'name: nginx',
          'version: 1.2.3',
          'description: A web server',
          'appVersion: "2.4"',
          'type: application',
        ].join('\n'),
      );
    });

    it('leaves the optional Chart.yaml fields out when the chart has none', () => {
      helmService.getChartDetail.and.returnValue(of({ name: 'nginx', version: '1.2.3', size: 0 }));

      select();

      expect(component.chartYaml).toBe('apiVersion: v2\nname: nginx\nversion: 1.2.3');
    });

    it('does not load anything, and stops loading, when the route has no chart or version', () => {
      build({ name: 'nginx' });

      select();

      expect(helmService.getChartDetail).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(helmService.getChartDetail).not.toHaveBeenCalled();
    });

    it('stops loading and shows no chart when the request fails', () => {
      helmService.getChartDetail.and.returnValue(throwError(() => 'boom'));

      select();

      expect(component.loading).toBeFalse();
      expect(component.chart).toBeUndefined();
      expect(component.chartYaml).toBe('');
    });

    it('stops following repository changes when destroyed', () => {
      component.ngOnDestroy();

      select();

      expect(helmService.getChartDetail).not.toHaveBeenCalled();
    });
  });

  describe('deleteVersion', () => {
    beforeEach(() => select());

    it('asks for confirmation before deleting anything', () => {
      component.deleteVersion();

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(helmService.deleteChart).not.toHaveBeenCalled();
    });

    it('deletes the version, then goes up to the chart and toasts while others remain', async () => {
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(helmService.getChartVersions).toHaveBeenCalledOnceWith('nginx');
      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.2.3');
      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    // RPS-1302: the chart is gone with its last version, so its versions page answers 404.
    it('goes to the chart list, never to the versions page of the removed chart, after the last version', async () => {
      helmService.getChartVersions.and.returnValue(of([{ version: '1.2.3' }] as never));
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(helmService.deleteChart).toHaveBeenCalledOnceWith('nginx', '1.2.3');
      expect(router.navigate).toHaveBeenCalledOnceWith(['/', REPO]);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('shows the page as loading until the delete answers', () => {
      const answer = new Subject<void>();
      helmService.deleteChart.and.returnValue(answer);
      component.deleteVersion();

      dangerModalService.call();
      expect(component.loading).toBeTrue();

      answer.complete();
      expect(component.loading).toBeFalse();
    });

    // The error interceptor shows the failure; the component passing the HttpErrorResponse to the
    // toast service read "[object Object]" (RPS-1302).
    it('shows no toast of its own and stays on the page when the delete fails', () => {
      helmService.deleteChart.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 500, error: { text: 'Chart is locked' } })),
      );
      component.deleteVersion();

      dangerModalService.call();

      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('deletes nothing when the versions of the chart cannot be read', () => {
      helmService.getChartVersions.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      component.deleteVersion();

      dangerModalService.call();

      expect(helmService.deleteChart).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });
  });
});
