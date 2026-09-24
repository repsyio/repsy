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

import { FormBuilder } from '@angular/forms';
import { of, Subject, throwError } from 'rxjs';

import { ProtocolDeployTokenControllerService } from '../../../../../../generated/api';
import { TokenCreateInfo } from '../../../../pages/repository/repo-settings/deploy-token/dto/token-create-info';
import { ToastService } from '../../toast/toast.service';
import { DeployTokenCreateModalComponent } from './deploy-token-create-modal.component';

const REPO = 'acme-repo';
/** The clock is frozen at noon UTC so the "now" time of day that the component appends to a date is known. */
const NOW = new Date('2026-03-10T12:00:00.000Z');
const TOMORROW = '2026-03-11';
const ONE_YEAR_LATER = '2027-03-10';
const TOKEN: TokenCreateInfo = { id: 'token-1', username: 'bot', token: 'secret' };

describe('DeployTokenCreateModalComponent', () => {
  let component: DeployTokenCreateModalComponent;
  let api: jasmine.SpyObj<ProtocolDeployTokenControllerService>;
  let toastService: jasmine.SpyObj<ToastService>;
  let openChange: boolean[];
  let created: TokenCreateInfo[];

  beforeEach(() => {
    jasmine.clock().install();
    jasmine.clock().mockDate(NOW);

    api = jasmine.createSpyObj<ProtocolDeployTokenControllerService>('ProtocolDeployTokenControllerService', [
      'createDeployToken',
    ]);
    toastService = jasmine.createSpyObj<ToastService>('ToastService', ['show']);
    api.createDeployToken.and.returnValue(of({ data: TOKEN }) as never);

    component = new DeployTokenCreateModalComponent(api, new FormBuilder(), toastService);
    component.repoName = REPO;
    openChange = [];
    created = [];
    component.openChange.subscribe((open) => openChange.push(open));
    component.created.subscribe((info) => created.push(info));
    component.ngOnInit();
  });

  afterEach(() => jasmine.clock().uninstall());

  function fill(values: Record<string, unknown>): void {
    component.form.patchValue({ name: 'ci-token', ...values });
  }

  function sentPayload(): unknown {
    return api.createDeployToken.calls.mostRecent().args[1];
  }

  describe('initial state', () => {
    it('allows expiration dates from tomorrow to 365 days from today, in UTC', () => {
      expect(component.minDate).toBe(TOMORROW);
      expect(component.maxDate).toBe(ONE_YEAR_LATER);
    });

    it('defaults to a read/write token that expires at the latest allowed date', () => {
      expect(component.form.getRawValue()).toEqual({
        name: '',
        username: '',
        description: '',
        readOnly: false,
        expirationDate: ONE_YEAR_LATER,
      });
    });

    it('offers read/write and read only access', () => {
      expect(component.accessTypeOptions).toEqual([
        { label: 'Read/Write', value: false },
        { label: 'Read Only', value: true },
      ]);
    });
  });

  describe('validation', () => {
    it('requires a name of at most 80 characters', () => {
      expect(component.form.get('name').hasError('required')).toBeTrue();
      fill({ name: 'a'.repeat(80) });
      expect(component.form.valid).toBeTrue();
      fill({ name: 'a'.repeat(81) });
      expect(component.form.get('name').hasError('maxlength')).toBeTrue();
    });

    it('treats the username as optional but restricts it to 3 to 25 of a-z, 0-9, hyphen and underscore', () => {
      fill({ username: '' });
      expect(component.form.valid).toBeTrue();
      fill({ username: 'ci_bot-1' });
      expect(component.form.valid).toBeTrue();
      fill({ username: 'ab' });
      expect(component.form.get('username').hasError('minlength')).toBeTrue();
      fill({ username: 'a'.repeat(26) });
      expect(component.form.get('username').hasError('maxlength')).toBeTrue();
      fill({ username: 'CI-Bot' });
      expect(component.form.get('username').hasError('pattern')).toBeTrue();
      fill({ username: 'ci bot' });
      expect(component.form.get('username').hasError('pattern')).toBeTrue();
    });

    it('limits the description to 500 characters', () => {
      fill({ description: 'd'.repeat(500) });
      expect(component.form.valid).toBeTrue();
      fill({ description: 'd'.repeat(501) });
      expect(component.form.get('description').hasError('maxlength')).toBeTrue();
    });
  });

  describe('createToken', () => {
    it('sends the form as the API payload for the repository', () => {
      fill({ username: 'ci-bot', description: 'Used by CI', readOnly: true, expirationDate: TOMORROW });

      component.createToken();

      expect(api.createDeployToken).toHaveBeenCalledTimes(1);
      expect(api.createDeployToken.calls.mostRecent().args[0]).toBe(REPO);
      expect(sentPayload()).toEqual({
        name: 'ci-token',
        username: 'ci-bot',
        description: 'Used by CI',
        readOnly: true,
        expirationDate: '2026-03-11T12:00:00.000Z',
      });
    });

    it('sends the default form as a read/write token expiring one year from now', () => {
      fill({});

      component.createToken();

      expect(sentPayload()).toEqual({
        name: 'ci-token',
        username: undefined,
        description: undefined,
        readOnly: false,
        expirationDate: '2027-03-10T12:00:00.000Z',
      });
    });

    it('trims the username and description and drops blank ones', () => {
      fill({ username: '  ci-bot  ', description: ' note ' });
      component.createToken();
      expect(sentPayload()).toEqual(jasmine.objectContaining({ username: 'ci-bot', description: 'note' }));

      fill({ username: '   ', description: '   ' });
      component.createToken();
      expect(sentPayload()).toEqual(jasmine.objectContaining({ username: undefined, description: undefined }));
    });

    it('omits the expiration when the date was cleared', () => {
      fill({ expirationDate: null });

      component.createToken();

      expect(Object.keys(sentPayload() as object)).not.toContain('expirationDate');
      expect(api.createDeployToken).toHaveBeenCalledTimes(1);
    });

    it('locks the form while the request runs', () => {
      const response = new Subject<unknown>();
      api.createDeployToken.and.returnValue(response as never);
      fill({});

      component.createToken();

      expect(component.loading).toBeTrue();
      expect(component.form.disabled).toBeTrue();

      response.next({ data: TOKEN });
      response.complete();

      expect(component.loading).toBeFalse();
      expect(component.form.enabled).toBeTrue();
    });

    it('closes the modal, hands the token to the parent and toasts on success', () => {
      fill({ username: 'ci-bot', readOnly: true, expirationDate: TOMORROW });

      component.createToken();

      expect(created).toEqual([TOKEN]);
      expect(openChange).toEqual([false]);
      expect(toastService.show).toHaveBeenCalledOnceWith('Deploy token created successfully.', 'success');
      expect(component.form.getRawValue()).toEqual({
        name: '',
        username: '',
        description: '',
        readOnly: false,
        expirationDate: ONE_YEAR_LATER,
      });
    });

    it('stays open and unlocks the form when the request fails, leaving the toast to the interceptor', () => {
      api.createDeployToken.and.returnValue(throwError(() => new Error('boom')));
      fill({});

      component.createToken();

      expect(created).toEqual([]);
      expect(openChange).toEqual([]);
      expect(toastService.show).not.toHaveBeenCalled();
      expect(component.form.enabled).toBeTrue();
      expect(component.loading).toBeFalse();
      expect(component.form.get('name').value).toBe('ci-token');
    });

    describe('expiration date checks', () => {
      const EXPIRATION_MESSAGE = 'Expiration date must be between tomorrow and one year from today.';

      function expectRejected(): void {
        expect(api.createDeployToken).not.toHaveBeenCalled();
        expect(toastService.show).toHaveBeenCalledOnceWith(EXPIRATION_MESSAGE, 'error');
        expect(component.form.enabled).toBeTrue();
        expect(component.loading).toBeFalse();
        expect(openChange).toEqual([]);
      }

      it('rejects today', () => {
        fill({ expirationDate: '2026-03-10' });
        component.createToken();
        expectRejected();
      });

      it('rejects a date in the past', () => {
        fill({ expirationDate: '2025-01-01' });
        component.createToken();
        expectRejected();
      });

      it('rejects a date later than one year from today', () => {
        fill({ expirationDate: '2027-03-11' });
        component.createToken();
        expectRejected();
      });

      it('rejects a value that is not a date', () => {
        fill({ expirationDate: '2027-13-45' });
        component.createToken();
        expectRejected();
      });

      it('accepts tomorrow and exactly one year from today', () => {
        fill({ expirationDate: TOMORROW });
        component.createToken();
        fill({ expirationDate: ONE_YEAR_LATER });
        component.createToken();

        expect(api.createDeployToken).toHaveBeenCalledTimes(2);
        expect(toastService.show).not.toHaveBeenCalledWith(EXPIRATION_MESSAGE, 'error');
      });

      it('never leaves the modal locked: Cancel stays enabled and the user can retry after a rejection', () => {
        fill({ expirationDate: '2027-03-11' });

        component.createToken();
        expectRejected();

        // The user is not trapped: they can either close the modal...
        component.closeModal();
        expect(openChange).toEqual([false]);

        // ...or fix the date and retry without a page reload.
        fill({ expirationDate: TOMORROW });
        component.createToken();
        expect(api.createDeployToken).toHaveBeenCalledTimes(1);
      });
    });

    describe('expirationDate control validator', () => {
      it('flags a date before tomorrow as dateOutOfRange', () => {
        fill({ expirationDate: '2026-03-10' });
        expect(component.form.get('expirationDate').hasError('dateOutOfRange')).toBeTrue();
        expect(component.form.invalid).toBeTrue();
      });

      it('flags a date more than one year out as dateOutOfRange', () => {
        fill({ expirationDate: '2027-03-11' });
        expect(component.form.get('expirationDate').hasError('dateOutOfRange')).toBeTrue();
      });

      it('flags a malformed date as dateInvalid', () => {
        fill({ expirationDate: '2027-13-45' });
        expect(component.form.get('expirationDate').hasError('dateInvalid')).toBeTrue();
      });

      it('accepts dates from tomorrow through one year from today', () => {
        fill({ expirationDate: TOMORROW });
        expect(component.form.get('expirationDate').valid).toBeTrue();

        fill({ expirationDate: ONE_YEAR_LATER });
        expect(component.form.get('expirationDate').valid).toBeTrue();
      });

      it('treats a cleared expiration date as valid, since it is optional', () => {
        fill({ expirationDate: null });
        expect(component.form.get('expirationDate').valid).toBeTrue();
      });
    });
  });

  describe('closeModal', () => {
    it('resets the form to its defaults and tells the parent to close', () => {
      fill({ username: 'ci-bot', description: 'x', readOnly: true, expirationDate: TOMORROW });

      component.closeModal();

      expect(component.form.getRawValue()).toEqual({
        name: '',
        username: '',
        description: '',
        readOnly: false,
        expirationDate: ONE_YEAR_LATER,
      });
      expect(openChange).toEqual([false]);
    });
  });

  describe('getNowTime', () => {
    it('returns the time of day parts of a moment', () => {
      expect(
        component.getNowTime(jasmine.createSpyObj('Moment', { hour: 1, minute: 2, second: 3, millisecond: 4 })),
      ).toEqual({
        hour: 1,
        minute: 2,
        second: 3,
        millisecond: 4,
      });
    });
  });
});
