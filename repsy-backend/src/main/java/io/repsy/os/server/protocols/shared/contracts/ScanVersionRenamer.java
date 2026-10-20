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

package io.repsy.os.server.protocols.shared.contracts;

import java.util.UUID;

/**
 * Moves the security scans of an artifact version to its new version string. A protocol that
 * renames the versions it stores calls this inside its own transaction; the security module
 * implements it, so the protocols no longer depend on the scan repository (RPS-2278).
 */
public interface ScanVersionRenamer {

  /**
   * @param artifactName the artifact name as the scans store it (compared lower-cased)
   */
  void renameArtifactVersion(
      UUID repoId, String artifactName, String oldVersion, String newVersion);
}
