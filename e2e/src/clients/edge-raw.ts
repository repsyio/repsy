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
 * Raw `fetch` for the `api` project (README.md "API suite", RPS-1480): what a request answers at the
 * HTTP edge -- which port serves what, the public URLs Repsy writes into its answers, the CORS and CSP
 * headers. Unlike the per-protocol `*-raw.ts` clients it takes a full URL, keeps every response header
 * and never follows a redirect, so a spec sees exactly what the connector sent. No retry: nothing here
 * is a credentialed protocol call that the throttle could 429.
 */
import { env } from '../env.js';

export interface EdgeResponse {
  status: number;
  /** Header names lower-cased; a repeated header is joined with ", " (`fetch` semantics). */
  headers: Headers;
  text: string;
  /** The parsed body when it is JSON, else `undefined`. */
  json: unknown;
}

export interface EdgeRequestOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: string;
}

/** The panel API port (8080 on a default stack). */
export const apiUrl = (path: string): string => `${env.apiBaseUrl}${path}`;

/** The wire-protocol port (9090 on a default stack). */
export const repoUrl = (path: string): string => `${env.repoBaseUrl}${path}`;

export async function edgeRequest(
  url: string,
  opts: EdgeRequestOptions = {},
): Promise<EdgeResponse> {
  const res = await fetch(url, {
    method: opts.method ?? 'GET',
    headers: opts.headers,
    body: opts.body,
    redirect: 'manual',
  });
  const text = await res.text();
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    json = undefined;
  }
  return { status: res.status, headers: res.headers, text, json };
}

/** `X-Forwarded-*` as a TLS-terminating proxy at `https://pub.e2e.test:8443` would add them. */
export const FORWARDED_HEADERS: Readonly<Record<string, string>> = {
  'X-Forwarded-Proto': 'https',
  'X-Forwarded-Host': 'pub.e2e.test',
  'X-Forwarded-Port': '8443',
};

/** What `FORWARDED_HEADERS` add up to. */
export const FORWARDED_ORIGIN = 'https://pub.e2e.test:8443';

/** An admin panel access token, from a raw `POST /api/auth/login` on the api port. */
export async function adminBearer(): Promise<string> {
  const res = await edgeRequest(apiUrl('/api/auth/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: env.adminUsername, password: env.adminPassword }),
  });
  const token = (res.json as { data?: { token?: string } } | undefined)?.data?.token;
  if (res.status !== 200 || !token) {
    throw new Error(
      `admin login on the api port answered ${res.status}: ${res.text.slice(0, 200)}`,
    );
  }
  return token;
}
