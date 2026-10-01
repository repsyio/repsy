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
 * A PGP public key registered directly on a repo's Maven key store (RPS-1501). Mirrors the backend's
 * `PgpPublicKeyItem` record (`GET/POST /api/mvn/key-stores/{repoName}/public-keys`); no key material
 * is returned, only the fingerprint/keyId identifying it.
 */
export interface PgpPublicKeyItem {
  uuid: string;
  keyId: string;
  fingerprint: string;
  userId?: string;
  createdAt: string;
}
