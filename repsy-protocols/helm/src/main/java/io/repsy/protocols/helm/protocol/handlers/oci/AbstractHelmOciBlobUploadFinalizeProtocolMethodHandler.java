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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.oci.handlers.AbstractOciUploadFinalizeProtocolMethodHandler;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Handles PUT /v2/{repo}/{name}/blobs/uploads/{uuid}?digest= — finalizes a blob upload. */
@NullMarked
public abstract class AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler<ID>
    extends AbstractOciUploadFinalizeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final String DEFAULT_BLOB_MEDIA_TYPE = "application/octet-stream";

  public AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.PUT)
            .skipHeaderPreProcessor(true)
            .writeOperation(true),
        basePathParser,
        helmFacade,
        provider);
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
  protected String reportedUploadId(final String uploadId) {
    return UUID.fromString(uploadId).toString();
  }

  @Override
  protected String finalizeUpload(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String uploadId,
      final String digest)
      throws IOException {

    // helm_oci_blob.digest holds "sha256:" and 64 hex characters, but BlobDigests also admits a
    // sha512 one, which is 135 characters and would fail the row insert after the blob was stored.
    if (BlobDigests.isSupported(digest) && !digest.startsWith(HelmConstants.SHA256_PREFIX)) {
      throw new BadRequestException(ProtocolErrorCodes.BLOB_DIGEST_UNSUPPORTED);
    }

    return this.facade
        .finalizeBlob(
            context,
            UUID.fromString(uploadId),
            digest,
            blobMediaType(request.getContentType()),
            request.getInputStream(),
            request.getContentLengthLong())
        .digest();
  }

  @Override
  protected String blobLocation(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String digest) {

    // Derive base blob path from request URI: strip "/uploads/{uuid}" → ".../blobs/{digest}"
    final var requestPath = request.getRequestURI();
    final var blobsBasePath = requestPath.substring(0, requestPath.lastIndexOf("/uploads/"));

    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(blobsBasePath + "/" + digest)
        .build()
        .toUriString();
  }
}
