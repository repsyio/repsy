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

import { HttpErrorResponse } from '@angular/common/http';

import { VERSION_LOAD_FAILED, versionLoadError } from './version-load-error.util';

describe('versionLoadError', () => {
  it('names the version for a 404', () => {
    expect(versionLoadError(new HttpErrorResponse({ status: 404 }), '9.9.9')).toBe("Version '9.9.9' not found");
  });

  it('keeps build metadata and other special characters of the version as they are', () => {
    expect(versionLoadError(new HttpErrorResponse({ status: 404 }), '1.0.0+build.5')).toBe(
      "Version '1.0.0+build.5' not found",
    );
  });

  it('says the load failed for any other status', () => {
    expect(versionLoadError(new HttpErrorResponse({ status: 500 }), '1.0.0')).toBe(VERSION_LOAD_FAILED);
    expect(versionLoadError(new HttpErrorResponse({ status: 0 }), '1.0.0')).toBe(VERSION_LOAD_FAILED);
  });

  it('says the load failed for an error that is not an HTTP error', () => {
    expect(versionLoadError(new Error('boom'), '1.0.0')).toBe(VERSION_LOAD_FAILED);
    expect(versionLoadError(undefined, '1.0.0')).toBe(VERSION_LOAD_FAILED);
  });
});
