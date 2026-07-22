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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Maps a repo type (e.g. "MAVEN") to the {@link ArtifactStorageResolver} bean that supports it,
 * built once at startup from every {@link ArtifactStorageResolver} bean Spring can find. Repo types
 * with no registered resolver (Docker's registry-reference mechanism, Helm-OCI's manifest/digest
 * indirection) simply have no entry — callers get an empty {@link Optional} and treat it the same
 * as a resolver-returned {@code Optional.empty()}.
 */
@Slf4j
@Component
public class ArtifactStorageResolverRegistry {

  private final @NonNull Map<String, ArtifactStorageResolver> resolversByRepoType;

  public ArtifactStorageResolverRegistry(final @NonNull List<ArtifactStorageResolver> resolvers) {
    this.resolversByRepoType = buildRegistry(resolvers);
  }

  public @NonNull Optional<ArtifactStorageResolver> findResolver(final @NonNull String repoType) {
    return Optional.ofNullable(this.resolversByRepoType.get(repoType));
  }

  private static @NonNull Map<String, ArtifactStorageResolver> buildRegistry(
      final @NonNull List<ArtifactStorageResolver> resolvers) {

    final var registry = new HashMap<String, ArtifactStorageResolver>();

    for (final var resolver : resolvers) {
      for (final var repoType : resolver.getSupportedRepoTypes()) {
        registerIfAbsent(registry, repoType, resolver);
      }
    }

    return Map.copyOf(registry);
  }

  private static void registerIfAbsent(
      final @NonNull Map<String, ArtifactStorageResolver> registry,
      final @NonNull String repoType,
      final @NonNull ArtifactStorageResolver resolver) {

    final var existing = registry.get(repoType);
    if (existing != null) {
      log.warn(
          "Multiple artifact storage resolvers support repo type {}: keeping {}, ignoring {}",
          repoType,
          existing.getClass().getSimpleName(),
          resolver.getClass().getSimpleName());
      return;
    }

    registry.put(repoType, resolver);
  }
}
