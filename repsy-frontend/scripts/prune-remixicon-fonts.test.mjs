/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Run with `pnpm test:scripts` (node --test); needs no build.
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { pruneRemixiconCss } from './prune-remixicon-fonts.mjs';

// The rule as "ng build --configuration production" writes it (minified, bundled urls).
const RULE =
  '@font-face{font-family:remixicon;' +
  'src:url("./assets/remixicon-AAA.eot?t=1");' +
  'src:url("./assets/remixicon-AAA.eot?t=1#iefix") format("embedded-opentype"),' +
  'url("./assets/remixicon-BBB.woff2?t=1") format("woff2"),' +
  'url("./assets/remixicon-CCC.woff?t=1") format("woff"),' +
  'url("./assets/remixicon-DDD.ttf?t=1") format("truetype"),' +
  'url("./assets/remixicon-EEE.svg?t=1#remixicon") format("svg");font-display:swap}';
const CSS = `body{margin:0}@font-face{font-family:Comfortaa;src:url(/assets/c.ttf) format("truetype")}${RULE}.ri-a:before{content:"\\ea01"}`;

test('keeps woff2 and woff, drops the other sources and lists their files', () => {
  const { css, dropped } = pruneRemixiconCss(CSS);

  assert.deepEqual(dropped.sort(), ['remixicon-AAA.eot', 'remixicon-DDD.ttf', 'remixicon-EEE.svg']);
  assert.ok(
    css.includes(
      '@font-face{font-family:remixicon;font-display:swap;' +
        'src:url("./assets/remixicon-BBB.woff2?t=1") format("woff2"),' +
        'url("./assets/remixicon-CCC.woff?t=1") format("woff")}',
    ),
    css,
  );
});

test('leaves every other rule alone', () => {
  const { css } = pruneRemixiconCss(CSS);

  assert.ok(
    css.startsWith('body{margin:0}@font-face{font-family:Comfortaa;src:url(/assets/c.ttf) format("truetype")}'),
  );
  assert.ok(css.endsWith('.ri-a:before{content:"\\ea01"}'));
});

test('fails when the remixicon rule is missing', () => {
  assert.throws(() => pruneRemixiconCss('body{margin:0}'), /no @font-face/);
});

test('fails when there is nothing to drop or nothing to keep', () => {
  const woffOnly =
    '@font-face{font-family:remixicon;src:url("./a.woff2") format("woff2"),url("./a.woff") format("woff");font-display:swap}';
  assert.throws(() => pruneRemixiconCss(woffOnly), /not as expected/);
  const eotOnly = '@font-face{font-family:remixicon;src:url("./a.eot")}';
  assert.throws(() => pruneRemixiconCss(eotOnly), /not as expected/);
});
