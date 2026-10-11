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
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NuGetArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("NUGET");

  private final NuGetStorageService nuGetStorageService;

  private final StorageStrategyRegistry storageStrategyRegistry;

  @Override
  public Optional<String> resolve(
      final UUID repoId,
      final String repoName,
      final String artifactName,
      final String artifactVersion) {

    final var nupkgPath =
        this.nuGetStorageService.getNupkgRelativePath(artifactName, artifactVersion);

    return this.storageStrategyRegistry
        .get(RepoType.NUGET)
        .get(StoragePath.of(repoId, nupkgPath), repoName)
        .map(resource -> nupkgPath);
  }

  @Override
  public Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
