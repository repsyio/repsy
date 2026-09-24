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
 * Fails the suite, naming the specs, when a spec leaves an element in `document.body` (RPS-1319). No spec
 * did when this was added; the guard keeps it that way.
 *
 * A fixture that is appended to the body by hand and never removed (or a modal or overlay that is
 * rendered outside a fixture) stays in the one shared Karma page and changes the layout, focus and hit
 * testing of every spec that runs after it, so the failure shows up in some other spec and only for some
 * random orders. The leftovers are removed as soon as a spec is done, so they cannot reach the next spec,
 * and the leaking specs are reported once at the end.
 *
 * The check runs in `specDone`, which is after Angular has destroyed the fixtures of the spec
 * (`destroyAfterEach`): only elements that outlive that are leaks.
 */
const leaks: string[] = [];
let bodyBefore = new Set<ChildNode>();

function describeNode(node: ChildNode): string {
  if (node instanceof Element) {
    const id = node.id ? `#${node.id}` : '';
    const testId = node.getAttribute('data-testid');
    return `<${node.tagName.toLowerCase()}${id}${testId ? ` data-testid="${testId}"` : ''}>`;
  }
  return node.nodeName;
}

jasmine.getEnv().addReporter({
  jasmineStarted: (info) => {
    // Karma prints the browser console: with the seed a failing order can be replayed (`JASMINE_SEED`, see karma.conf.cjs).
    console.info(`Jasmine random order: ${info.order?.random}, seed ${info.order?.seed}`);
  },
  specStarted: () => {
    bodyBefore = new Set(Array.from(document.body.childNodes));
  },
  specDone: (result) => {
    for (const node of Array.from(document.body.childNodes)) {
      if (!bodyBefore.has(node)) {
        leaks.push(`${result.fullName} left ${describeNode(node)} in document.body`);
        node.remove();
      }
    }
  },
});

afterAll(() => {
  if (leaks.length > 0) {
    fail(`${leaks.length} spec(s) leaked DOM into document.body:\n${leaks.join('\n')}`);
  }
});
