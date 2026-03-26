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
package io.repsy.protocols.maven.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.MutablePair;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenProtocolFacade<ID> implements MavenProtocolFacade<ID> {

  private static final String USAGES = "usages";

  private final MavenStorageService<ID> mavenStorageService;
  private final ArtifactService<ID> artifactService;

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.mavenStorageService.getResource(repoInfo.getName(), storagePath);
  }

  @Override
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength)
      throws IOException, XmlPullParserException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    byte[] content = null;

    final var fileName = storagePath.getRelativePath().getFileName();
    final MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair;

    if (ArtifactUtils.isFileSuitableForGavExtraction(fileName)) {
      artifactPair = this.artifactService.getDeployAndVersionType(repoInfo, storagePath);
    } else {
      content = inputStream.readAllBytes();
      artifactPair =
          this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
              repoInfo, content, fileName);
    }

    if (artifactPair == null) {
      return;
    }

    this.artifactService.checkDeploymentRules(repoInfo, artifactPair, storagePath);

    final var afterUploadUsage =
        this.mavenStorageService.writeInputStreamToPath(
            storagePath,
            content == null ? inputStream : new ByteArrayInputStream(content),
            repoInfo.getName());

    final var resource = this.mavenStorageService.getResource(repoInfo.getName(), storagePath);

    this.artifactService.createOrUpdateArtifact(repoInfo, storagePath, resource);

    context.addProperty(USAGES, afterUploadUsage);
  }
}
