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

import { EllipsisPipe } from './ellipsis.pipe';

describe('EllipsisPipe', () => {
  const pipe = new EllipsisPipe();

  it('leaves a value within the limit untouched', () => {
    expect(pipe.transform('short')).toBe('short');
    expect(pipe.transform('0123456789')).toBe('0123456789');
  });

  it('truncates a value over the default limit of 10 and appends an ellipsis', () => {
    expect(pipe.transform('0123456789A')).toBe('0123456789...');
  });

  it('honours a custom limit', () => {
    expect(pipe.transform('abcdef', 3)).toBe('abc...');
    expect(pipe.transform('abc', 3)).toBe('abc');
  });

  it('returns an empty string for an empty or missing value', () => {
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform(null as unknown as string)).toBe('');
    expect(pipe.transform(undefined as unknown as string)).toBe('');
  });
});
