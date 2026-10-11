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
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("GOLANG");

  private final GoStorageService<?> goStorageService;

  private final StorageStrategyRegistry storageStrategyRegistry;

  @Override
  public Optional<String> resolve(
      final UUID repoId,
      final String repoName,
      final String artifactName,
      final String artifactVersion) {

    final var zipPath =
        this.goStorageService.getModuleZipRelativePath(artifactName, artifactVersion);

    return this.storageStrategyRegistry
        .get(RepoType.GOLANG)
        .get(StoragePath.of(repoId, zipPath), repoName)
        .map(resource -> zipPath);
  }

  @Override
  public Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
