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
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestDeletionComponent;
import io.repsy.os.server.protocols.docker.shared.utils.PathParserUtils;
import io.repsy.protocols.docker.protocol.facades.AbstractDockerProtocolTxFacade;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.SavedManifest;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@Transactional(readOnly = true)
@NullMarked
public class DockerProtocolTxFacade extends AbstractDockerProtocolTxFacade<UUID> {

  private final ManifestDeletionComponent manifestDeletionComponent;

  public DockerProtocolTxFacade(
      final ImageService<UUID> imageTxService,
      final LayerService<UUID> layerTxService,
      final ManifestService<UUID> manifestTxService,
      final DockerStorageService<UUID> dockerStorageService,
      final ObjectMapper objectMapper,
      final ManifestDeletionComponent manifestDeletionComponent) {

    super(dockerStorageService, layerTxService, imageTxService, manifestTxService, objectMapper);

    this.manifestDeletionComponent = manifestDeletionComponent;
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
      final long contentLength)
      throws IOException {

    return super.uploadLayerChunk(context, relativePath, inputStream, contentLength);
  }

  /**
   * Hashes the whole upload, which takes as long as the layer is big, so it does not hold a
   * database transaction (and its connection) open meanwhile.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void verifyLayerDigest(
      final ProtocolContext context, final RelativePath relativePath, final String digest)
      throws IOException {

    super.verifyLayerDigest(context, relativePath, digest);
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public void finalizeLayerUpload(
      final ProtocolContext context, final RelativePath relativePath, final LayerInfo layerInfo)
      throws IOException {

    super.finalizeLayerUpload(context, relativePath, layerInfo);

    final var repoInfo = ProtocolContextUtils.<UUID>getRepoInfo(context);

    // A layer whose digest is already stored is dropped here: its upload was charged as it was
    // written, so the bytes it freed are refunded.
    final var usages =
        super.dockerStorageService.rename(
            repoInfo.getStorageKey(), relativePath, layerInfo.getDigest());

    ProtocolContextUtils.addUsages(context, usages);
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public SavedManifest<UUID> saveManifest(
      final ProtocolContext context, final String imageName, final ManifestForm form)
      throws IOException {

    return super.saveManifest(context, imageName, form);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Resource getLayer(
      final ProtocolContext context, final String digest, final String servletPath)
      throws IOException {

    return super.getLayer(context, digest, servletPath);
  }

  /**
   * Deletes the manifest or tag the reference names, and refunds the bytes of the manifest file
   * that no other manifest of the repo needs any more.
   */
  @Override
  @Transactional
  public void deleteManifest(
      final ProtocolContext context, final String imageName, final String reference) {

    final var repoInfo = io.repsy.os.server.shared.utils.ProtocolContextUtils.getRepoInfo(context);

    final var freed = this.manifestDeletionComponent.delete(repoInfo, imageName, reference);

    if (freed != 0L) {
      ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(-freed));
    }
  }
}
