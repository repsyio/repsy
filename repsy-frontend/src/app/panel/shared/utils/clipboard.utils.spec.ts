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

import { copyToClipboard } from './clipboard.utils';

// RPS-1623 (ported from Cloud, RPS-1624): navigator.clipboard is missing over plain HTTP and can reject, so copying falls back to execCommand.
describe('copyToClipboard', () => {
  function stubClipboard(value: Partial<Clipboard> | undefined): void {
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue(value as Clipboard);
  }

  it('uses the async clipboard API when it is available', async () => {
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    stubClipboard({ writeText });
    const exec = spyOn(document, 'execCommand');

    expect(await copyToClipboard('mvn install')).toBeTrue();

    expect(writeText).toHaveBeenCalledOnceWith('mvn install');
    expect(exec).not.toHaveBeenCalled();
  });

  it('falls back to a hidden textarea and execCommand when navigator.clipboard is undefined', async () => {
    stubClipboard(undefined);
    let copied: string | undefined;
    const exec = spyOn(document, 'execCommand').and.callFake(() => {
      copied = (document.activeElement as HTMLTextAreaElement).value;
      return true;
    });

    expect(await copyToClipboard('npm install repsy')).toBeTrue();

    expect(exec).toHaveBeenCalledOnceWith('copy');
    expect(copied).toBe('npm install repsy');
    expect(document.querySelector('textarea')).toBeNull();
  });

  it('falls back when writeText rejects', async () => {
    stubClipboard({ writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')) });
    const exec = spyOn(document, 'execCommand').and.returnValue(true);

    expect(await copyToClipboard('x')).toBeTrue();

    expect(exec).toHaveBeenCalledOnceWith('copy');
  });

  it('returns false, without throwing, when execCommand fails or throws', async () => {
    stubClipboard(undefined);
    const exec = spyOn(document, 'execCommand').and.returnValue(false);
    expect(await copyToClipboard('x')).toBeFalse();

    exec.and.throwError('unsupported');
    expect(await copyToClipboard('x')).toBeFalse();
    expect(document.querySelector('textarea')).toBeNull();
  });
});
