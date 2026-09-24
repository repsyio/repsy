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


// Test-only helper for the specs that render a panel component's real template (the list templates and the help
// texts), where the rest of the specs drive the class alone. Nothing under src/app imports it, so it is never part of
// the application bundle.

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Provider, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

export interface Rendered<T> {
  fixture: ComponentFixture<T>;
  el: HTMLElement;
}

/** Creates the standalone `type` with the router and an HTTP client that never answers, `providers` and `inputs`. */
export async function renderComponent<T>(
  type: Type<T>,
  providers: Provider[],
  inputs: Record<string, unknown> = {},
): Promise<Rendered<T>> {
  TestBed.configureTestingModule({
    imports: [type],
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), ...providers],
  });
  const fixture = TestBed.createComponent(type);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

/** The `data-testid` of every element in `el` whose test id starts with `prefix`. */
export function testIds(el: HTMLElement, prefix: string): string[] {
  return Array.from(el.querySelectorAll(`[data-testid^="${prefix}"]`)).map((e) => e.getAttribute('data-testid') ?? '');
}
