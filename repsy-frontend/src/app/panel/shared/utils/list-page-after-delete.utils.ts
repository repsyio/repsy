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

/**
 * What a server-paged list does after one of its rows was deleted (RPS-1340). The rule reads the page the user
 * is on and the search, never the page alone as "the list": a page holding one row is the whole list only when
 * it is the first page and nothing filters it.
 */

/** The deleted row was the only one of an unfiltered list: the parent (package, scope) is gone with it. */
export function emptiesList(rowsOnPage: number, pageNum: number, searchText: string): boolean {
  return rowsOnPage <= 1 && pageNum === 0 && !searchText;
}

/** The page to reload: one back when the deleted row was the last one of a page that is not the first. */
export function pageAfterDelete(rowsOnPage: number, pageNum: number): number {
  return rowsOnPage <= 1 && pageNum > 0 ? pageNum - 1 : pageNum;
}
