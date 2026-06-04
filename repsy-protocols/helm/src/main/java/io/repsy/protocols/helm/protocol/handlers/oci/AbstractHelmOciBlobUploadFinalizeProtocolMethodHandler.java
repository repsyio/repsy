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

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_CONTENT_DIGEST;
import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_UPLOAD_UUID;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Handles PUT /v2/{repo}/{name}/blobs/uploads/{uuid}?digest= — finalizes a blob upload. */
@NullMarked
public abstract class AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern UPLOAD_FINALIZE_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  private final PathParser basePathParser;
  private final HelmFacade<ID> helmFacade;

  public AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!UPLOAD_FINALIZE_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = UPLOAD_FINALIZE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var uploadId = UUID.fromString(matcher.group(2));
    final var digest = request.getParameter("digest");
    final var mediaType = request.getContentType();

    if (digest == null) {
      return ResponseEntity.badRequest().build();
    }

    final var contentLength = request.getContentLengthLong();
    final var blobInfo =
        this.helmFacade.finalizeBlob(
            context,
            uploadId,
            digest,
            mediaType != null ? mediaType : "application/octet-stream",
            request.getInputStream(),
            contentLength);

    // Derive base blob path from request URI: strip "/uploads/{uuid}" → ".../blobs/{digest}"
    final var requestPath = request.getRequestURI();
    final var blobsBasePath = requestPath.substring(0, requestPath.lastIndexOf("/uploads/"));
    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(blobsBasePath + "/" + blobInfo.getDigest())
            .build()
            .toUriString();

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(DOCKER_CONTENT_DIGEST, blobInfo.getDigest())
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .header(LOCATION, location)
        .build();
  }
}
