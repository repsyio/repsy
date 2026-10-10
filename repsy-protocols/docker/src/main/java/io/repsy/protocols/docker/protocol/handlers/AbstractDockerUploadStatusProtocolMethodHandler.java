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
import io.repsy.protocols.oci.handlers.AbstractOciUploadStatusProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/**
 * Handles {@code GET}/{@code HEAD} {@code /v2/{name}/blobs/uploads/{uuid}} — how a client resumes
 * an interrupted upload: it answers {@code 204} with the {@code Range} of the bytes already
 * written, or a {@code 404} ({@code io.repsy.core.error_handling.exceptions.ItemNotFoundException}
 * propagated from the facade) when the session does not exist.
 */
@NullMarked
public abstract class AbstractDockerUploadStatusProtocolMethodHandler<ID>
    extends AbstractOciUploadStatusProtocolMethodHandler<DockerProtocolFacade<ID>> {

  public AbstractDockerUploadStatusProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.GET, HttpMethod.HEAD),
        basePathParser,
        dockerFacade,
        provider);
  }

  @Override
  protected long uploadSize(final ProtocolContext context, final String name, final String uploadId)
      throws IOException {

    // Throws ItemNotFoundException when no such upload session exists, which ErrorHandler turns
    // into the 404 BLOB_UPLOAD_UNKNOWN the OCI distribution spec asks for.
    return this.facade.getUploadSize(context, DockerUploadPaths.of(uploadId));
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
