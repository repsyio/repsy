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

import { of, Subject, throwError } from 'rxjs';

import { ReposApi } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { renderComponent } from '../../testing/render-spec-helpers';
import { generalParentForm, lastSentForm, releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { PackageOverrideComponent } from './package-override.component';

const REPO = 'acme-repo';

describe('PackageOverrideComponent', () => {
  let component: PackageOverrideComponent;
  let repoApi: jasmine.SpyObj<ReposApi>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['updateRepoSettings']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    repoApi.updateRepoSettings.and.returnValue(of({}) as never);

    component = new PackageOverrideComponent(repoApi, toastService);
    component.repoName = REPO;
    fetchCount = 0;
    component.fetch.subscribe(() => fetchCount++);
  });

  it('starts from the override setting of the parent form', () => {
    component.parentForm = generalParentForm({ allowOverride: false });
    component.ngOnInit();

    expect(component.allowOverride).toBeFalse();
  });

  it('blocks overriding and sends only the override, whatever the other settings are', () => {
    component.repoType = RepoType.DOCKER;
    component.parentForm = generalParentForm({
      privateRepository: true,
      allowOverride: true,
      securityScanEnabled: false,
    });
    component.ngOnInit();
    component.allowOverride = false;

    component.changeOverride();

    expect(repoApi.updateRepoSettings.calls.mostRecent().args[0]).toBe(REPO);
    expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ allowOverride: false });
    expect(component.parentForm.get('allowOverride').value).toBeFalse();
    expect(toastService.show).toHaveBeenCalledOnceWith('Package override is now blocked', 'success');
    expect(fetchCount).toBe(1);
  });

  it('allows overriding again', () => {
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: false });
    component.ngOnInit();
    component.allowOverride = true;

    component.changeOverride();

    expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ allowOverride: true });
    expect(component.parentForm.get('allowOverride').value).toBeTrue();
    expect(toastService.show).toHaveBeenCalledOnceWith('Package override is now allowed', 'success');
  });

  [RepoType.MAVEN, RepoType.NUGET].forEach((repoType) => {
    it(`does not send the visibility, release, snapshot or scan flags of a ${repoType} repository (RPS-1619)`, () => {
      component.repoType = repoType;
      component.parentForm = releaseAwareParentForm({
        privateRepository: true,
        releases: true,
        snapshots: false,
        allowOverride: true,
        securityScanEnabled: false,
      });
      component.ngOnInit();
      component.allowOverride = false;

      component.changeOverride();

      expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ allowOverride: false });
    });
  });

  it('locks the toggle while the save is on its way and unlocks it afterwards', () => {
    const inFlight = new Subject<object>();
    repoApi.updateRepoSettings.and.returnValue(inFlight as never);
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: true });
    component.ngOnInit();
    component.allowOverride = false;

    component.changeOverride();

    expect(component.saving).toBeTrue();

    inFlight.next({});
    inFlight.complete();

    expect(component.saving).toBeFalse();
  });

  it('puts the toggle back to the stored value, and neither refreshes nor toasts, when saving fails (RPS-1618)', () => {
    repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: true });
    component.ngOnInit();
    component.allowOverride = false;

    component.changeOverride();

    expect(component.allowOverride).toBeTrue();
    expect(component.parentForm.get('allowOverride').value).toBeTrue();
    expect(component.saving).toBeFalse();
    expect(fetchCount).toBe(0);
    expect(toastService.show).not.toHaveBeenCalled();
  });

  it('puts a denied override back to Deny when saving the change to Allow fails', () => {
    repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ allowOverride: false });
    component.ngOnInit();
    component.allowOverride = true;

    component.changeOverride();

    expect(component.allowOverride).toBeFalse();
  });
});

describe('PackageOverrideComponent template', () => {
  it('describes what Allow and Deny do instead of "when active ... will be blocked" (RPS-1261)', async () => {
    const { el } = await renderComponent(
      PackageOverrideComponent,
      [
        { provide: ReposApi, useValue: {} },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
      { repoName: REPO, repoType: RepoType.NPM, parentForm: generalParentForm({ allowOverride: true }) },
    );

    expect(el.querySelector('[data-testid="toggle-label"]')?.textContent?.trim()).toBe('Allow');
    expect(el.textContent).not.toContain('will be blocked');
    expect(el.textContent).toContain('Allow: users can upload the same version again and overwrite it.');
    expect(el.textContent).toContain('Deny: uploading the same version again is blocked.');
  });

  const providers = () => [
    { provide: ReposApi, useValue: {} },
    { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
  ];

  it('tells a Maven repository that a SNAPSHOT can always be deployed again (RPS-1328)', async () => {
    const { el } = await renderComponent(PackageOverrideComponent, providers(), {
      repoName: REPO,
      repoType: RepoType.MAVEN,
      parentForm: releaseAwareParentForm({ allowOverride: false }),
    });
    const note = el.querySelector('[data-testid="settings-override-maven-note"]');

    expect(note?.textContent).toContain('a SNAPSHOT can always be deployed again');
    expect(note?.textContent).toContain('timestamped SNAPSHOT builds cannot be replaced');
    expect(el.textContent).not.toContain('will be blocked');
  });

  it('keeps the Maven note off every other repository type (RPS-1328)', async () => {
    const { el } = await renderComponent(PackageOverrideComponent, providers(), {
      repoName: REPO,
      repoType: RepoType.NPM,
      parentForm: generalParentForm({ allowOverride: false }),
    });

    expect(el.querySelector('[data-testid="settings-override-maven-note"]')).toBeNull();
  });
});

describe('PackageOverrideComponent toggle', () => {
  it('ignores a second click while the first save is on its way (RPS-1618)', async () => {
    const inFlight = new Subject<object>();
    const repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['updateRepoSettings']);
    repoApi.updateRepoSettings.and.returnValue(inFlight as never);
    const { el, fixture } = await renderComponent(
      PackageOverrideComponent,
      [
        { provide: ReposApi, useValue: repoApi },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
      { repoName: REPO, repoType: RepoType.NPM, parentForm: generalParentForm({ allowOverride: true }) },
    );
    const input = el.querySelector<HTMLInputElement>('[data-testid="toggle-input"]');

    input.click();
    fixture.detectChanges();
    input.click();
    input.click();

    expect(input.disabled).toBeTrue();
    expect(repoApi.updateRepoSettings).toHaveBeenCalledTimes(1);
  });
});
