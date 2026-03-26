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
package io.repsy.os.server.protocols.docker.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.docker.shared.utils.PathParserUtils;
import io.repsy.protocols.docker.protocol.facades.AbstractDockerProtocolTxFacade;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@Transactional(readOnly = true)
@NullMarked
public class DockerProtocolTxFacade extends AbstractDockerProtocolTxFacade<UUID> {

  public DockerProtocolTxFacade(
      final ImageService<UUID> imageTxService,
      final LayerService<UUID> layerTxService,
      final ManifestService<UUID> manifestTxService,
      final DockerStorageService<UUID> dockerStorageService,
      final ObjectMapper objectMapper) {

    super(dockerStorageService, layerTxService, imageTxService, manifestTxService, objectMapper);
  }

  @Override
  public BaseParsedPath parseForLayer(final String servletPath, final String digest) {

    return PathParserUtils.parseForLayer(servletPath, digest);
  }

  @Override
  public BaseParsedPath parseForManifest(final String servletPath, final String fileName) {

    return PathParserUtils.parseForManifest(servletPath, fileName);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public long uploadLayerChunk(
      final ProtocolContext context,
      final RelativePath relativePath,
      final InputStream inputStream,
      final long contentLength) {

    return super.uploadLayerChunk(context, relativePath, inputStream, contentLength);
  }

  @Override
  @Transactional
  public void finalizeLayerUpload(
      final BaseRepoInfo<UUID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo)
      throws IOException {

    super.finalizeLayerUpload(repoInfo, relativePath, layerInfo);

    super.dockerStorageService.rename(
        repoInfo.getStorageKey(), relativePath, layerInfo.getDigest());
  }

  @Override
  @Transactional
  public @Nullable String saveManifest(
      final ProtocolContext context, final BaseImageInfo<UUID> imageInfo, final ManifestForm form)
      throws IOException {

    return super.saveManifest(context, imageInfo, form);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Resource getLayer(
      final ProtocolContext context, final String digest, final String servletPath)
      throws IOException {

    return super.getLayer(context, digest, servletPath);
  }
}
