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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.oci.handlers.AbstractOciBlobPullProtocolMethodHandler;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;

/**
 * The OCI blob pull of a Docker layer, served with the layer's stored media type: the {@code
 * Content-Type} the blob check of the same digest reports (RPS-2172).
 */
public abstract class AbstractDockerLayerPullProtocolMethodHandler<ID>
    extends AbstractOciBlobPullProtocolMethodHandler<DockerProtocolFacade<ID>> {

  /** What a layer without a stored media type is served as. */
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  public AbstractDockerLayerPullProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET),
        OciPathUtils.blob(BlobDigests.DIGEST_REGEX),
        DEFAULT_CONTENT_TYPE,
        basePathParser,
        dockerFacade,
        provider);
  }

  @Override
  protected String contentType(final ProtocolContext context, final String digest) {
    final var mediaType = this.facade.getLayerMediaType(context, digest);

    return mediaType == null || mediaType.isBlank() ? DEFAULT_CONTENT_TYPE : mediaType;
  }

  @Override
  protected Resource getBlob(
      final ProtocolContext context, final HttpServletRequest request, final String digest)
      throws IOException {

    return this.facade.getLayer(context, digest, request.getServletPath());
  }
}
