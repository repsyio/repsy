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
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.cargo.protocol.utils.CrateInspectionUtils;
import io.repsy.protocols.cargo.protocol.utils.CratePublishBodyUtils;
import io.repsy.protocols.cargo.protocol.utils.CratePublishRequestUtils;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.storage.RepoRef;
import io.repsy.protocols.shared.utils.BoundedLengthInputStream;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.RequestBodies;
import io.repsy.protocols.shared.utils.SpooledUpload;
import io.repsy.protocols.shared.utils.StoredUpload;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
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

    final var resource = this.getCrate(context);

    this.cargoCrateService.incrementDownloadCount(repoInfo, crateName, versionName);

    return resource;
  }

  @Override
  public Resource getCrate(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var crateNameAndVersionPair = CrateUtils.extractCrateNameAndVersion(context);

    return this.cargoStorageService.getCrate(
        RepoRef.of(repoInfo),
        crateNameAndVersionPair.getFirst(),
        crateNameAndVersionPair.getSecond());
  }

  /**
   * Spools the {@code .crate} to a temporary file instead of holding it whole in memory (RPS-1119):
   * the declared length is checked against {@link #maxCrateBytes} before anything is read, the
   * tarball is inspected and the checksum is taken from the spool's own hash, and the file is
   * streamed into storage. The publish-metadata JSON is bounded the same way in {@link
   * CratePublishBodyUtils#getPublishRequest}, which runs first, so a request that fails validation
   * is refused before the (potentially large) crate that follows it is even looked at. A body with
   * no byte in it, one that stops inside a length field, and a crate of zero bytes are refused with
   * a {@code 400} in Cargo's error shape, and nothing is stored (RPS-1466).
   */
  @Override
  public void publish(final ProtocolContext context, final InputStream requestBody)
      throws IOException {

    final var inputStream =
        RequestBodies.nonEmpty(requestBody)
            .orElseThrow(() -> new IllegalArgumentException("the publish body is empty"));
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var published = CratePublishBodyUtils.getPublishRequest(inputStream, this.objectMapper);

    CratePublishRequestUtils.validatePublishRequest(published);

    final var request = CratePublishRequestUtils.dropOverLongMetadata(published);

    final var crateLength = CratePublishBodyUtils.readCrateLength(inputStream);

    if (crateLength == 0) {
      throw new IllegalArgumentException("the crate is empty");
    }

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
      final CrateInspectionUtils.CrateInspection inspection;
      try (final var inspectionStream = spool.openStream()) {
        inspection = CrateInspectionUtils.inspectCrate(inspectionStream);
      }

      final var requestWithChecksum =
          CratePublishRequestUtils.createCratePublishRequestWithChecksum(
              request, spool.sha256Hex(), inspection.hasLib());

      final var indexJsonLine =
          CratePublishRequestUtils.getIndexJsonLine(requestWithChecksum, this.objectMapper);

      final var usages =
          this.cargoCrateService.publish(
              repoInfo,
              requestWithChecksum,
              inspection.edition(),
              () -> this.storeCrate(repoInfo, crateName, request.vers(), spool, indexJsonLine));

      context.addProperty(ARTIFACT_NAME, crateName);
      context.addProperty(ARTIFACT_VERSION, request.vers());
      context.addProperty(
          STORAGE_PATH,
          String.format("crates/%s/%s-%s.crate", crateName, crateName, request.vers()));
      context.addProperty(USAGES, usages);
    }
  }

  /**
   * Stores the crate and its index line while {@link CargoCrateService#publish} still holds the
   * version's rows: a failure rolls the rows back, and the partly written crate file of the new
   * version is removed so nothing is left in storage that no row describes (RPS-1124).
   */
  private BaseUsages storeCrate(
      final BaseRepoInfo<ID> repoInfo,
      final String crateName,
      final String version,
      final SpooledUpload spool,
      final String indexJsonLine)
      throws IOException {

    // A crate version is never replaced: CargoCrateService#publish refuses an existing version
    // before this runs, so the file removed on failure is always the new version's own.
    return StoredUpload.storeOrDiscard(
        () -> {
          try (final var crateStream = spool.openStream()) {
            return this.cargoStorageService.writeCrateAndIndex(
                RepoRef.of(repoInfo), crateName, version, crateStream, indexJsonLine);
          }
        },
        () -> this.cargoStorageService.deleteCrate(RepoRef.of(repoInfo), crateName, version),
        false,
        log,
        "crate " + crateName + " " + version);
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
