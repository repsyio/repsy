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

// RPS-1467: remixicon's @font-face lists an .eot, a .woff2, a .woff, a .ttf and a .svg font, and the
// Angular build copies every url() it can resolve, so the panel image shipped about 4.5 MB of
// legacy formats (.eot for IE, .svg fonts for old iOS, .ttf) that no supported browser requests.
// Angular's "loader" option does not apply to url() in a global stylesheet, and vendoring a copy of
// remixicon.css would freeze its ~3000 icon classes, so this step runs after "ng build": it keeps
// the woff2 and woff sources in the bundled remixicon @font-face rule and deletes the other font
// files it referenced. It fails the build when the rule is not found or nothing is dropped, so a
// remixicon upgrade that changes the CSS cannot silently bring the files back.

import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const KEPT_FORMATS = new Set(['woff2', 'woff']);
const FONT_FACE = /@font-face\{font-family:remixicon;[^}]*\}/;
const SRC_ENTRY = /url\(\s*(["']?)([^)"']+?)\1\s*\)(?:\s*format\(\s*["']?([\w-]+)["']?\s*\))?/g;

export function pruneRemixiconCss(css) {
  const rule = FONT_FACE.exec(css)?.[0];
  if (rule === undefined) {
    throw new Error('no @font-face{font-family:remixicon;...} rule in the bundled stylesheet');
  }
  const kept = [];
  const dropped = new Set();
  // The first "src:" line is the bare .eot url with no format(), which IE9 needs; a later src
  // declaration overrides it, so only the last one is read.
  const srcs = rule.match(/src:[^;}]*/g) ?? [];
  const last = srcs[srcs.length - 1] ?? '';
  for (const match of last.matchAll(SRC_ENTRY)) {
    const [entry, , url, format] = match;
    if (format !== undefined && KEPT_FORMATS.has(format)) {
      kept.push(entry);
    } else {
      dropped.add(basename(url.split(/[?#]/)[0]));
    }
  }
  for (const first of srcs.slice(0, -1)) {
    for (const match of first.matchAll(SRC_ENTRY)) {
      dropped.add(basename(match[2].split(/[?#]/)[0]));
    }
  }
  if (kept.length === 0 || dropped.size === 0) {
    throw new Error(`remixicon @font-face rule not as expected (kept ${kept.length}, dropped ${dropped.size})`);
  }
  const withoutSrc = rule.replace(/src:[^;}]*;?/g, '').slice(0, -1);
  const separator = withoutSrc.endsWith(';') ? '' : ';';
  const pruned = `${withoutSrc}${separator}src:${kept.join(',')}}`;
  return { css: css.replace(rule, pruned), dropped: [...dropped] };
}

function main() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'panel-frontend', 'browser');
  const stylesheets = readdirSync(root).filter((f) => /^styles-.*\.css$/.test(f));
  if (stylesheets.length !== 1) {
    throw new Error(`expected one styles-*.css in ${root}, found ${stylesheets.length}`);
  }
  const path = join(root, stylesheets[0]);
  const { css, dropped } = pruneRemixiconCss(readFileSync(path, 'utf8'));
  writeFileSync(path, css);
  for (const name of dropped) {
    rmSync(join(root, 'assets', name));
  }
  console.log(`remixicon: kept woff2 and woff, removed ${dropped.join(', ')}`);
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
