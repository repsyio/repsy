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
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
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

    final var existingOpt =
        this.chartService.findOptionalByNameAndVersion(repoInfo.getId(), name, version);

    if (existingOpt.isPresent()) {
      if (!repoInfo.isAllowOverride()) {
        log.info("Chart {}:{} already exists in repo {}", name, version, repoInfo.getName());
        throw new ItemAlreadyExistException("chartAlreadyExists");
      }
      final var oldSize = existingOpt.get().size();
      final var chartInfo = this.chartService.update(repoInfo.getId(), form);
      this.helmStorageService.saveChart(repoInfo.getName(), storagePath, chartStream);
      context.addProperty(ARTIFACT_NAME, name);
      context.addProperty(ARTIFACT_VERSION, version);
      context.addProperty(STORAGE_PATH, storagePath.getRelativePath().getPath());
      context.addProperty("usages", BaseUsages.ofDisk(size - oldSize));
      return chartInfo;
    }

    final var chartInfo = this.chartService.findOrCreate(form, repoInfo.getId());
    this.helmStorageService.saveChart(repoInfo.getName(), storagePath, chartStream);
    context.addProperty(ARTIFACT_NAME, name);
    context.addProperty(ARTIFACT_VERSION, version);
    context.addProperty(STORAGE_PATH, storagePath.getRelativePath().getPath());
    context.addProperty("usages", BaseUsages.ofDisk(size));
    return chartInfo;
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
  public HelmChartInfo findOrCreateChart(final HelmChartForm form, final ID repoId) {
    return this.chartService.findOrCreate(form, repoId);
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

  @Override
  public HelmOciManifestInfo findOrCreateManifest(final HelmOciManifestForm form, final ID repoId) {
    return this.ociManifestService.save(form, repoId);
  }

  @Override
  public void pushManifest(
      final ProtocolContext context,
      final String name,
      final String reference,
      final byte[] contentBytes)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var usages =
        this.helmStorageService.saveManifest(
            repoInfo.getStorageKey(), name, reference, contentBytes, repoInfo.getName());
    ProtocolContextUtils.addUsages(context, usages);
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
