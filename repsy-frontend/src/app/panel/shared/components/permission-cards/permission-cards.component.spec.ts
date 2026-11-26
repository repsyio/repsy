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
import { PermissionCardsComponent, PermissionToggle } from './permission-cards.component';

describe('PermissionCardsComponent', () => {
  function cards(): PermissionCardsComponent {
    const component = new PermissionCardsComponent();
    component.granted = new Set(['repo:write']);
    component.fixed = 'repo:read';
    return component;
  }

  it('treats the fixed permission as always granted', () => {
    const component = cards();

    expect(component.isFixed('repo:read')).toBeTrue();
    expect(component.isGranted('repo:read')).toBeTrue();
    expect(component.isGranted('repo:write')).toBeTrue();
    expect(component.isGranted('repo:manage')).toBeFalse();
  });

  it('reports a change of any card but the fixed one', () => {
    const component = cards();
    const seen: PermissionToggle[] = [];
    component.toggled.subscribe((toggle) => seen.push(toggle));

    component.onChange('repo:read', false);
    component.onChange('repo:manage', true);

    expect(seen).toEqual([{ scope: 'repo:manage', checked: true }]);
  });
});
