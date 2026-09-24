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

let counter = 0;

/**
 * A document-unique element id: `<prefix>-<n>`. Ids that label form controls or name dialogs must
 * not repeat in the document (two forms on one page, a modal opened over a form), so a component
 * takes one prefix per instance instead of hard-coding `id="name"`.
 */
export function uniqueId(prefix: string): string {
  counter += 1;
  return `${prefix}-${counter}`;
}

/**
 * The id factory of one component instance: `const id = idFactory('token-create')`, then
 * `[id]="id('name')"` on the control and `[attr.for]="id('name')"` on its label. Every call site of
 * one instance shares the instance prefix, and two instances never share it.
 */
export function idFactory(prefix: string): (suffix: string) => string {
  const instance = uniqueId(prefix);
  return (suffix: string) => `${instance}-${suffix}`;
}
