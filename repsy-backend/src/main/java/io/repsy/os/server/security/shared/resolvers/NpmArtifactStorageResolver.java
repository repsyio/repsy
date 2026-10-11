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
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.npm.shared.utils.NpmPackageUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NpmArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("NPM");

  private final NpmStorageService npmStorageService;

  private final StorageStrategyRegistry storageStrategyRegistry;

  @Override
  public Optional<String> resolve(
      final UUID repoId,
      final String repoName,
      final String artifactName,
      final String artifactVersion) {

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
    final var tarballFilename = NpmPackageUtils.getTarballFilename(packageName, artifactVersion);

    final var tarballPath = packageBasePath.resolve(tarballFilename).toString();

    return this.storageStrategyRegistry
        .get(RepoType.NPM)
        .get(StoragePath.of(repoId, tarballPath), repoName)
        .map(resource -> tarballPath);
  }

  @Override
  public Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
