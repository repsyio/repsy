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

import { idFactory, uniqueId } from './unique-id';

describe('unique ids', () => {
  it('never repeats an id', () => {
    const ids = new Set(Array.from({ length: 50 }, () => uniqueId('x')));
    expect(ids.size).toBe(50);
  });

  it('gives every instance its own prefix and keeps it stable within the instance', () => {
    const first = idFactory('form');
    const second = idFactory('form');

    expect(first('name')).toBe(first('name'));
    expect(first('name')).not.toBe(first('description'));
    expect(first('name')).not.toBe(second('name'));
    expect(first('name')).toMatch(/^form-\d+-name$/);
  });
});
