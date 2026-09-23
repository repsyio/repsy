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
package io.repsy.protocols.cargo.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractCargoProtocolFacade<ID> implements CargoProtocolFacade {

  private static final String USAGES = "usages";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  private final CargoStorageService cargoStorageService;
  private final CargoCrateService<ID> cargoCrateService;
  private final ObjectMapper objectMapper;

  /**
   * The largest {@code .crate} a publish may carry. The length is a field inside Cargo's own wire
   * format rather than an HTTP header, so it is checked against this limit right after it is read,
   * before any of the crate itself is spooled to disk (RPS-1119).
   */
  private final long maxCrateBytes;

  @Override
  public List<CrateIndexEntry> getIndexEntries(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var crateName = CrateUtils.extractLastSegment(context);

    return this.cargoCrateService.getIndexEntries(repoInfo, crateName);
  }

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var crateNameAndVersionPair = CrateUtils.extractCrateNameAndVersion(context);
    final var crateName = crateNameAndVersionPair.getFirst();
    final var versionName = crateNameAndVersionPair.getSecond();

    final var resource =
        this.cargoStorageService.getCrate(
            repoInfo.getStorageKey(), repoInfo.getName(), crateName, versionName);

    this.cargoCrateService.incrementDownloadCount(repoInfo, crateName, versionName);

    return resource;
  }

  /**
   * Spools the {@code .crate} to a temporary file instead of holding it whole in memory (RPS-1119):
   * the declared length is checked against {@link #maxCrateBytes} before anything is read, the
   * tarball is inspected and the checksum is taken from the spool's own hash, and the file is
   * streamed into storage. The publish-metadata JSON is bounded the same way in {@link
   * CrateUtils#getPublishRequest}, which runs first, so a request that fails validation is refused
   * before the (potentially large) crate that follows it is even looked at.
   */
  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var published = CrateUtils.getPublishRequest(inputStream, this.objectMapper);

    CrateUtils.validatePublishRequest(published);

    final var request = CrateUtils.dropOverLongMetadata(published);

    final var crateLength = CrateUtils.readCrateLength(inputStream);

    if (crateLength > this.maxCrateBytes) {
      throw new MaxUploadSizeExceededException(this.maxCrateBytes);
    }

    final SpooledUpload spool;
    try {
      spool =
          SpooledUpload.spool(
              new BoundedLengthInputStream(inputStream, crateLength), this.maxCrateBytes);
    } catch (final EntryTooLargeException e) {
      // The body was chunked or understated its length and outgrew the limit while it was read.
      throw new MaxUploadSizeExceededException(this.maxCrateBytes, e);
    }

    try (spool) {
      if (spool.size() < crateLength) {
        throw new IllegalArgumentException("the crate body is shorter than declared");
      }

      final var crateName = CrateUtils.normalizeCrateName(request.name());
      final CrateUtils.CrateInspection inspection;
      try (final var inspectionStream = spool.openStream()) {
        inspection = CrateUtils.inspectCrate(inspectionStream);
      }

      final var requestWithChecksum =
          CrateUtils.createCratePublishRequestWithChecksum(
              request, spool.sha256Hex(), inspection.hasLib());

      final var indexJsonLine = CrateUtils.getIndexJsonLine(requestWithChecksum, this.objectMapper);

      final var usages =
          this.cargoStorageService.writeCrateAndIndex(
              repoInfo.getStorageKey(),
              repoInfo.getName(),
              crateName,
              request.vers(),
              spool.openStream(),
              indexJsonLine);

      this.cargoCrateService.publish(repoInfo, requestWithChecksum, inspection.edition());

      context.addProperty(ARTIFACT_NAME, crateName);
      context.addProperty(ARTIFACT_VERSION, request.vers());
      context.addProperty(
          STORAGE_PATH,
          String.format("crates/%s/%s-%s.crate", crateName, crateName, request.vers()));
      context.addProperty(USAGES, usages);
    }
  }

  /**
   * Reads at most {@code maxBytes} from {@code delegate}, then reports end-of-stream whatever the
   * delegate still has left. Wraps the request body before it is spooled (RPS-1119), so a {@code
   * .crate} whose declared length is shorter than what the client actually sends never pulls the
   * extra bytes into the spool; a body that ends early is instead reported by the spooled size
   * coming out shorter than the declared length. The delegate is not closed here: it is the
   * protocol's own request stream, which the caller owns.
   */
  private static final class BoundedLengthInputStream extends InputStream {

    private final InputStream delegate;
    private long remaining;

    private BoundedLengthInputStream(final InputStream delegate, final long maxBytes) {
      this.delegate = delegate;
      this.remaining = maxBytes;
    }

    @Override
    public int read() throws IOException {
      if (this.remaining <= 0) {
        return -1;
      }

      final var b = this.delegate.read();
      if (b >= 0) {
        this.remaining--;
      }

      return b;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (this.remaining <= 0) {
        return -1;
      }

      final var toRead = (int) Math.min(len, this.remaining);
      final var read = this.delegate.read(b, off, toRead);

      if (read > 0) {
        this.remaining -= read;
      }

      return read;
    }
  }

  @Override
  public void yank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var nameVersionPair = CrateUtils.extractCrateNameAndVersion(context);

    this.cargoCrateService.yank(repoInfo, nameVersionPair.getFirst(), nameVersionPair.getSecond());
  }

  @Override
  public void unyank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var nameVersionPair = CrateUtils.extractCrateNameAndVersion(context);

    this.cargoCrateService.unyank(
        repoInfo, nameVersionPair.getFirst(), nameVersionPair.getSecond());
  }

  @Override
  public Page<CrateListItem> search(
      final ProtocolContext context, final String query, final Pageable pageable) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.cargoCrateService.search(repoInfo, query, pageable);
  }
}
