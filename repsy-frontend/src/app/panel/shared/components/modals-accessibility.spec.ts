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

import { LoginComponent } from '../../../auth/pages/login/login.component';
import { AuthService } from '../../../auth/pages/service/auth.service';
import { TokenCreateInfo } from '../../pages/repository/repo-settings/deploy-token/dto/token-create-info';
import { renderComponent } from '../../pages/repository/testing/render-spec-helpers';
import { DangerModalComponent } from './modals/danger-modal/danger-modal.component';
import { DangerModalService } from './modals/danger-modal/danger-modal.service';
import { DeployTokenCreateModalComponent } from './modals/deploy-token-create-modal/deploy-token-create-modal.component';
import { DeployTokenInfoModalComponent } from './modals/deploy-token-info-modal/deploy-token-info-modal.component';
import { RepositoryCreateModalComponent } from './modals/repository-create-modal/repository-create-modal.component';
import { UserCreateModalComponent } from './modals/user-create-modal/user-create-modal.component';
import { UserEditModalComponent } from './modals/user-edit-modal/user-edit-modal.component';
import { UserResetPasswordModalComponent } from './modals/user-reset-password-modal/user-reset-password-modal.component';

/**
 * RPS-1266: every modal is a named dialog, every form control is labelled, and no id repeats. The modals are
 * rendered from their real templates; the e2e suite (A11Y-05..) checks the same on the running panel.
 */
describe('modal and form accessibility', () => {
  const NAMED_CONTROLS = 'input:not([type="hidden"]), textarea, select';

  function nameOf(control: HTMLElement): string {
    const labelledBy = control.getAttribute('aria-labelledby');
    if (labelledBy) {
      return labelledBy
        .split(' ')
        .map((id) => document.getElementById(id)?.textContent?.trim() ?? '')
        .join(' ')
        .trim();
    }
    const label = control.getAttribute('aria-label');
    if (label) {
      return label;
    }
    const labels = Array.from((control as HTMLInputElement).labels ?? []);
    return labels
      .map((element) => element.textContent?.trim() ?? '')
      .join(' ')
      .trim();
  }

  /** The label of every control resolves (`for` points at that control), and no id occurs twice. */
  function expectLabelled(root: HTMLElement): void {
    const ids = Array.from(root.querySelectorAll('[id]')).map((element) => element.id);
    expect(ids.filter((id, index) => ids.indexOf(id) !== index))
      .withContext('duplicate ids')
      .toEqual([]);

    for (const label of Array.from(root.querySelectorAll('label[for]'))) {
      const target = document.getElementById(label.getAttribute('for'));
      expect(target)
        .withContext(`label for="${label.getAttribute('for')}"`)
        .not.toBeNull();
      expect(target.tagName)
        .withContext(`label for="${label.getAttribute('for')}"`)
        .toMatch(/INPUT|TEXTAREA|SELECT/);
    }

    for (const control of Array.from(root.querySelectorAll<HTMLElement>(NAMED_CONTROLS))) {
      if (control.matches('[type="radio"], [type="checkbox"]')) {
        continue;
      }
      expect(nameOf(control))
        .withContext(control.getAttribute('data-testid') ?? control.outerHTML)
        .not.toBe('');
    }

    for (const button of Array.from(root.querySelectorAll<HTMLElement>('button'))) {
      const name = button.getAttribute('aria-label') || button.textContent?.trim() || button.getAttribute('title');
      expect(name)
        .withContext(button.getAttribute('data-testid') ?? button.outerHTML)
        .toBeTruthy();
    }
  }

  function expectDialog(root: HTMLElement, testId: string, role = 'dialog'): HTMLElement {
    const dialog = root.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
    expect(dialog).not.toBeNull();
    expect(dialog.getAttribute('role')).toBe(role);
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    const title = document.getElementById(dialog.getAttribute('aria-labelledby'));
    expect(title).not.toBeNull();
    expect(dialog.contains(title)).toBeTrue();
    expect(title.textContent.trim()).not.toBe('');
    return dialog;
  }

  function expectBackdropIsNotAControl(root: HTMLElement, testId: string): void {
    const backdrop = root.querySelector(`[data-testid="${testId}"]`);
    expect(backdrop.tagName).toBe('DIV');
    expect(backdrop.getAttribute('aria-hidden')).toBe('true');
  }

  it('the repository create modal', async () => {
    const { el } = await renderComponent(RepositoryCreateModalComponent, [], { open: true });

    expectDialog(el, 'repo-create-modal');
    expectBackdropIsNotAControl(el, 'repo-create-backdrop');
    expectLabelled(el);
    const nameInput = el.querySelector<HTMLInputElement>('[data-testid="repo-create-name"]');
    expect(nameInput.labels[0].textContent).toContain('Name');
    expect(document.activeElement).toBe(nameInput);
    // The selector is named by its label and its value: "Type Docker".
    const selector = el.querySelector('[data-testid="selector-toggle"]');
    expect(nameOf(selector as HTMLElement)).toBe('Type Docker');
  });

  it('the deploy token create modal', async () => {
    const { el } = await renderComponent(DeployTokenCreateModalComponent, [], { open: true, repoName: 'repo' });

    expectDialog(el, 'token-create-modal');
    expectBackdropIsNotAControl(el, 'token-create-backdrop');
    expectLabelled(el);
    const group = el.querySelector<HTMLElement>('[data-testid="radio-group"]');
    expect(group.getAttribute('role')).toBe('radiogroup');
    expect(document.getElementById(group.getAttribute('aria-labelledby')).textContent).toContain('Access Type');
  });

  it('the user create modal', async () => {
    const { el } = await renderComponent(UserCreateModalComponent, [], { open: true });

    expectDialog(el, 'user-create-modal');
    expectBackdropIsNotAControl(el, 'user-create-backdrop');
    expectLabelled(el);
    const toggle = el.querySelector<HTMLElement>('[data-testid="user-create-role"] input');
    expect(nameOf(toggle)).toBe('Role User');
    const eye = el.querySelector('[data-testid="user-create-password-toggle"]');
    expect(eye.getAttribute('aria-label')).toBe('Show password');
    expect(eye.getAttribute('aria-pressed')).toBe('false');
    (eye as HTMLElement).click();
  });

  it('the user create modal keeps the password toggle in step with the field', async () => {
    const { fixture, el } = await renderComponent(UserCreateModalComponent, [], { open: true });
    const eye = el.querySelector<HTMLElement>('[data-testid="user-create-password-toggle"]');

    eye.click();
    fixture.detectChanges();

    expect(eye.getAttribute('aria-label')).toBe('Hide password');
    expect(eye.getAttribute('aria-pressed')).toBe('true');
  });

  it('the user edit modal', async () => {
    const { el } = await renderComponent(UserEditModalComponent, [], {
      open: true,
      user: { id: 'u1', username: 'bob', role: 'USER', createdAt: '2026-01-01T00:00:00Z', lastLoginAt: null },
    });

    expectDialog(el, 'user-edit-modal');
    expectBackdropIsNotAControl(el, 'user-edit-backdrop');
    expectLabelled(el);
    expect(nameOf(el.querySelector<HTMLElement>('[data-testid="user-edit-role"] input'))).toBe('Role User');
  });

  it('the user reset password modal', async () => {
    const { el } = await renderComponent(UserResetPasswordModalComponent, [], {
      open: true,
      username: 'bob',
      newPassword: 'Secret-1',
    });

    expectDialog(el, 'user-reset-password-modal');
    expectBackdropIsNotAControl(el, 'user-reset-password-backdrop');
    expectLabelled(el);
  });

  it('the deploy token info modal', async () => {
    const tokenInfo = { username: 'u', token: 't' } as TokenCreateInfo;
    const { el } = await renderComponent(DeployTokenInfoModalComponent, [], { open: true, tokenInfo });

    expectDialog(el, 'token-info-modal');
    expectBackdropIsNotAControl(el, 'token-info-backdrop');
    expectLabelled(el);
  });

  it('the danger modal is an alertdialog that keeps its callback API and focuses Cancel', async () => {
    const service = new DangerModalService();
    const confirmed: string[] = [];
    service.showWithMessage('Delete thing', 'Delete', 'It cannot be undone', () => confirmed.push('yes'));
    const { fixture, el } = await renderComponent(DangerModalComponent, [{ provide: DangerModalService, useValue: service }]);

    const dialog = expectDialog(el, 'danger-modal', 'alertdialog');
    expectBackdropIsNotAControl(el, 'danger-modal-backdrop');
    expectLabelled(el);
    expect(document.getElementById(dialog.getAttribute('aria-describedby')).textContent).toContain(
      'Do you want to delete thing?',
    );
    expect(document.activeElement).toBe(el.querySelector('[data-testid="danger-modal-cancel"]'));

    el.querySelector<HTMLElement>('[data-testid="danger-modal-confirm"]').click();
    fixture.detectChanges();
    expect(confirmed).toEqual(['yes']);
    expect(service.modal).toBeNull();
  });

  it('the login form', async () => {
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['logIn']);
    const { fixture, el } = await renderComponent(LoginComponent, [{ provide: AuthService, useValue: auth }]);

    expectLabelled(el);
    expect(nameOf(el.querySelector<HTMLElement>('[data-testid="login-username"]'))).toBe('Username');
    const password = el.querySelector<HTMLInputElement>('[data-testid="login-password"]');
    expect(password.labels[0].textContent.trim()).toBe('Password');
    const eye = el.querySelector<HTMLElement>('[data-testid="login-password-toggle"]');
    expect(eye.getAttribute('aria-label')).toBe('Show password');
    eye.click();
    fixture.detectChanges();
    expect(eye.getAttribute('aria-label')).toBe('Hide password');
    expect(eye.getAttribute('aria-pressed')).toBe('true');
  });

  it('two instances of a form never share an id', async () => {
    const first = await renderComponent(UserCreateModalComponent, [], { open: true });
    const second = TestBed.createComponent(UserCreateModalComponent);
    second.componentRef.setInput('open', true);
    second.detectChanges();

    const ids = [first.el, second.nativeElement as HTMLElement].flatMap((root) =>
      Array.from(root.querySelectorAll('input[id]')).map((input) => input.id),
    );
    expect(new Set(ids).size).toBe(ids.length);
  });
});
