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
 * A `HEAD` must answer what the `GET` of the same URL does, without the body (RPS-1465): the same
 * status, `Content-Type` and `Content-Disposition`, and the `GET`'s `Content-Length` when the body is a
 * stored file (a generated JSON body is not measured by the `HEAD`). NuGet, Cargo and Go had no `HEAD`
 * handler at all before, so a `HEAD` on an existing file was the router's generic `404 unknownPath`.
 */
import { expect } from '@playwright/test';

import { withBackoff429 } from '../scenarios/remote-throttle.js';

export interface HeadParityOptions {
  /** Compare `Content-Length` (a stored file); `false` for a body the server builds per request. */
  contentLength: boolean;
  /** The status both must answer; `200` unless the URL is a missing file. */
  status?: number;
}

/** GETs and HEADs `url` with the same `headers` and asserts the HEAD mirrors the GET. */
export async function expectHeadMirrorsGet(
  label: string,
  url: string,
  headers: Record<string, string>,
  options: HeadParityOptions,
): Promise<void> {
  const status = options.status ?? 200;
  let get: Response | undefined;
  let getBody = Buffer.alloc(0);
  await withBackoff429(async () => {
    get = await fetch(url, { headers });
    getBody = Buffer.from(await get.arrayBuffer());
    return get.status;
  });
  let head: Response | undefined;
  let headBody = Buffer.alloc(0);
  await withBackoff429(async () => {
    head = await fetch(url, { method: 'HEAD', headers });
    headBody = Buffer.from(await head.arrayBuffer());
    return head.status;
  });

  expect(get?.status, `GET ${label}`).toBe(status);
  expect(head?.status, `HEAD ${label}`).toBe(status);
  expect(headBody.length, `HEAD ${label} has no body`).toBe(0);
  expect(head?.headers.get('content-type'), `HEAD ${label} Content-Type`).toBe(
    get?.headers.get('content-type'),
  );
  const disposition = head?.headers.get('content-disposition') ?? null;
  expect(disposition, `HEAD ${label} Content-Disposition`).toBe(
    get?.headers.get('content-disposition') ?? null,
  );
  expect(disposition ?? '', `HEAD ${label} is not named f.txt`).not.toContain('f.txt');
  if (options.contentLength && status === 200) {
    expect(head?.headers.get('content-length'), `HEAD ${label} Content-Length`).toBe(
      String(getBody.length),
    );
  }
}
