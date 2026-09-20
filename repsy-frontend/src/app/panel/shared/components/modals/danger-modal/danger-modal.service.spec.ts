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

import { DangerModalService } from './danger-modal.service';

describe('DangerModalService', () => {
  let service: DangerModalService;

  beforeEach(() => {
    service = new DangerModalService();
  });

  it('shows a modal without a message and keeps the confirm callback', () => {
    const confirm = jasmine.createSpy('confirm');

    service.show('Delete repository', 'Delete', confirm);

    expect(service.modal).toEqual({ title: 'Delete repository', action: 'Delete', message: null });
    service.call();
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it('shows a modal with a message', () => {
    service.showWithMessage('Delete account', 'Delete', 'This cannot be undone.', () => undefined);

    expect(service.modal).toEqual({ title: 'Delete account', action: 'Delete', message: 'This cannot be undone.' });
  });

  it('replaces a modal that is already open, including its message and callback', () => {
    const first = jasmine.createSpy('first');
    const second = jasmine.createSpy('second');
    service.showWithMessage('First', 'Go', 'with message', first);

    service.show('Second', 'Go', second);

    expect(service.modal).toEqual({ title: 'Second', action: 'Go', message: null });
    service.call();
    expect(second).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
  });

  it('closes the modal', () => {
    service.show('Delete', 'Delete', () => undefined);

    service.close();

    expect(service.modal).toBeNull();
  });
});
