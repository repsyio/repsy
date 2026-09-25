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

import { emptiesList, pageAfterDelete } from './list-page-after-delete.util';

describe('emptiesList', () => {
  it('is true for the only row of the first, unfiltered page', () => {
    expect(emptiesList(1, 0, '')).toBeTrue();
  });

  it('is false for the only row of a later page: the earlier pages still hold rows', () => {
    expect(emptiesList(1, 1, '')).toBeFalse();
  });

  it('is false while a search filters the list: the row is only the last match', () => {
    expect(emptiesList(1, 0, 'v1')).toBeFalse();
  });

  it('is false while other rows remain on the page', () => {
    expect(emptiesList(2, 0, '')).toBeFalse();
  });
});

describe('pageAfterDelete', () => {
  it('goes back a page when the last row of a later page was deleted', () => {
    expect(pageAfterDelete(1, 2)).toBe(1);
  });

  it('stays on the page while other rows remain', () => {
    expect(pageAfterDelete(2, 2)).toBe(2);
  });

  it('never goes below the first page', () => {
    expect(pageAfterDelete(1, 0)).toBe(0);
  });
});
