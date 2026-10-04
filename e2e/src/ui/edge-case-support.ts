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

/**
 * Shared bits of the RPS-1761 edge-case specs (`tests/ui/edge-cases/`): a raw panel call with a bearer token
 * (the typed `PanelBackend` has no PATCH and turns an HTTP error into an exception, and these specs need the
 * status) and a description read-back.
 */
import { apiUrl, edgeRequest } from '../clients/edge-raw.js';

/** One panel REST call with a bearer `token`; an HTTP error status is returned, never thrown. */
export async function panelCall(
  token: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<{ status: number; data: unknown }> {
  const res = await edgeRequest(apiUrl(path), {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, data: (res.json as { data?: unknown } | undefined)?.data };
}

/** The description the panel returns for repository `name` (`undefined`: no such repository). */
export async function repoDescription(token: string, name: string): Promise<string | undefined> {
  const res = await edgeRequest(apiUrl(`/api/repos?q=${encodeURIComponent(name)}&size=50`), {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}` },
  });
  const content = (
    res.json as { content?: { name: string; description?: string | null }[] } | undefined
  )?.content;
  const repo = content?.find((candidate) => candidate.name === name);
  return repo ? (repo.description ?? '') : undefined;
}

/** Runs `task(i)` for 0..count-1, at most `limit` at a time. */
export async function inBatches(
  count: number,
  limit: number,
  task: (index: number) => Promise<unknown>,
): Promise<void> {
  for (let start = 0; start < count; start += limit) {
    const batch = Array.from({ length: Math.min(limit, count - start) }, (_, k) => task(start + k));
    await Promise.all(batch);
  }
}
