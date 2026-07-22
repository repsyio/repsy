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
package io.repsy.os.server.security.shared.resolvers;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Only covers the classic (chart-museum) push path — {@code charts/{name}-{version}.tgz} — which
 * is fully deterministic. A chart pushed via OCI instead lives at a content-addressed {@code
 * oci/blobs/{digest}} path that cannot be derived from name+version at all (same indirection as
 * Docker, deliberately out of scope here). Since both push paths share the single "HELM" repo type,
 * there is no way to tell them apart from the coordinate alone — so, unlike every other resolver in
 * this package, this one DOES check for the computed file's existence (a cheap {@link
 * org.springframework.core.io.Resource#exists()} stat, not a content read) and returns empty rather
 * than a wrong/nonexistent path when the chart was actually pushed via OCI.
 */
@Slf4j
@Component
@NullMarked
@RequiredArgsConstructor
public class HelmArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("HELM");

  private final @NonNull HelmStorageService<?> helmStorageService;

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    final var relativePath =
        this.helmStorageService.getChartRelativePath(artifactName, artifactVersion);
    final var storagePath = StoragePath.of(repoId, relativePath);

    try {
      final var resource = this.helmStorageService.getResource(storagePath, repoName);

      if (resource.isPresent() && resource.get().exists()) {
        return Optional.of(relativePath);
      }
    } catch (final IOException exception) {
      log.debug("Failed to check classic chart existence at {}", relativePath, exception);
    }

    return Optional.empty();
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
