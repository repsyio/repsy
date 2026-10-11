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
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.AbstractRoutedProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/**
 * {@code POST /v2/<repo>/<name>/blobs/uploads/}: opens an upload session and answers 202 with its
 * {@code Location} and {@code Docker-Upload-UUID}, both naming the one session id (RPS-1241).
 *
 * <p>The route matched the path already, so {@code handle} does not parse it again.
 *
 * <p>A cross-repo mount ({@code ?mount=<digest>&from=<repo>}) is not implemented: it is answered
 * like a plain start, a 202 with a new session, which the OCI distribution spec allows and every
 * client follows by uploading the blob.
 */
public abstract class AbstractOciUploadStartProtocolMethodHandler
    extends AbstractRoutedProtocolMethodHandler {

  /**
   * @param route the format's methods and properties; the path is {@link OciPathUtils#UPLOAD_START}
   */
  protected AbstractOciUploadStartProtocolMethodHandler(
      final HandlerRoute route, final PathParser basePathParser, final ProtocolProvider provider) {

    super(route.path(OciPathUtils.UPLOAD_START.asMatchPredicate()), basePathParser, provider);
  }

  /** Checks the request and opens the session, answering its id. */
  protected abstract UUID startUpload(ProtocolContext context, HttpServletRequest request);

  /** The {@code Location} a client sends the session's chunks and its finalize to. */
  protected abstract String uploadLocation(HttpServletRequest request, UUID uploadId);

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    // Minted once: the Location and the Docker-Upload-UUID must name the same session (RPS-1241).
    final var uploadId = this.startUpload(context, request);

    return ResponseEntity.accepted()
        .header(LOCATION, this.uploadLocation(request, uploadId))
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .build();
  }
}
