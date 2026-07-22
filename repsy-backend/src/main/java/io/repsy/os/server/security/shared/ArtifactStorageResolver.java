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
package io.repsy.os.server.security.shared;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Recomputes an artifact version's storage-relative path live, from its coordinate alone, instead
 * of relying on a previously-recorded scan row. Implementations must only compute/verify the path
 * (protocol-specific formula, optionally checked against a metadata table) — never download or read
 * the artifact's content.
 */
public interface ArtifactStorageResolver {

  @NonNull Optional<String> resolve(
      @NonNull UUID repoId,
      @NonNull String repoName,
      @NonNull String artifactName,
      @NonNull String artifactVersion);

  @NonNull Set<String> getSupportedRepoTypes();
}
