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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.oci.handlers.AbstractOciUploadStartProtocolMethodHandler;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/**
 * The Docker upload start: honours the {@code digest-algorithm} hint (RPS-1594) and keeps no state
 * for a session until its first byte arrives.
 */
@NullMarked
public abstract class AbstractDockerUploadStartProtocolMethodHandler
    extends AbstractOciUploadStartProtocolMethodHandler {

  private static final String DIGEST_ALGORITHM_PARAMETER = "digest-algorithm";

  public AbstractDockerUploadStartProtocolMethodHandler(
      final PathParser basePathParser, final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.POST).skipHeaderPreProcessor(true),
        basePathParser,
        provider);
  }

  @Override
  protected UUID startUpload(final ProtocolContext context, final HttpServletRequest request) {

    // The registry keeps no state for a session until its first byte arrives, and the algorithm of
    // the blob is the one of the digest the finalizing PUT names, which is verified against the
    // stored bytes. So the hint (RPS-1594) is honoured by accepting what the finalize accepts and
    // refusing an algorithm the registry cannot check, up front instead of after the upload.
    final var digestAlgorithm = request.getParameter(DIGEST_ALGORITHM_PARAMETER);

    if (digestAlgorithm != null && !BlobDigests.isSupportedAlgorithm(digestAlgorithm)) {
      throw new BadRequestException(ProtocolErrorCodes.DOCKER_DIGEST_ALGORITHM_UNSUPPORTED);
    }

    return this.getUuid();
  }

  @Override
  protected String uploadLocation(final HttpServletRequest request, final UUID uploadId) {
    final var requestPath = request.getRequestURI();
    final var end = requestPath.endsWith("/") ? requestPath.length() - 1 : requestPath.length();

    return PublicUrls.origin(request) + requestPath.substring(0, end) + "/" + uploadId;
  }

  protected UUID getUuid() {

    return UUID.randomUUID();
  }
}
