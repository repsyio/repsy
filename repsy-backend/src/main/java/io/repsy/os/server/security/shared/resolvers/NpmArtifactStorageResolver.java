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
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@NullMarked
@RequiredArgsConstructor
public class NpmArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("NPM");

  private final @NonNull NpmStorageService npmStorageService;

  @Qualifier("osStorageStrategyNpm")
  private final @NonNull StorageStrategy npmStorageStrategy;

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

    final var tarballPath = packageBasePath.resolve(tarballFilename).toString();

    return this.npmStorageStrategy
        .get(StoragePath.of(repoId, tarballPath), repoName)
        .map(resource -> tarballPath);
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
