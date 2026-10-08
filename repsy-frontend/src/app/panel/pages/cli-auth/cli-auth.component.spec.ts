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
import { ActivatedRoute, convertToParamMap } from '@angular/router';

import { CliAuthComponent } from './cli-auth.component';

function pageFor(query: Record<string, string>): CliAuthComponent {
  const route = { snapshot: { queryParamMap: convertToParamMap(query) } } as unknown as ActivatedRoute;
  const component = new CliAuthComponent(route);
  component.ngOnInit();
  return component;
}

describe('CliAuthComponent', () => {
  it('pre-fills the name and the known scopes of the link', () => {
    const page = pageFor({ name: 'laptop', scopes: 'repo:read,repo:write', state: 'abc' });

    expect(page.name).toBe('laptop');
    expect(page.scopes).toEqual(['repo:read', 'repo:write']);
  });

  it('drops scopes it does not know, so the link never grants more than the page shows', () => {
    expect(pageFor({ scopes: 'repo:read,profile:read,admin' }).scopes).toEqual(['repo:read']);
  });

  it('falls back to a default name and no scope', () => {
    const page = pageFor({});

    expect(page.name).toBe('Repsy CLI');
    expect(page.scopes).toEqual([]);
  });

  it('shows the secret once the token is created', () => {
    const page = pageFor({});
    expect(page.created).toBeNull();

    page.onCreated({ token: 'rut-x' } as never);

    expect(page.created?.token).toBe('rut-x');
  });
});
