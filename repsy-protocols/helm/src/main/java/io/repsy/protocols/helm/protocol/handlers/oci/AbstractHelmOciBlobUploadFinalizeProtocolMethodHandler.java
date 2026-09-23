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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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

  private static final String DEFAULT_BLOB_MEDIA_TYPE = "application/octet-stream";

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
    return Map.of(
        "permission", Permission.WRITE, "skipHeaderPreProcessor", true, "writeOperation", true);
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

  /**
   * The media type recorded for the blob. A blob is addressed by its digest and a chart is found
   * through its manifest, so the type is only informational: a client that names none, or one that
   * does not fit the 255 characters of helm_oci_blob.media_type, gets the generic one and the push
   * goes through (RPS-1072).
   */
  private static String blobMediaType(final @Nullable String contentType) {
    if (contentType == null || contentType.length() > HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH) {
      return DEFAULT_BLOB_MEDIA_TYPE;
    }
    return contentType;
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
      throw new BadRequestException("digestMissing");
    }

    // helm_oci_blob.digest holds "sha256:" and 64 hex characters, but BlobDigests also admits a
    // sha512 one, which is 135 characters and would fail the row insert after the blob was stored.
    if (BlobDigests.isSupported(digest) && !digest.startsWith(HelmConstants.SHA256_PREFIX)) {
      throw new BadRequestException("blobDigestUnsupported");
    }

    final var contentLength = request.getContentLengthLong();
    final var blobInfo =
        this.helmFacade.finalizeBlob(
            context,
            uploadId,
            digest,
            blobMediaType(mediaType),
            request.getInputStream(),
            contentLength);

    // Derive base blob path from request URI: strip "/uploads/{uuid}" → ".../blobs/{digest}"
    final var requestPath = request.getRequestURI();
    final var blobsBasePath = requestPath.substring(0, requestPath.lastIndexOf("/uploads/"));
    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(blobsBasePath + "/" + blobInfo.digest())
            .build()
            .toUriString();

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(DOCKER_CONTENT_DIGEST, blobInfo.digest())
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .header(LOCATION, location)
        .build();
  }
}
