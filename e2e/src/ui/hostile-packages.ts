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
 * Hostile package content for the UI suite (RPS-1623): a README, a description and homepage/project/
 * repository fields that carry everything a malicious publisher could put into the panel, and seeders
 * that publish them over raw HTTP (no client binary). The payloads are plain data and the seeders build
 * on the ones of `src/seed/packages`, so Repsy Cloud's suite (RPS-1624) reuses this file as it is.
 *
 * Every script payload sets `window.__pwned` to its own id (`CANARY`), so a spec that finds the canary
 * set knows which payload got through. Nothing here is executed by the harness itself.
 *
 * The specs that use it: `tests/ui/packages/untrusted-content.spec.ts`. The assertions live in
 * `hostile-checks.ts`.
 */
import { adminCredential, authHeader } from '../clients/raw-http.js';
import {
  buildPublishDocument,
  buildTarball,
  rawPublish as npmPublish,
} from '../clients/npm-raw.js';
import { buildWheel, buildUploadForm, uploadUrl } from '../clients/pypi-raw.js';
import { buildNupkg, rawPublish as nugetPublish } from '../clients/nuget-raw.js';
import { buildGem, rawPublish as rubyPublish } from '../clients/ruby-raw.js';
import {
  artifactDir,
  artifactMetadataXml,
  buildJar,
  minimalPom,
  rawPut,
  splitPackageName,
  versionDir,
} from '../clients/maven-raw.js';
import { publishCrate } from '../seed/packages/cargo.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from '../seed/packages/shared.js';
import type { SeededPackage } from '../seed/packages.js';

/** The `window` property every script payload sets (to the id of the payload). */
export const CANARY = '__pwned';

/** Host of every external resource in the payloads: unresolvable, blocked by the harness, never contacted by a safe panel. */
export const TRACKER_HOST = 'tracker.e2e.invalid';

const setCanary = (id: string): string => `window.${CANARY}='${id}'`;
const base64 = (text: string): string => Buffer.from(text, 'utf8').toString('base64');

/** Links with a scheme that must never stay a live `href`. */
export const HOSTILE_URLS = {
  javascript: `javascript:${setCanary('javascript-url')}`,
  data: `data:text/html;base64,${base64(`<script>${setCanary('data-url')}</script>`)}`,
  vbscript: 'vbscript:msgbox(1)',
} as const;

export type HostileScheme = keyof typeof HOSTILE_URLS;

export interface HostilePayload {
  /** Also the value the payload writes to `window.__pwned`, when it is a script. */
  id: string;
  /** What it is, for the failure message. */
  what: string;
  /** Markdown (raw HTML included) as a publisher writes it. */
  markdown: string;
}

/**
 * The README payloads. The `link:` ones are anchors, `image:` ones are pictures, the rest must simply not
 * run or load. `external-link` and `mailto-link` are the harmless controls: they must survive (safely).
 */
export const HOSTILE_PAYLOADS: readonly HostilePayload[] = [
  {
    id: 'script-tag',
    what: 'a <script> element',
    markdown: `<script>${setCanary('script-tag')}</script>`,
  },
  {
    id: 'img-onerror',
    what: 'an <img> with an onerror handler',
    markdown: `<img src=x onerror="${setCanary('img-onerror')}">`,
  },
  {
    id: 'svg-onload',
    what: 'an <svg> with an onload handler',
    markdown: `<svg onload="${setCanary('svg-onload')}"><circle r="5" /></svg>`,
  },
  {
    id: 'inline-handler',
    what: 'an anchor with an onclick handler',
    markdown: `<a href="https://example.com/handler" onclick="${setCanary('inline-handler')}">handler link</a>`,
  },
  {
    id: 'javascript-link',
    what: 'a javascript: link',
    markdown: `<a href="${HOSTILE_URLS.javascript}">javascript link</a>`,
  },
  {
    id: 'javascript-link-obfuscated',
    what: 'a javascript: link spelled with an entity, a tab and mixed case',
    markdown: `<a href="  JaVa&#x09;Script&#58;${setCanary('javascript-link-obfuscated')}">obfuscated link</a>`,
  },
  {
    id: 'data-link',
    what: 'a data:text/html link (Angular rewrites javascript: only)',
    markdown: `<a href="${HOSTILE_URLS.data}">data link</a>`,
  },
  {
    id: 'vbscript-link',
    what: 'a vbscript: link',
    markdown: `<a href="${HOSTILE_URLS.vbscript}">vbscript link</a>`,
  },
  {
    id: 'tracking-image',
    what: 'an external image (a tracking pixel)',
    markdown: `![tracking pixel](https://${TRACKER_HOST}/pixel.gif?reader=secret)`,
  },
  {
    id: 'data-html-image',
    what: 'an image whose source is a data:text/html document',
    markdown: `![data document](${HOSTILE_URLS.data})`,
  },
  {
    id: 'relative-image',
    what: 'a relative image (it would resolve against the panel)',
    markdown: '![relative picture](/assets/icons/email.svg)',
  },
  {
    id: 'relative-link',
    what: 'a relative link (it would open a panel route)',
    markdown: '[relative link](/users)',
  },
  {
    id: 'iframe',
    what: 'an <iframe> to an external page',
    markdown: `<iframe src="https://${TRACKER_HOST}/frame" srcdoc="<script>${setCanary('iframe')}</script>"></iframe>`,
  },
  {
    id: 'style-background',
    what: 'a style attribute that loads an external background',
    markdown: `<div style="background:url(https://${TRACKER_HOST}/background.png)">styled block</div>`,
  },
  {
    id: 'html-comment',
    what: 'a script hidden in an HTML comment',
    markdown: `<!-- <script>${setCanary('html-comment')}</script> -->`,
  },
  {
    id: 'external-link',
    what: 'a plain external link (a control: it stays, and opens safely)',
    markdown: '[external link](https://example.com/docs)',
  },
  {
    id: 'mailto-link',
    what: 'a mailto: link (a control: it stays)',
    markdown: '[mail the author](mailto:author@example.com)',
  },
];

/** A README that carries every payload, with a heading and a code block so that it renders like a real one. */
export const HOSTILE_README = [
  '# Hostile README',
  ...HOSTILE_PAYLOADS.map((payload) => payload.markdown),
  '```bash\ninstall --hostile\n```',
].join('\n\n');

/** Plain-text metadata (a description, a title): shown as text, so this must appear literally and run nothing. */
export const HOSTILE_TEXT = `<img src=x onerror="${setCanary('metadata-text')}"><script>${setCanary('metadata-script')}</script>`;

const xmlEscape = (text: string): string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

interface SeedOptions {
  /** Distinguishes several packages of one repo; default 1. */
  index?: number;
  version?: string;
}

const nameOf = (
  protocol: Parameters<typeof defaultPackageName>[0],
  runId: string,
  opts: SeedOptions,
): string => defaultPackageName(protocol, runId, opts.index);

/** npm: the README, the description, the homepage and the repository URL of the manifest. */
export async function seedHostileNpm(
  repoName: string,
  runId: string,
  opts: SeedOptions = {},
): Promise<SeededPackage> {
  const name = nameOf('npm', runId, opts);
  const version = opts.version ?? DEFAULT_VERSION;
  const document = buildPublishDocument({
    repoName,
    packageName: name,
    version,
    tarballBytes: buildTarball({ packageName: name, version }),
    description: HOSTILE_TEXT,
    extra: {
      readme: HOSTILE_README,
      homepage: HOSTILE_URLS.javascript,
      repository: { type: 'git', url: HOSTILE_URLS.data },
      bugs: { url: HOSTILE_URLS.vbscript },
    },
  });
  expectPublished(await npmPublish(repoName, adminCredential(), name, document), `npm ${name}`);
  return { protocol: 'npm', repoName, name, version, extra: {} };
}

/** PyPI: the long description (Markdown) and the home page (`homePage`, hostile by default). */
export async function seedHostilePypi(
  repoName: string,
  runId: string,
  opts: SeedOptions & { homePage?: string } = {},
): Promise<SeededPackage> {
  const name = nameOf('pypi', runId, opts);
  const version = opts.version ?? DEFAULT_VERSION;
  const form = buildUploadForm(buildWheel({ name, version }));
  form.append('description', HOSTILE_README);
  form.append('description_content_type', 'text/markdown');
  form.append('home_page', opts.homePage ?? HOSTILE_URLS.javascript);
  const res = await fetch(uploadUrl(repoName), {
    method: 'POST',
    headers: authHeader(adminCredential()),
    body: form,
  });
  expectPublished(
    { status: res.status, body: Buffer.from(await res.arrayBuffer()), msgId: undefined },
    `pypi ${name}`,
  );
  return { protocol: 'pypi', repoName, name, version, extra: {} };
}

/** Cargo: the README and the description. */
export async function seedHostileCargo(
  repoName: string,
  runId: string,
  opts: SeedOptions = {},
): Promise<SeededPackage> {
  return publishCrate(repoName, nameOf('cargo', runId, opts), opts.version ?? DEFAULT_VERSION, {
    readme: HOSTILE_README,
    description: HOSTILE_TEXT,
  });
}

/** NuGet: the README, the title and the project, repository and licence URLs of the nuspec. */
export async function seedHostileNuget(
  repoName: string,
  runId: string,
  opts: SeedOptions = {},
): Promise<SeededPackage> {
  const name = nameOf('nuget', runId, opts);
  const version = opts.version ?? DEFAULT_VERSION;
  const nupkg = buildNupkg({
    packageId: name,
    version,
    metadata: {
      // The nuspec is XML, so the text is escaped here and comes back out as the very same text.
      title: xmlEscape(HOSTILE_TEXT),
      tags: xmlEscape(HOSTILE_TEXT),
      projectUrl: xmlEscape(HOSTILE_URLS.javascript),
      repositoryUrl: xmlEscape(HOSTILE_URLS.data),
      licenseUrl: xmlEscape(HOSTILE_URLS.vbscript),
      readme: HOSTILE_README,
    },
  });
  expectPublished(await nugetPublish(repoName, adminCredential(), nupkg), `nuget ${name}`);
  return { protocol: 'nuget', repoName, name, version, extra: {} };
}

/** Maven: the name, description and URLs of the POM. */
export async function seedHostileMaven(
  repoName: string,
  runId: string,
  opts: SeedOptions = {},
): Promise<SeededPackage> {
  const name = nameOf('maven', runId, opts);
  const version = opts.version ?? DEFAULT_VERSION;
  const [groupId, artifactId] = splitPackageName(name);
  const admin = adminCredential();
  const base = `${versionDir(groupId, artifactId, version)}/${artifactId}-${version}`;
  const pom = minimalPom(
    groupId,
    artifactId,
    version,
    `  <name>${xmlEscape(HOSTILE_TEXT)}</name>\n` +
      `  <description>${xmlEscape(HOSTILE_TEXT)}</description>\n` +
      `  <url>${xmlEscape(HOSTILE_URLS.javascript)}</url>\n` +
      `  <scm><url>${xmlEscape(HOSTILE_URLS.data)}</url></scm>\n`,
  );
  const octet = 'application/octet-stream';
  expectPublished(
    await rawPut(repoName, admin, `${base}.jar`, buildJar({ groupId, artifactId, version }), octet),
    `PUT ${base}.jar`,
  );
  expectPublished(await rawPut(repoName, admin, `${base}.pom`, pom, octet), `PUT ${base}.pom`);
  expectPublished(
    await rawPut(
      repoName,
      admin,
      `${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
      artifactMetadataXml({ groupId, artifactId, versions: [version] }),
      'application/xml',
    ),
    `PUT ${artifactDir(groupId, artifactId)}/maven-metadata.xml`,
  );
  return { protocol: 'maven', repoName, name, version, extra: {} };
}

/** Ruby: the homepage (`homepage`, hostile by default) and the description of the gemspec. */
export async function seedHostileRuby(
  repoName: string,
  runId: string,
  opts: SeedOptions & { homepage?: string } = {},
): Promise<SeededPackage> {
  const name = nameOf('ruby', runId, opts);
  const version = opts.version ?? DEFAULT_VERSION;
  const gem = await buildGem({
    name,
    version,
    homepage: opts.homepage ?? HOSTILE_URLS.javascript,
    description: HOSTILE_TEXT,
  });
  expectPublished(await rubyPublish(repoName, adminCredential(), gem.bytes), `gem ${gem.filename}`);
  return { protocol: 'ruby', repoName, name, version, extra: {} };
}
