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
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.oci.utils.OciUploadUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET} (and {@code HEAD}) {@code /v2/<repo>/<name>/blobs/uploads/<id>}: 204 with the
 * session's {@code Location}, {@code Range} and id. An unknown session is the format's not-found
 * exception, which the error handler answers as the OCI {@code BLOB_UPLOAD_UNKNOWN} (Docker) or
 * {@code BLOB_UNKNOWN} (Helm) 404.
 *
 * @param <F> the format's facade
 */
public abstract class AbstractOciUploadStatusProtocolMethodHandler<F>
    extends AbstractOciUploadSessionProtocolMethodHandler<F> {

  protected AbstractOciUploadStatusProtocolMethodHandler(
      final HandlerRoute route,
      final PathParser basePathParser,
      final F facade,
      final ProtocolProvider provider) {

    super(route, basePathParser, facade, provider);
  }

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

    final var uploadSize = this.uploadSize(context, name, uploadId);

    return ResponseEntity.noContent()
        .header(LOCATION, this.uploadLocation(context, request, name, uploadId))
        .header(RANGE, OciUploadUtils.range(uploadSize))
        .header(DOCKER_UPLOAD_UUID, reportedId)
        .build();
  }
}
