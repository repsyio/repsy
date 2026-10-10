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
package io.repsy.protocols.helm.protocol.handlers.oci;

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_UPLOAD_UUID;
import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.RANGE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Handles {@code GET}/{@code HEAD} {@code /v2/{repo}/{name}/blobs/uploads/{uuid}} — how a client
 * resumes an interrupted upload: it answers {@code 204} with the {@code Range} of the bytes already
 * written, or a {@code 404} ({@code io.repsy.core.error_handling.exceptions.ItemNotFoundException}
 * propagated from the facade) when the session does not exist.
 */
@NullMarked
public abstract class AbstractHelmOciBlobUploadStatusProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern UPLOAD_STATUS_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  public AbstractHelmOciBlobUploadStatusProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.GET, HttpMethod.HEAD)
            .path(UPLOAD_STATUS_PATTERN.asMatchPredicate()),
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = UPLOAD_STATUS_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var uploadId = UUID.fromString(matcher.group(2));

    // Throws ItemNotFoundException when no such upload session exists, which ErrorHandler turns
    // into the 404 BLOB_UPLOAD_UNKNOWN the OCI distribution spec asks for.
    final var uploadSize = this.facade.getUploadSize(context, uploadId);

    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(request.getRequestURI())
            .build()
            .toUriString();

    return ResponseEntity.noContent()
        .header(LOCATION, location)
        .header(RANGE, "0-" + Math.max(uploadSize - 1, 0))
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .build();
  }
}
