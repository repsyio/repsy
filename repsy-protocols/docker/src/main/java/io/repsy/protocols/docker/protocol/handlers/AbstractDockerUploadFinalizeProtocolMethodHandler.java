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
package io.repsy.protocols.docker.protocol.handlers;

import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_LAYER;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.oci.handlers.AbstractOciUploadFinalizeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;

/**
 * The Docker upload finalize: appends the request body, verifies the digest against the stored
 * bytes and records the layer (retrying a concurrent first insert).
 */
@NullMarked
public abstract class AbstractDockerUploadFinalizeProtocolMethodHandler<ID>
    extends AbstractOciUploadFinalizeProtocolMethodHandler<DockerProtocolFacade<ID>> {

  private static final int RETRY_COUNT = 3;

  private static final long WAIT_RETRY = 100;

  private final LayerService<ID> layerService;

  public AbstractDockerUploadFinalizeProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final LayerService<ID> layerService,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.PUT).skipHeaderPreProcessor(true),
        basePathParser,
        dockerFacade,
        provider);
    this.layerService = layerService;
  }

  @Override
  protected String finalizeUpload(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String uploadId,
      final String digest)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var uploadPath = DockerUploadPaths.of(uploadId);

    if (request.getContentLength() > 0) {
      this.facade.uploadLayerChunk(
          context, uploadPath, request.getInputStream(), request.getContentLengthLong());
    }

    this.facade.verifyLayerDigest(context, uploadPath, digest);

    final var layerForm =
        LayerForm.builder()
            .imageName(name)
            .digest(digest)
            .mediaType(DOCKER_LAYER)
            .uuid(UUID.fromString(uploadId))
            .build();

    final var layerInfo = this.findOrCreateLayer(repoInfo.getId(), layerForm, 1);

    this.facade.finalizeLayerUpload(context, uploadPath, layerInfo);

    return digest;
  }

  @Override
  protected String blobLocation(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String digest) {

    return this.getServletURILocation(context, name, digest);
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String digest) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return PublicUrls.currentContextRoot()
        + "/v2/"
        + urlProperties.getRepoName()
        + "/"
        + imageName
        + "/blobs/"
        + digest;
  }

  @SneakyThrows
  private LayerInfo findOrCreateLayer(
      final ID repoId, final LayerForm layerForm, final int counter) {

    try {
      return this.layerService.getOrCreate(layerForm, repoId);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }

      Thread.sleep(WAIT_RETRY * counter);

      return this.findOrCreateLayer(repoId, layerForm, counter + 1);
    }
  }
}
