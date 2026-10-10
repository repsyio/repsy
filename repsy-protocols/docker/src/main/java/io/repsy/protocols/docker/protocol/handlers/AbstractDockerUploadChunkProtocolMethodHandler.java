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
import io.repsy.protocols.oci.handlers.AbstractOciUploadChunkProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/** The Docker upload chunk; the session lives at {@code /blobs/<id>} of the repo's storage. */
@NullMarked
public abstract class AbstractDockerUploadChunkProtocolMethodHandler<ID>
    extends AbstractOciUploadChunkProtocolMethodHandler<DockerProtocolFacade<ID>> {

  public AbstractDockerUploadChunkProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.PATCH).skipHeaderPreProcessor(true),
        basePathParser,
        dockerFacade,
        provider);
  }

  @Override
  protected long uploadSize(final ProtocolContext context, final String name, final String uploadId)
      throws IOException {

    return this.facade.getUploadSize(context, DockerUploadPaths.of(uploadId));
  }

  @Override
  protected long appendChunk(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String uploadId)
      throws IOException {

    return this.facade.uploadLayerChunk(
        context,
        DockerUploadPaths.of(uploadId),
        request.getInputStream(),
        request.getContentLengthLong());
  }

  @Override
  protected String uploadLocation(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String uploadId) {

    return this.getServletURILocation(context, name, uploadId);
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String sessionId) {

    return DockerUploadPaths.sessionLocation(context, imageName, sessionId);
  }
}
