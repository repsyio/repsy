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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexInfo;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.storage.RepoRef;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.StoredUpload;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.core.io.Resource;

/**
 * The classic chart side of the Helm facade: {@code index.yaml}, the chart archives under {@code
 * charts/}, and the delete that also removes what the OCI route stored for a version. The OCI blob
 * and manifest sides are {@link HelmOciBlobFacade} and {@link HelmOciManifestFacade}.
 */
@Slf4j
@NullMarked
@RequiredArgsConstructor
final class HelmClassicChartFacade<ID> {

  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  private final HelmStorageService<ID> helmStorageService;
  private final ChartService<ID> chartService;
  private final OciManifestService<ID> ociManifestService;
  private final AbstractHelmChartFilesService<ID> chartFilesService;

  HelmIndexInfo generateIndex(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return HelmIndexUtils.buildIndex(this.chartService.findAllByRepoId(repoInfo.getId()));
  }

  Resource getChart(final ProtocolContext context, final String filename) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), HelmConstants.CHARTS_PATH + "/" + filename);
    final var classic = this.helmStorageService.findResource(storagePath, repoInfo.getName());
    if (classic.isPresent()) {
      return classic.get();
    }

    // A chart published only through the OCI route stores its archive as the chart layer, under
    // oci/blobs/<digest>, never under charts/ — the same fallback deleteChartFile already makes
    // (AbstractHelmStorageService#deleteChartFile). Without it, an OCI-only chart is listed in
    // index.yaml but 404s on the classic download route (RPS-1217).
    return this.findChartByArchiveFileName(repoInfo, filename)
        .flatMap(chart -> this.helmStorageService.findBlob(RepoRef.of(repoInfo), chart.digest()))
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.CHART_NOT_FOUND));
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

  HelmChartInfo pushChart(
      final ProtocolContext context,
      final HelmChartMetadata metadata,
      final String digest,
      final InputStream chartStream,
      final long size)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var name = metadata.getName();
    final var version = metadata.getVersion();
    final var form =
        HelmChartForm.builder()
            .name(name)
            .version(version)
            .description(blankToNull(metadata.getDescription()))
            .appVersion(blankToNull(metadata.getAppVersion()))
            .type(metadata.getType())
            .apiVersion(metadata.getApiVersion())
            .dependencies(metadata.getDependencies())
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

  private static @Nullable String blankToNull(final @Nullable String value) {
    return value == null || value.isBlank() ? null : value;
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

    StoredUpload.runOrDiscard(
        () -> this.helmStorageService.saveChart(repoInfo.getName(), storagePath, chartStream),
        () -> this.helmStorageService.deleteChart(storagePath, repoInfo.getName()),
        replaced != null,
        log,
        Level.DEBUG,
        "chart " + storagePath);

    context.addProperty(ARTIFACT_NAME, form.getName());
    context.addProperty(ARTIFACT_VERSION, form.getVersion());
    context.addProperty(STORAGE_PATH, storagePath.getRelativePath().getPath());
    ProtocolContextUtils.addUsages(
        context, BaseUsages.ofDisk(form.getSize() - (replaced == null ? 0 : replaced.size())));
  }

  void deleteChart(final ProtocolContext context, final String name, final String version)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    // The chart first, as a push takes it (RPS-1365), and before the version and its manifests are
    // read: what a push that was running commits is then part of what is deleted.
    this.chartService.lockChart(repoInfo.getId(), name);

    final var chartInfo =
        this.chartService.getByRepoIdAndNameAndVersion(repoInfo.getId(), name, version);

    final var manifests = this.ociManifestService.findAllByChartId(chartInfo.id());

    this.ociManifestService.deleteAllByChartId(chartInfo.id());

    this.chartService.delete(repoInfo.getId(), name, version);

    final var freed =
        this.chartFilesService.deleteFiles(
            repoInfo.getId(),
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            List.of(new DeletedChart(name, version, chartInfo.digest(), manifests)));

    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(-freed));
  }

  Optional<HelmChartInfo> findChartByNameAndVersion(
      final ProtocolContext context, final String name, final String version) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.chartService.findOptionalByNameAndVersion(repoInfo.getId(), name, version);
  }
}
