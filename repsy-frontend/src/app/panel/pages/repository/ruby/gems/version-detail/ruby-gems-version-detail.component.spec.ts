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
import { GemVersionInfo, RepoPermissionInfo } from '../../../../../../../generated/api';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { VERSION_PROBE_SORT } from '../../../../../shared/util/version-delete-landing.util';
import { permission } from '../../../testing/protocol-service-spec-helpers';
import { RubyService } from '../../service/ruby.service';
import { RubyGemsVersionDetailComponent } from './ruby-gems-version-detail.component';

const REPO = 'ruby-repo';
/** A page of versions: the delete flow reads nothing but `content`. */
const pageOf = (content: unknown[]): never => ({ content }) as never;
const GEM_VERSION = { platform: 'x86_64-linux' } as GemVersionInfo;

describe('RubyGemsVersionDetailComponent', () => {
  let component: RubyGemsVersionDetailComponent;
  let route: ActivatedRoute;
  let rubyService: jasmine.SpyObj<RubyService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let router: jasmine.SpyObj<Router>;
  let dangerModalService: DangerModalService;
  let repoChanges: BehaviorSubject<RepoPermissionInfo | null>;

  function build(params: Record<string, string> = { gem: 'rails', version: '7.1.0' }): void {
    component?.ngOnDestroy();
    route = { snapshot: { paramMap: convertToParamMap(params) } } as ActivatedRoute;
    component = new RubyGemsVersionDetailComponent(route, rubyService, toastService, dangerModalService, router);
  }

  beforeEach(() => {
    repoChanges = new BehaviorSubject<RepoPermissionInfo | null>(null);
    rubyService = jasmine.createSpyObj<RubyService>(
      'RubyService',
      ['fetchGemVersion', 'fetchGemVersions', 'deleteGemVersion'],
      { repoChanges },
    );
    rubyService.fetchGemVersions.and.returnValue(of(pageOf([{ version: '7.1.0' }, { version: '7.0.0' }])));
    rubyService.fetchGemVersion.and.returnValue(of(GEM_VERSION));
    rubyService.deleteGemVersion.and.returnValue(of(undefined));
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

  it('loads nothing before a repository is selected', () => {
    expect(component.loading).toBeTrue();
    expect(rubyService.fetchGemVersion).not.toHaveBeenCalled();
  });

  describe('when a repository is selected', () => {
    it('loads the gem version of the route', () => {
      select();

      expect(rubyService.fetchGemVersion).toHaveBeenCalledOnceWith('rails', '7.1.0');
      expect(component.gemName).toBe('rails');
      expect(component.versionName).toBe('7.1.0');
      expect(component.gemVersion).toBe(GEM_VERSION);
      expect(component.loading).toBeFalse();
      expect(component.error).toBeNull();
    });

    it('builds the install command and the Gemfile snippet for that version and repository', () => {
      select();
      const repoUrl = `${environment.repoBaseUrl}/${REPO}/`;

      expect(component.installCommand).toBe(`gem install rails -v 7.1.0 --source ${repoUrl}`);
      expect(component.gemfileSnippet).toBe(`source "${repoUrl}" do\n  gem "rails", "7.1.0"\nend`);
    });

    it('does not load anything, and stops loading, when the route has no gem or version', () => {
      build({ gem: 'rails' });

      select();

      expect(rubyService.fetchGemVersion).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
      expect(component.installCommand).toBeUndefined();
    });

    it('ignores an empty repository value', () => {
      repoChanges.next(null);

      expect(rubyService.fetchGemVersion).not.toHaveBeenCalled();
    });

    it('stops loading and shows no version when the request fails', () => {
      rubyService.fetchGemVersion.and.returnValue(throwError(() => new Error('boom')));

      select();

      expect(component.loading).toBeFalse();
      expect(component.gemVersion).toBeUndefined();
      expect(component.gemfileSnippet).toBe('');
    });

    it('stops following repository changes when destroyed', () => {
      component.ngOnDestroy();

      select();

      expect(rubyService.fetchGemVersion).not.toHaveBeenCalled();
    });
  });

  describe('deleteVersion', () => {
    beforeEach(() => select());

    it('asks for confirmation before deleting anything', () => {
      component.deleteVersion();

      expect(dangerModalService.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      expect(rubyService.deleteGemVersion).not.toHaveBeenCalled();
    });

    it('deletes the version with its platform, then goes to the versions page of the gem and toasts', async () => {
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(rubyService.fetchGemVersions).toHaveBeenCalledOnceWith('rails', '', VERSION_PROBE_SORT, 0, 2);
      expect(rubyService.deleteGemVersion).toHaveBeenCalledOnceWith('rails', '7.1.0', 'x86_64-linux');
      expect(router.navigate).toHaveBeenCalledOnceWith(['..'], { relativeTo: route });
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
      expect(component.loading).toBeFalse();
    });

    // The gem is gone with its last version, so its versions page would answer 404 (RPS-1288).
    it('goes to the gem list of the repository after the last version', async () => {
      rubyService.fetchGemVersions.and.returnValue(of(pageOf([{ version: '7.1.0' }])));
      component.deleteVersion();

      dangerModalService.call();
      await Promise.resolve();

      expect(rubyService.deleteGemVersion).toHaveBeenCalledOnceWith('rails', '7.1.0', 'x86_64-linux');
      expect(router.navigate).toHaveBeenCalledOnceWith(['/', REPO]);
      expect(toastService.show).toHaveBeenCalledOnceWith('Version deleted successfully', 'success');
    });

    it('deletes nothing when the versions of the gem cannot be read', () => {
      rubyService.fetchGemVersions.and.returnValue(throwError(() => new Error('boom')));
      component.deleteVersion();

      dangerModalService.call();

      expect(rubyService.deleteGemVersion).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });

    it('falls back to the ruby platform when the version info has none', () => {
      rubyService.fetchGemVersion.and.returnValue(throwError(() => new Error('boom')));
      build();
      select();
      component.deleteVersion();

      dangerModalService.call();

      expect(rubyService.deleteGemVersion).toHaveBeenCalledOnceWith('rails', '7.1.0', 'ruby');
    });

    it('shows the page as loading until the delete answers', () => {
      const answer = new Subject<void>();
      rubyService.deleteGemVersion.and.returnValue(answer);
      component.deleteVersion();

      dangerModalService.call();
      expect(component.loading).toBeTrue();

      answer.complete();
      expect(component.loading).toBeFalse();
    });

    it('stays on the page, without a toast, when the delete fails', () => {
      rubyService.deleteGemVersion.and.returnValue(throwError(() => new Error('boom')));
      component.deleteVersion();

      dangerModalService.call();

      expect(router.navigate).not.toHaveBeenCalled();
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.loading).toBeFalse();
    });
  });
});
