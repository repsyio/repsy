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
package io.repsy.protocols.helm.protocol.facades;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

/** The OCI blob side of the Helm facade: upload sessions, finalize, existence and download. */
@NullMarked
@RequiredArgsConstructor
final class HelmOciBlobFacade<ID> {

  private final HelmStorageService<ID> helmStorageService;
  private final OciBlobService<ID> ociBlobService;

  UUID startBlobUpload(final ProtocolContext context) {
    return UUID.randomUUID();
  }

  long uploadBlobChunk(
      final ProtocolContext context,
      final UUID uploadId,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var usages =
        this.helmStorageService.saveBlobChunk(
            repoInfo.getStorageKey(), uploadId, stream, repoInfo.getName());
    ProtocolContextUtils.addUsages(context, usages);
    return this.helmStorageService.getBlobSize(
        repoInfo.getStorageKey(), uploadId, repoInfo.getName());
  }

  long getUploadSize(final ProtocolContext context, final UUID uploadId) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.helmStorageService
        .findBlob(repoInfo.getStorageKey(), uploadId.toString(), repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.BLOB_NOT_FOUND))
        .contentLength();
  }

  @SneakyThrows
  HelmOciBlobInfo finalizeBlob(
      final ProtocolContext context,
      final UUID uploadId,
      final String digest,
      final String mediaType,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    if (contentLength > 0) {
      final var chunkUsages =
          this.helmStorageService.saveBlobChunk(
              repoInfo.getStorageKey(), uploadId, stream, repoInfo.getName());
      ProtocolContextUtils.addUsages(context, chunkUsages);
    }
    this.verifyUploadDigest(repoInfo, uploadId, digest);
    // The upload was charged as it was written; a blob whose digest is already stored is dropped
    // here, so the bytes it freed are refunded.
    final var finalizeUsages =
        this.helmStorageService.finalizeBlob(repoInfo.getStorageKey(), uploadId, digest);
    ProtocolContextUtils.addUsages(context, finalizeUsages);
    final var resource =
        this.helmStorageService
            .findBlob(repoInfo.getStorageKey(), digest, repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.BLOB_NOT_FOUND));
    final var form =
        HelmOciBlobForm.builder()
            .digest(digest)
            .size(resource.contentLength())
            .mediaType(mediaType)
            .build();
    return this.ociBlobService.getOrCreate(form, repoInfo.getId());
  }

  /** Refuses an upload that does not hash to the digest the client claims for it. */
  private void verifyUploadDigest(
      final BaseRepoInfo<ID> repoInfo, final UUID uploadId, final String digest)
      throws IOException {
    final var upload =
        this.helmStorageService
            .findBlob(repoInfo.getStorageKey(), uploadId.toString(), repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.BLOB_NOT_FOUND));

    if (!BlobDigests.matches(digest, upload.getInputStream())) {
      throw new BadRequestException(ProtocolErrorCodes.DIGEST_MISMATCH);
    }
  }

  Optional<HelmOciBlobInfo> checkBlob(final ProtocolContext context, final String digest) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var blobOpt = this.ociBlobService.findByDigest(repoInfo.getId(), digest);
    if (blobOpt.isEmpty()) {
      return Optional.empty();
    }
    if (!this.helmStorageService.blobExists(repoInfo.getStorageKey(), digest, repoInfo.getName())) {
      return Optional.empty();
    }
    return blobOpt;
  }

  Resource getBlob(final ProtocolContext context, final String digest) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.helmStorageService
        .findBlob(repoInfo.getStorageKey(), digest, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.BLOB_NOT_FOUND));
  }

  HelmOciBlobInfo getOrCreateBlob(final HelmOciBlobForm form, final ID repoId) {
    return this.ociBlobService.getOrCreate(form, repoId);
  }
}
