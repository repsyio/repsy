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
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { lastVersionOfGroupWarning, showVersionDeleteDialog } from './version-delete-warning.util';

describe('version-delete-warning.util', () => {
  it('says the artifact and the group are removed with the last version of the only artifact', () => {
    const warning = lastVersionOfGroupWarning('io.acme', 'widget');

    expect(warning).toContain('only version of widget');
    expect(warning).toContain('only artifact of the group io.acme');
    expect(warning).toContain('the artifact and the group are removed too');
  });

  describe('showVersionDeleteDialog', () => {
    let modal: DangerModalService;

    beforeEach(() => (modal = new DangerModalService()));

    it('shows the plain confirmation without a warning', () => {
      const call = jasmine.createSpy('call');

      showVersionDeleteDialog(modal, null, call);

      expect(modal.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: null });
      modal.call();
      expect(call).toHaveBeenCalledTimes(1);
    });

    it('shows the warning as the message of the confirmation', () => {
      const call = jasmine.createSpy('call');

      showVersionDeleteDialog(modal, 'the group goes too', call);

      expect(modal.modal).toEqual({ title: 'Delete Version', action: 'Delete', message: 'the group goes too' });
      modal.call();
      expect(call).toHaveBeenCalledTimes(1);
    });
  });
});
