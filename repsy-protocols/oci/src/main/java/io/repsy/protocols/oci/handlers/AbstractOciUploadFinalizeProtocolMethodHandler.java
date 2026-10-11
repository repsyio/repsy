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
package io.repsy.protocols.oci.handlers;

import static io.repsy.protocols.oci.constants.OciConstants.DOCKER_CONTENT_DIGEST;
import static io.repsy.protocols.oci.constants.OciConstants.DOCKER_UPLOAD_UUID;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code PUT /v2/<repo>/<name>/blobs/uploads/<id>?digest=<digest>}: stores the session (with the
 * request body as its last chunk) as the blob of that digest and answers 201 with the blob's
 * digest, the session id and the blob's {@code Location}. A missing {@code digest} is a 400 {@code
 * digestMissing}; the format verifies the digest.
 *
 * @param <F> the format's facade
 */
public abstract class AbstractOciUploadFinalizeProtocolMethodHandler<F>
    extends AbstractFacadeProtocolMethodHandler<F> {

  protected AbstractOciUploadFinalizeProtocolMethodHandler(
      final HandlerRoute route,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(
        route.path(OciPathUtils.UPLOAD_SESSION.asMatchPredicate()),
        basePathParser,
        facade,
        provider);
  }

  /** See {@link AbstractOciUploadSessionProtocolMethodHandler#reportedUploadId}. */
  protected String reportedUploadId(final String uploadId) {
    return uploadId;
  }

  /** Stores the blob and answers the digest the response reports for it. */
  protected abstract String finalizeUpload(
      ProtocolContext context,
      HttpServletRequest request,
      String name,
      String uploadId,
      String digest)
      throws Exception;

  /** The {@code Location} of the stored blob. */
  protected abstract String blobLocation(
      ProtocolContext context, HttpServletRequest request, String name, String digest);

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var matcher = OciPathMatches.match(OciPathUtils.UPLOAD_SESSION, context);

    if (matcher.isEmpty()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var name = matcher.get().group(1);
    final var uploadId = matcher.get().group(2);
    final var reportedId = this.reportedUploadId(uploadId);
    final var digest = request.getParameter("digest");

    if (digest == null) {
      throw new BadRequestException(ProtocolErrorCodes.DIGEST_MISSING);
    }

    final var storedDigest = this.finalizeUpload(context, request, name, uploadId, digest);

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(DOCKER_CONTENT_DIGEST, storedDigest)
        .header(DOCKER_UPLOAD_UUID, reportedId)
        .header(LOCATION, this.blobLocation(context, request, name, storedDigest))
        .build();
  }
}
