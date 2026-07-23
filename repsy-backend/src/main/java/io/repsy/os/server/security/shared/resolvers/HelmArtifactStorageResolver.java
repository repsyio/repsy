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
