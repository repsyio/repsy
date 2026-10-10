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

import { externalHttpUrl } from './external-url.utils';

// RPS-1623: only http(s) becomes a link; Angular's sanitiser lets data: and vbscript: through.
describe('externalHttpUrl', () => {
  it('keeps http and https URLs, trimmed', () => {
    expect(externalHttpUrl('https://example.com/home')).toBe('https://example.com/home');
    expect(externalHttpUrl('  HTTP://example.com  ')).toBe('HTTP://example.com');
  });

  it('rejects other schemes, however they are spelled', () => {
    for (const raw of [
      'javascript:alert(1)',
      ' JaVaScRiPt:alert(1)',
      'java\tscript:alert(1)',
      'data:text/html,<script>alert(1)</script>',
      'vbscript:msgbox(1)',
      'mailto:a@b.c',
      'ftp://example.com',
      'file:///etc/passwd',
    ]) {
      expect(externalHttpUrl(raw)).withContext(raw).toBeNull();
    }
  });

  it('rejects relative values and empty ones', () => {
    for (const raw of ['/admin', 'example.com', '//example.com', '', '   ', null, undefined]) {
      expect(externalHttpUrl(raw)).withContext(String(raw)).toBeNull();
    }
  });
});
