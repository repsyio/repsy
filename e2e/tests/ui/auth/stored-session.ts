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
 * What the SPA keeps in `localStorage` for a session (`AuthService`): `username`, `token` and
 * `refresh-token` (under the keys of `target.ui.sessionStorageKeys`); it counts as logged in iff both
 * tokens exist. Shared by the auth specs.
 */
import type { Page } from '@playwright/test';

import { readStoredSession, type StoredSessionValues } from '../../../src/ui/session.js';

export type StoredSession = StoredSessionValues;

export const NO_SESSION: StoredSession = { username: null, token: null, refreshToken: null };

/** The three keys as they are in the page's `localStorage` right now. */
export function storedSession(page: Page): Promise<StoredSession> {
  return readStoredSession(page);
}

/** Three base64url segments: what a JWT looks like, without decoding or trusting it. */
export const JWT_SHAPE = /^[\w-]+\.[\w-]+\.[\w-]+$/;
