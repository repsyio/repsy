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
 * The size-limit leg's payload sizing (RPS-1482, README.md "Size-limit leg"): the limit the overlay
 * `docker-compose.stack-limits.yml` sets on every configurable upload limit, and how much random
 * padding takes a package over it or keeps it just under. The padding is RANDOM, never zeros or text:
 * a gem, a crate and a chart are gzipped by the client, and a compressible payload would shrink
 * below the limit and be accepted.
 */
import { randomBytes } from 'node:crypto';

/** What `MULTIPART_MAX_FILE_SIZE`, `RUBY_MAX_GEM_SIZE`, `CARGO_MAX_CRATE_SIZE` and `GO_MAX_MODULE_ZIP_SIZE`
 *  are set to in docker-compose.stack-limits.yml ("64KB" is 64 x 1024 for Spring's `DataSize`). */
export const SIZE_LIMIT_BYTES = 64 * 1024;

/** Padding that takes any of the small test packages well over the limit (the package is about 100 KB). */
export const OVER_LIMIT_PADDING_BYTES = 100_000;

/** Padding that keeps a package clearly under the limit, but is real weight (about 20 KB): what the
 *  positive control uploads, to show the limit is not simply refusing everything. */
export const UNDER_LIMIT_PADDING_BYTES = 20_000;

/** `bytes` bytes of random padding; for the `padBytes` option of the package builders. */
export function randomPadding(bytes: number): Buffer {
  return randomBytes(bytes);
}
