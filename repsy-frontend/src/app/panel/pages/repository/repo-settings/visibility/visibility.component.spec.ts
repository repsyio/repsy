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

import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';

import { ReposApi } from '../../../../../../generated/api';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoType } from '../../../../shared/dtos/repo/repo-type';
import { renderComponent } from '../../testing/render-spec-helpers';
import { generalParentForm, lastSentForm, releaseAwareParentForm } from '../testing/repo-settings-spec-helpers';
import { VisibilityComponent } from './visibility.component';

const REPO = 'acme-repo';

describe('VisibilityComponent', () => {
  let component: VisibilityComponent;
  let repoApi: jasmine.SpyObj<ReposApi>;
  let toastService: jasmine.SpyObj<ToastService>;
  let fetchCount: number;

  beforeEach(() => {
    repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['updateRepoSettings']);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    repoApi.updateRepoSettings.and.returnValue(of({}) as never);

    component = new VisibilityComponent(repoApi, toastService);
    component.repoName = REPO;
    fetchCount = 0;
    component.fetch.subscribe(() => fetchCount++);
  });

  it('makes a repository private and sends only the visibility, whatever the other settings are', () => {
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({
      privateRepository: false,
      allowOverride: false,
      securityScanEnabled: false,
    });

    component.changePrivacy(false);

    expect(component.parentForm.get('privateRepository').value).toBeTrue();
    expect(repoApi.updateRepoSettings.calls.mostRecent().args[0]).toBe(REPO);
    expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ privateRepo: true });
    expect(toastService.show).toHaveBeenCalledOnceWith('Repository visibility has changed as private', 'success');
    expect(fetchCount).toBe(1);
  });

  it('makes a repository public', () => {
    component.repoType = RepoType.PYPI;
    component.parentForm = generalParentForm({ privateRepository: true });

    component.changePrivacy(true);

    expect(component.parentForm.get('privateRepository').value).toBeFalse();
    expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ privateRepo: false });
    expect(toastService.show).toHaveBeenCalledOnceWith('Repository visibility has changed as public', 'success');
  });

  [RepoType.NUGET, RepoType.MAVEN].forEach((repoType) => {
    it(`does not send the release, snapshot, override or scan flags of a ${repoType} repository (RPS-1619)`, () => {
      component.repoType = repoType;
      component.parentForm = releaseAwareParentForm({
        releases: true,
        snapshots: false,
        allowOverride: false,
        securityScanEnabled: false,
        pgpVerifyAllSignaturesEnabled: true,
      });

      component.changePrivacy(false);

      expect(lastSentForm(repoApi.updateRepoSettings, 1)).toEqual({ privateRepo: true });
    });
  });

  it('locks the toggle while the save is on its way and unlocks it afterwards', () => {
    const inFlight = new Subject<object>();
    repoApi.updateRepoSettings.and.returnValue(inFlight as never);
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm();

    component.changePrivacy(false);

    expect(component.saving).toBeTrue();

    inFlight.next({});
    inFlight.complete();

    expect(component.saving).toBeFalse();
  });

  it('puts the toggle back to the stored value, and neither refreshes nor toasts, when saving fails (RPS-1618)', () => {
    repoApi.updateRepoSettings.and.returnValue(throwError(() => new Error('boom')));
    component.repoType = RepoType.NPM;
    component.parentForm = generalParentForm({ privateRepository: true });

    component.changePrivacy(true);

    expect(component.parentForm.get('privateRepository').value).toBeTrue();
    expect(component.saving).toBeFalse();
    expect(fetchCount).toBe(0);
    expect(toastService.show).not.toHaveBeenCalled();
  });
});

describe('VisibilityComponent template', () => {
  async function render(privateRepository: boolean): Promise<HTMLElement> {
    const { el } = await renderComponent(
      VisibilityComponent,
      [
        { provide: ReposApi, useValue: {} },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
      { repoName: REPO, repoType: RepoType.NPM, parentForm: generalParentForm({ privateRepository }) },
    );
    return el;
  }

  const text = (el: HTMLElement, testId: string): string | undefined =>
    el.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim();

  it('describes the toggle by what Public and Private mean, not by "active" (RPS-1261)', async () => {
    const el = await render(false);

    expect(text(el, 'toggle-label')).toBe('Public');
    expect(el.textContent).not.toContain('When active');
    expect(el.textContent).not.toContain('only authorized users can access');
    expect(el.textContent).toContain('Public: anyone can read the repository without signing in.');
    expect(el.textContent).toContain(
      'Private: signing in is required, and every signed-in user can read and write it.',
    );
  });

  it('hints at the switch to the OTHER state (RPS-1261)', async () => {
    expect(text(await render(false), 'settings-visibility-hint')).toBe('Turn it off to require signing in.');

    TestBed.resetTestingModule();
    expect(text(await render(true), 'settings-visibility-hint')).toBe('Turn it on to make the repository public.');
  });
});

describe('VisibilityComponent toggle', () => {
  it('ignores a second click while the first save is on its way (RPS-1618)', async () => {
    const inFlight = new Subject<object>();
    const repoApi = jasmine.createSpyObj<ReposApi>('ReposApi', ['updateRepoSettings']);
    repoApi.updateRepoSettings.and.returnValue(inFlight as never);
    const { el, fixture } = await renderComponent(
      VisibilityComponent,
      [
        { provide: ReposApi, useValue: repoApi },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['show']) },
      ],
      { repoName: REPO, repoType: RepoType.NPM, parentForm: generalParentForm({ privateRepository: false }) },
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
