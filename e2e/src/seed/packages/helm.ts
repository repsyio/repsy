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

/**
 * Helm seeder. Helm has TWO backend modules behind one panel, and `opts.variant` picks one:
 * `oci` (default) uploads the config and chart blobs and then the OCI manifest under the tag, what
 * `helm push oci://` sends; `classic` POSTs the chart `.tgz` as the multipart `chart` part, what
 * `helm cm-push` sends (ChartMuseum). Both feed the same chart list. The chart is built in code
 * (`helm-chart.ts`), never with `helm`. `opts.name` is the chart name (lower-case, `-` allowed),
 * `extra.variant` says which module published it.
 */
import { buildChart } from '../../clients/helm-chart.js';
import { buildConfigBytes, buildManifestBytes } from '../../clients/helm.js';
import {
  chartFileName,
  HELM_MEDIA_TYPES,
  rawPutManifest,
  rawUploadBlob,
  rawUploadChart,
} from '../../clients/helm-raw.js';
import { adminCredential } from '../../clients/raw-http.js';
import type { PackageSeeder } from '../packages.js';
import { defaultPackageName, DEFAULT_VERSION, expectPublished } from './shared.js';

export const HELM_VARIANTS = ['oci', 'classic'] as const;

export const seedHelm: PackageSeeder = async (repoName, ctx, opts) => {
  const name = opts.name ?? defaultPackageName('helm', ctx.runId, opts.index);
  const version = opts.version ?? DEFAULT_VERSION;
  const variant = opts.variant ?? 'oci';
  if (!(HELM_VARIANTS as readonly string[]).includes(variant)) {
    throw new Error(
      `helm seed: unknown variant "${variant}" (expected ${HELM_VARIANTS.join(', ')})`,
    );
  }
  const admin = adminCredential();
  const built = await buildChart({ name, version, marker: `e2e ${name}:${version}` });

  if (variant === 'classic') {
    expectPublished(
      await rawUploadChart(repoName, admin, built.tgzBytes, chartFileName(name, version), {
        route: 'chartmuseum',
      }),
      `POST chart ${name}-${version}.tgz`,
    );
  } else {
    const configBytes = buildConfigBytes(built);
    const { bytes: manifestBytes, digest: configDigest } = buildManifestBytes(built, configBytes);
    expectPublished(
      await rawUploadBlob(repoName, admin, name, configBytes, configDigest),
      `config blob of ${name}:${version}`,
    );
    expectPublished(
      await rawUploadBlob(repoName, admin, name, built.tgzBytes, built.tgzDigest),
      `chart blob of ${name}:${version}`,
    );
    expectPublished(
      await rawPutManifest(
        repoName,
        admin,
        name,
        version,
        manifestBytes,
        HELM_MEDIA_TYPES.manifest,
      ),
      `manifest ${name}:${version}`,
    );
  }

  return { protocol: 'helm', repoName, name, version, extra: { variant } };
};
