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

import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * {@code artifactName} is stored as {@code "@scope/pkg"} (scoped) or {@code "pkg"} (unscoped) —
 * see {@code AbstractNpmProtocolFacade#buildArtifactName} — so it is split back apart here rather
 * than needing a separate scope lookup.
 */
@Component
@NullMarked
@RequiredArgsConstructor
public class NpmArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("NPM");

  private final @NonNull NpmStorageService npmStorageService;

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    final var scopeSlashIndex = artifactName.indexOf('/');
    final String scopeName;
    final String packageName;

    if (artifactName.startsWith("@") && scopeSlashIndex > 0) {
      scopeName = artifactName.substring(1, scopeSlashIndex);
      packageName = artifactName.substring(scopeSlashIndex + 1);
    } else {
      scopeName = null;
      packageName = artifactName;
    }

    final var packageBasePath = this.npmStorageService.getPackageBasePath(scopeName, packageName);
    final var tarballFilename = PackageUtils.getTarballFilename(packageName, artifactVersion);

    return Optional.of(packageBasePath.resolve(tarballFilename).toString());
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
