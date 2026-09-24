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

import { FormControl } from '@angular/forms';

import { DESCRIPTION_MAX_LENGTH, DESCRIPTION_MAX_MESSAGE, descriptionValidators } from './description.validators';

describe('descriptionValidators (backend: at most 500 characters)', () => {
  const errorsOf = (value: string | null): string[] =>
    Object.keys(new FormControl(value, descriptionValidators()).errors ?? {});

  it('accepts an empty description, a null one and exactly 500 characters', () => {
    expect(errorsOf('')).toEqual([]);
    expect(errorsOf(null)).toEqual([]);
    expect(errorsOf('d'.repeat(DESCRIPTION_MAX_LENGTH))).toEqual([]);
  });

  it('rejects 501 characters with the maxlength error', () => {
    expect(errorsOf('d'.repeat(DESCRIPTION_MAX_LENGTH + 1))).toEqual(['maxlength']);
  });

  it('has the limit in its message', () => {
    expect(DESCRIPTION_MAX_LENGTH).toBe(500);
    expect(DESCRIPTION_MAX_MESSAGE).toBe('Should be maximum 500 characters');
  });
});
