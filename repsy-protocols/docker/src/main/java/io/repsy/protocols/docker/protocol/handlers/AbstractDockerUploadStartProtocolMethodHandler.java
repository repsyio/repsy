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

import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.DOCKER_UPLOAD_UUID;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractDockerUploadStartProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  /** The OCI distribution spec's hint (end-4c) of the algorithm the blob's digest will use. */
  private static final String DIGEST_ALGORITHM_PARAMETER = "digest-algorithm";

  private static final Pattern UPLOAD_START_PATTERN = Pattern.compile("^/([^/]+)/blobs/uploads/?$");

  public AbstractDockerUploadStartProtocolMethodHandler(
      final PathParser basePathParser, final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.POST)
            .skipHeaderPreProcessor(true)
            .path(UPLOAD_START_PATTERN.asMatchPredicate()),
        basePathParser,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    // The registry keeps no state for a session until its first byte arrives, and the algorithm of
    // the blob is the one of the digest the finalizing PUT names, which is verified against the
    // stored bytes. So the hint (RPS-1594) is honoured by accepting what the finalize accepts and
    // refusing an algorithm the registry cannot check, up front instead of after the upload.
    final var digestAlgorithm = request.getParameter(DIGEST_ALGORITHM_PARAMETER);

    if (digestAlgorithm != null && !BlobDigests.isSupportedAlgorithm(digestAlgorithm)) {
      throw new BadRequestException(ProtocolErrorCodes.DOCKER_DIGEST_ALGORITHM_UNSUPPORTED);
    }

    // Minted once: the Location a client PATCHes/PUTs against and the Docker-Upload-UUID it may
    // read back must name the same session (RPS-1241), and getUuid() is a fresh id on every call.
    final var sessionId = this.getUuid();

    final var location =
        ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{sessionId}")
            .buildAndExpand(sessionId)
            .toUriString();

    return ResponseEntity.accepted()
        .header(LOCATION, location)
        .header(DOCKER_UPLOAD_UUID, sessionId.toString())
        .build();
  }

  protected UUID getUuid() {

    return UUID.randomUUID();
  }
}
