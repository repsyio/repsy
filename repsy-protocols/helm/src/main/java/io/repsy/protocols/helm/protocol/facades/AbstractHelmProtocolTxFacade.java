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
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexDto;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexEntryDto;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListDto;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractHelmProtocolTxFacade<ID> implements HelmFacade<ID> {

  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  protected final HelmStorageService<ID> helmStorageService;
  protected final ChartService<ID> chartService;
  protected final OciBlobService<ID> ociBlobService;
  protected final OciManifestService<ID> ociManifestService;
  protected final AbstractHelmChartFilesService<ID> chartFilesService;

  @Override
  public HelmIndexDto generateIndex(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var charts = this.chartService.findAllByRepoId(repoInfo.getId());

    final Map<String, List<HelmIndexEntryDto>> entries = new LinkedHashMap<>();

    for (final var chart : charts) {
      final var url =
          HelmConstants.CHARTS_PATH
              + "/"
              + chart.name()
              + "-"
              + chart.version()
              + HelmConstants.TGZ_EXTENSION;
      final var entry =
          HelmIndexEntryDto.builder()
              .name(chart.name())
              .version(chart.version())
              .description(chart.description())
              .appVersion(chart.appVersion())
              .type(chart.type())
              .digest(chart.digest())
              .urls(List.of(url))
              .created(chart.createdAt().toString())
              .build();
      entries.computeIfAbsent(chart.name(), _ -> new ArrayList<>()).add(entry);
    }

    return HelmIndexDto.builder()
        .apiVersion(HelmConstants.API_VERSION)
        .entries(entries)
        .generated(Instant.now().toString())
        .build();
  }

  @Override
  public Resource getChart(final ProtocolContext context, final String filename)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), HelmConstants.CHARTS_PATH + "/" + filename);
    final var classic = this.helmStorageService.getResource(storagePath, repoInfo.getName());
    if (classic.isPresent()) {
      return classic.get();
    }

    // A chart published only through the OCI route stores its archive as the chart layer, under
    // oci/blobs/<digest>, never under charts/ — the same fallback deleteChartFile already makes
    // (AbstractHelmStorageService#deleteChartFile). Without it, an OCI-only chart is listed in
    // index.yaml but 404s on the classic download route (RPS-1217).
    return this.findChartByArchiveFileName(repoInfo, filename)
        .flatMap(
            chart ->
                this.helmStorageService.getBlob(
                    repoInfo.getStorageKey(), chart.digest(), repoInfo.getName()))
        .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
  }

  /**
   * Resolves {@code <name>-<version>.tgz} back to the chart row it names. A chart name may itself
   * contain hyphens, so splitting the filename on the last {@code -} is ambiguous; matching against
   * the rows instead is correct by construction, using exactly the relation {@link #generateIndex}
   * used to build the URL in the first place.
   */
  private Optional<HelmChartInfo> findChartByArchiveFileName(
      final BaseRepoInfo<ID> repoInfo, final String filename) {
    return this.chartService.findAllByRepoId(repoInfo.getId()).stream()
        .filter(
            chart ->
                filename.equals(chart.name() + "-" + chart.version() + HelmConstants.TGZ_EXTENSION))
        .findFirst();
  }

  @Override
  public HelmChartInfo pushChart(
      final ProtocolContext context,
      final String name,
      final String version,
      final String description,
      final String appVersion,
      final @Nullable String type,
      final String digest,
      final InputStream chartStream,
      final long size)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var form =
        HelmChartForm.builder()
            .name(name)
            .version(version)
            .description(description.isBlank() ? null : description)
            .appVersion(appVersion.isBlank() ? null : appVersion)
            .type(type)
            .digest(digest)
            .size(size)
            .build();

    final var storagePath =
        StoragePath.of(
            repoInfo.getStorageKey(), this.helmStorageService.getChartRelativePath(name, version));

    return this.chartService.publish(
        repoInfo.getId(),
        form,
        repoInfo.isAllowOverride(),
        replaced -> this.storeChart(context, repoInfo, storagePath, chartStream, form, replaced));
  }

  /**
   * Stores the chart file while {@link ChartService#publish} still holds the version's row: a
   * failure rolls the row back, and for a new version the partly written file is removed. A version
   * being replaced keeps its row, so its file is left alone.
   */
  private void storeChart(
      final ProtocolContext context,
      final BaseRepoInfo<ID> repoInfo,
      final StoragePath storagePath,
      final InputStream chartStream,
      final HelmChartForm form,
      final @Nullable HelmChartInfo replaced) {

    try {
      this.helmStorageService.saveChart(repoInfo.getName(), storagePath, chartStream);
    } catch (final RuntimeException e) {
      if (replaced == null) {
        this.discardPartialChart(repoInfo, storagePath, e);
      }
      throw e;
    }

    context.addProperty(ARTIFACT_NAME, form.getName());
    context.addProperty(ARTIFACT_VERSION, form.getVersion());
    context.addProperty(STORAGE_PATH, storagePath.getRelativePath().getPath());
    context.addProperty(
        "usages", BaseUsages.ofDisk(form.getSize() - (replaced == null ? 0 : replaced.size())));
  }

  private void discardPartialChart(
      final BaseRepoInfo<ID> repoInfo, final StoragePath storagePath, final Exception cause) {

    try {
      this.helmStorageService.deleteChart(storagePath, repoInfo.getName());
    } catch (final IOException | RuntimeException e) {
      // Nothing to delete when the failure came before the file was created.
      log.debug("No partial chart removed at {}: {}", storagePath, e.getMessage());
      cause.addSuppressed(e);
    }
  }

  @Override
  public void deleteChart(final ProtocolContext context, final String name, final String version)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var chartInfo =
        this.chartService.findByRepoIdAndNameAndVersion(repoInfo.getId(), name, version);

    final var manifests = this.ociManifestService.findAllByChartId(chartInfo.id());

    this.ociManifestService.deleteAllByChartId(chartInfo.id());

    this.chartService.delete(repoInfo.getId(), name, version);

    final var freed =
        this.chartFilesService.deleteFiles(
            repoInfo.getId(),
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            List.of(new DeletedChart(name, version, chartInfo.digest(), manifests)));

    context.addProperty("usages", BaseUsages.ofDisk(-freed));
  }

  @Override
  public UUID startBlobUpload(final ProtocolContext context) {
    return UUID.randomUUID();
  }

  @Override
  public long uploadBlobChunk(
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

  @Override
  public long getUploadSize(final ProtocolContext context, final UUID uploadId) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.helmStorageService
        .getBlob(repoInfo.getStorageKey(), uploadId.toString(), repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("blobNotFound"))
        .contentLength();
  }

  @Override
  @SneakyThrows
  public HelmOciBlobInfo finalizeBlob(
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
            .getBlob(repoInfo.getStorageKey(), digest, repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException("blobNotFound"));
    final var form =
        HelmOciBlobForm.builder()
            .digest(digest)
            .size(resource.contentLength())
            .mediaType(mediaType)
            .build();
    return this.ociBlobService.findOrCreate(form, repoInfo.getId());
  }

  /** Refuses an upload that does not hash to the digest the client claims for it. */
  private void verifyUploadDigest(
      final BaseRepoInfo<ID> repoInfo, final UUID uploadId, final String digest)
      throws IOException {
    final var upload =
        this.helmStorageService
            .getBlob(repoInfo.getStorageKey(), uploadId.toString(), repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException("blobNotFound"));

    if (!BlobDigests.matches(digest, upload.getInputStream())) {
      throw new BadRequestException("digestMismatch");
    }
  }

  @Override
  public Optional<HelmOciBlobInfo> checkBlob(final ProtocolContext context, final String digest) {
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

  @Override
  public Resource getBlob(final ProtocolContext context, final String digest) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.helmStorageService
        .getBlob(repoInfo.getStorageKey(), digest, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("blobNotFound"));
  }

  @Override
  public Optional<HelmOciManifestInfo> checkManifest(
      final ProtocolContext context, final String name, final String reference) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.ociManifestService.findByNameAndReference(repoInfo.getId(), name, reference);
  }

  @Override
  public HelmOciManifestInfo getManifest(
      final ProtocolContext context, final String name, final String reference) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.ociManifestService
        .findByNameAndReference(repoInfo.getId(), name, reference)
        .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));
  }

  @Override
  public Optional<HelmChartInfo> findChartByNameAndVersion(
      final ProtocolContext context, final String name, final String version) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.chartService.findOptionalByNameAndVersion(repoInfo.getId(), name, version);
  }

  @Override
  public HelmOciBlobInfo findOrCreateBlob(final HelmOciBlobForm form, final ID repoId) {
    return this.ociBlobService.findOrCreate(form, repoId);
  }

  /**
   * Writes the chart version, the manifest and the manifest file in the one transaction the caller
   * opened (RPS-1354). The chart row is locked first ({@link ChartService#findOrCreate}), which is
   * what makes pushes of one chart take turns (RPS-1273) and now also holds the turn until the
   * manifest is written. Both rows are flushed before the file is written, the order RPS-1124 set
   * for the classic upload: a row the database refuses never reaches storage, and a file that
   * cannot be written rolls the rows back. A brand-new manifest whose file fails has its partial
   * file removed; a manifest being replaced keeps its row, and so its file path, until the next
   * successful push.
   */
  @Override
  public HelmOciManifestPushResult pushManifest(
      final ProtocolContext context, final HelmOciManifestPushForm form, final byte[] contentBytes)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var chart = this.chartService.findOrCreate(form.getChart(), repoInfo.getId());
    // Looked up after the chart lock: a push that waited for another one must see the manifest
    // that one committed, not the row as it was before the wait.
    final var replaced =
        this.ociManifestService
            .findByNameAndReference(repoInfo.getId(), form.getName(), form.getReference())
            .isPresent();
    final var manifest =
        this.ociManifestService.save(
            HelmOciManifestForm.builder()
                .chartId(chart.id())
                .name(form.getName())
                .reference(form.getReference())
                .digest(form.getDigest())
                .mediaType(form.getMediaType())
                .content(form.getContent())
                .build(),
            repoInfo.getId());

    try {
      final var usages =
          this.helmStorageService.saveManifest(
              repoInfo.getStorageKey(),
              form.getName(),
              form.getReference(),
              contentBytes,
              repoInfo.getName());
      return new HelmOciManifestPushResult(manifest, usages);
    } catch (final RuntimeException e) {
      if (!replaced) {
        this.discardPartialManifest(repoInfo, form, e);
      }
      throw e;
    }
  }

  private void discardPartialManifest(
      final BaseRepoInfo<ID> repoInfo, final HelmOciManifestPushForm form, final Exception cause) {

    try {
      this.helmStorageService.deleteManifestFile(
          repoInfo.getStorageKey(), form.getName(), form.getReference(), repoInfo.getName());
    } catch (final IOException | RuntimeException e) {
      log.debug(
          "No partial manifest removed for {}:{}: {}",
          form.getName(),
          form.getReference(),
          e.getMessage());
      cause.addSuppressed(e);
    }
  }

  @Override
  public HelmOciTagListDto listTags(final ProtocolContext context, final String name) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    // listTagsByName's references include pushes by digest (sha256:...), which the distribution
    // spec's tags/list must not return, and are ordered newest-first, where the spec wants
    // lexical order. The filtering/sorting stays here rather than in OciManifestService because
    // the panel (HelmApiFacade) calls listTagsByName directly and relies on its raw, unfiltered,
    // createdAt-desc output (see HelmOciManifestNameRepairServiceIT).
    final var tags =
        this.ociManifestService.listTagsByName(repoInfo.getId(), name).stream()
            .filter(reference -> !reference.startsWith(HelmConstants.SHA256_PREFIX))
            .sorted()
            .toList();
    return HelmOciTagListDto.builder().name(name).tags(tags).build();
  }
}
