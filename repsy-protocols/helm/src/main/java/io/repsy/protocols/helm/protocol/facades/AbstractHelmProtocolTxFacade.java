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

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

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
    return this.helmStorageService
        .getResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("chartNotFound"));
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

    final var filename = name + "-" + version + HelmConstants.TGZ_EXTENSION;
    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), HelmConstants.CHARTS_PATH + "/" + filename);

    final var existingOpt =
        this.chartService.findOptionalByNameAndVersion(repoInfo.getId(), name, version);

    if (existingOpt.isPresent()) {
      if (!repoInfo.isAllowOverride()) {
        throw new ItemAlreadyExistException("chartAlreadyExists: " + name + ":" + version);
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

    // Soft-delete manifests before the chart so the chart FK join still resolves
    // when @SQLRestriction("DELETED = false") is applied. If the chart were deleted
    // first, the join in softDeleteByChartUuid would return no rows.
    this.ociManifestService.deleteAllByChartId(chartInfo.id());

    this.chartService.delete(repoInfo.getId(), name, version);

    final var filename = name + "-" + version + HelmConstants.TGZ_EXTENSION;
    final var freed =
        this.helmStorageService.deleteChartFile(
            repoInfo.getStorageKey(), filename, chartInfo.digest(), repoInfo.getName());

    for (final var manifest : manifests) {
      this.helmStorageService.deleteManifestFile(
          repoInfo.getStorageKey(), manifest.name(), manifest.reference(), repoInfo.getName());
    }

    if (!manifests.isEmpty()
        && !this.chartService.existsByRepoIdAndDigest(repoInfo.getId(), chartInfo.digest())) {
      this.ociBlobService.deleteByRepoIdAndDigest(repoInfo.getId(), chartInfo.digest());
    }

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
    this.helmStorageService.saveBlobChunk(
        repoInfo.getStorageKey(), uploadId, stream, repoInfo.getName());
    return this.helmStorageService.getBlobSize(
        repoInfo.getStorageKey(), uploadId, repoInfo.getName());
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
      this.helmStorageService.saveBlobChunk(
          repoInfo.getStorageKey(), uploadId, stream, repoInfo.getName());
    }
    this.helmStorageService.finalizeBlob(repoInfo.getStorageKey(), uploadId, digest);
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
    this.helmStorageService.saveManifest(
        repoInfo.getStorageKey(), name, reference, contentBytes, repoInfo.getName());
  }

  @Override
  public HelmOciTagListDto listTags(final ProtocolContext context, final String name) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var tags = this.ociManifestService.listTagsByName(repoInfo.getId(), name);
    return HelmOciTagListDto.builder().name(name).tags(tags).build();
  }
}
