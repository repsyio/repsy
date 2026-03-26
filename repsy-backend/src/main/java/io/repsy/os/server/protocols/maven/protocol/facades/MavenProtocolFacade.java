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
package io.repsy.os.server.protocols.maven.protocol.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.server.protocols.maven.shared.artifact.services.components.ArtifactDeletionComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.maven.protocol.facades.AbstractMavenProtocolFacade;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import java.io.InputStream;
import java.util.UUID;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class MavenProtocolFacade extends AbstractMavenProtocolFacade<UUID> {

  private final ArtifactDeletionComponent artifactDeletionComponent;
  private final ArtifactService<UUID> artifactService;

  public MavenProtocolFacade(
      final MavenStorageService<UUID> mavenStorageService,
      final ArtifactDeletionComponent artifactDeletionComponent,
      final ArtifactService<UUID> artifactService) {

    super(mavenStorageService, artifactService);

    this.artifactService = artifactService;
    this.artifactDeletionComponent = artifactDeletionComponent;
  }

  @Override
  @SneakyThrows
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    try {
      super.upload(context, inputStream, contentLength);
    } catch (final SignatureNotVerifiedException ex) {
      this.deleteArtifactVersion(storagePath, repoInfo);
      throw ex;
    }
  }

  @SneakyThrows
  private void deleteArtifactVersion(final StoragePath storagePath, final RepoInfo repoInfo) {

    final var nonSignedStoragePath = this.artifactService.getNonSignedStoragePath(storagePath);

    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException("itemNotFound");
    }

    this.artifactDeletionComponent.deleteArtifactVersion(
        repoInfo, gav.getGroupId(), gav.getArtifactId(), gav.getVersion());
  }
}
