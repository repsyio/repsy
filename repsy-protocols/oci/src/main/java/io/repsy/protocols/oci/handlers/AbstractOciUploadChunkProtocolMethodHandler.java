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

import static io.repsy.protocols.oci.constants.OciConstants.DOCKER_UPLOAD_UUID;
import static io.repsy.protocols.oci.constants.OciConstants.RANGE;
import static org.springframework.http.HttpHeaders.CONTENT_RANGE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.oci.utils.OciUploadUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code PATCH /v2/<repo>/<name>/blobs/uploads/<id>}: appends a chunk and answers 202 with the
 * session's {@code Location}, {@code Range} and id.
 *
 * <p>A {@code Content-Range} whose start ({@link OciUploadUtils#parseContentRangeStart}) is not the
 * session's current size is a 416 with the same headers, so the client can resume where the session
 * ends; a header the parser cannot read is ignored and the chunk appended.
 *
 * @param <F> the format's facade
 */
public abstract class AbstractOciUploadChunkProtocolMethodHandler<F>
    extends AbstractOciUploadSessionProtocolMethodHandler<F> {

  protected AbstractOciUploadChunkProtocolMethodHandler(
      final HandlerRoute route,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(route, basePathParser, facade, provider);
  }

  /** Appends the request body to the session and answers the session's new size. */
  protected abstract long appendChunk(
      ProtocolContext context, HttpServletRequest request, String name, String uploadId)
      throws IOException;

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

    final var contentRange = request.getHeader(CONTENT_RANGE);
    if (contentRange != null) {
      final var rangeStart = OciUploadUtils.parseContentRangeStart(contentRange);
      if (rangeStart.isPresent()) {
        final var currentSize = this.currentUploadSize(context, name, uploadId);
        if (rangeStart.getAsLong() != currentSize) {
          return this.answer(
              HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
              this.uploadLocation(context, request, name, uploadId),
              currentSize,
              reportedId);
        }
      }
    }

    final var uploadSize = this.appendChunk(context, request, name, uploadId);

    return this.answer(
        HttpStatus.ACCEPTED,
        this.uploadLocation(context, request, name, uploadId),
        uploadSize,
        reportedId);
  }

  /**
   * The bytes already written, a session that never received a chunk (nothing on disk yet) being
   * size zero rather than a failure: the first {@code PATCH} of a fresh upload legitimately carries
   * {@code Content-Range: 0-N}.
   */
  private long currentUploadSize(
      final ProtocolContext context, final String name, final String uploadId) throws IOException {

    try {
      return this.uploadSize(context, name, uploadId);
    } catch (final ItemNotFoundException _) {
      return 0;
    }
  }

  private ResponseEntity<Object> answer(
      final HttpStatus status, final String location, final long size, final String uploadId) {

    return ResponseEntity.status(status)
        .header(LOCATION, location)
        .header(RANGE, OciUploadUtils.range(size))
        .header(DOCKER_UPLOAD_UUID, uploadId)
        .build();
  }
}
