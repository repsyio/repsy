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
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService.DeletedChart;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexEntryInfo;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.helm.shared.utils.HelmVersionComparator;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.StoredUpload;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import org.slf4j.event.Level;
import org.springframework.core.io.Resource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractHelmProtocolTxFacade<ID> implements HelmProtocolFacade<ID> {

  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  /** Charts by name, the versions of a chart highest first (SemVer precedence, then the string). */
  private static final Comparator<HelmChartInfo> INDEX_ORDER =
      Comparator.comparing(HelmChartInfo::name)
          .thenComparing(HelmChartInfo::version, HelmVersionComparator.INSTANCE.reversed());

  private static final ObjectMapper DEPENDENCIES_MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> DEPENDENCIES_TYPE =
      new TypeReference<>() {};

  protected final HelmStorageService<ID> helmStorageService;
  protected final ChartService<ID> chartService;
  protected final OciBlobService<ID> ociBlobService;
  protected final OciManifestService<ID> ociManifestService;
  protected final AbstractHelmChartFilesService<ID> chartFilesService;

  @Override
  public HelmIndexInfo generateIndex(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    // The rows come back in the order the database keeps them, which changes with every update of
    // a row. The index is listed the way `helm repo index` writes it (RPS-1614): the charts by
    // name, the versions of a chart highest first.
    final var charts =
        this.chartService.findAllByRepoId(repoInfo.getId()).stream().sorted(INDEX_ORDER).toList();

    final Map<String, List<HelmIndexEntryInfo>> entries = new LinkedHashMap<>();

    for (final var chart : charts) {
      final var url =
          HelmConstants.CHARTS_PATH
              + "/"
              + chart.name()
              + "-"
              + chart.version()
              + HelmConstants.TGZ_EXTENSION;
      final var entry =
          HelmIndexEntryInfo.builder()
              .name(chart.name())
              .version(chart.version())
              .description(chart.description())
              .appVersion(chart.appVersion())
              .type(chart.type())
              .apiVersion(chart.apiVersion())
              .dependencies(decodeDependencies(chart))
              .digest(chart.digest())
              .urls(List.of(url))
              .created(chart.createdAt().toString())
              .build();
      entries.computeIfAbsent(chart.name(), _ -> new ArrayList<>()).add(entry);
    }

    return HelmIndexInfo.builder()
        .apiVersion(HelmConstants.API_VERSION)
        .entries(entries)
        .generated(Instant.now().toString())
        .build();
  }

  /**
   * The dependencies of a chart for its index entry. They were validated and serialised when the
   * chart was pushed, so a value that no longer reads back (a row edited by hand) costs the entry
   * its dependencies, not the whole index.
   */
  private static @Nullable List<Map<String, Object>> decodeDependencies(final HelmChartInfo chart) {
    final var json = chart.dependencies();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return DEPENDENCIES_MAPPER.readValue(json, DEPENDENCIES_TYPE);
    } catch (final JacksonException e) {
      log.warn(
          "Dependencies of chart {}:{} are not readable and are left out of the index: {}",
          chart.name(),
          chart.version(),
          e.getMessage());
      return null;
    }
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
    context.addProperty(
        "usages", BaseUsages.ofDisk(form.getSize() - (replaced == null ? 0 : replaced.size())));
  }

  @Override
  public void deleteChart(final ProtocolContext context, final String name, final String version)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    // The chart first, as a push takes it (RPS-1365), and before the version and its manifests are
    // read: what a push that was running commits is then part of what is deleted.
    this.chartService.lockChart(repoInfo.getId(), name);

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

    // What a replaced manifest's file held, to be put back when the transaction does not commit.
    final var previous = replaced ? this.readManifestFile(repoInfo, form) : null;

    return StoredUpload.storeOrDiscard(
        () -> {
          final var usages =
              this.helmStorageService.saveManifest(
                  repoInfo.getStorageKey(),
                  form.getName(),
                  form.getReference(),
                  contentBytes,
                  repoInfo.getName());
          this.undoManifestFileUnlessCommitted(repoInfo, form, contentBytes, previous, replaced);
          return new HelmOciManifestPushResult(manifest, usages);
        },
        () ->
            this.helmStorageService.deleteManifestFile(
                repoInfo.getStorageKey(), form.getName(), form.getReference(), repoInfo.getName()),
        replaced,
        log,
        Level.DEBUG,
        "manifest " + form.getName() + ":" + form.getReference());
  }

  /**
   * The file is the last write of the unit, so everything that fails before the commit rolls the
   * rows back without a file to undo. What can still fail is the commit itself (a lost connection,
   * a serialization failure at commit): the rows are then rolled back and the file, already
   * written, holds bytes no row describes (RPS-1366). This hook runs once the transaction is over
   * and, when it rolled back, removes the file of a new manifest or puts back the bytes a replaced
   * one had.
   *
   * <p>It runs after the database locks are released, so a later push of the same manifest may
   * already have written the file. The file is therefore touched only while it still holds the
   * bytes this unit wrote. When the outcome is unknown (the commit failed with a transaction
   * exception that says nothing about whether it took effect) the file is kept: a file no row
   * refers to is harmless, a missing one for a row that did commit is not.
   */
  private void undoManifestFileUnlessCommitted(
      final BaseRepoInfo<ID> repoInfo,
      final HelmOciManifestPushForm form,
      final byte[] written,
      final byte @Nullable [] previous,
      final boolean replaced) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(final int status) {
            if (status == STATUS_COMMITTED) {
              return;
            }

            if (status != STATUS_ROLLED_BACK) {
              log.warn(
                  "The outcome of the push of manifest {}:{} is unknown, its file is kept",
                  form.getName(),
                  form.getReference());
              return;
            }

            AbstractHelmProtocolTxFacade.this.undoManifestFile(
                repoInfo, form, written, previous, replaced);
          }
        });
  }

  private void undoManifestFile(
      final BaseRepoInfo<ID> repoInfo,
      final HelmOciManifestPushForm form,
      final byte[] written,
      final byte @Nullable [] previous,
      final boolean replaced) {

    try {
      final var current = this.readManifestFile(repoInfo, form);
      if (current == null || !Arrays.equals(current, written)) {
        // Removed, or already written again by a later push: not ours to touch.
        return;
      }

      if (previous != null) {
        this.helmStorageService.saveManifest(
            repoInfo.getStorageKey(),
            form.getName(),
            form.getReference(),
            previous,
            repoInfo.getName());
      } else if (!replaced) {
        this.helmStorageService.deleteManifestFile(
            repoInfo.getStorageKey(), form.getName(), form.getReference(), repoInfo.getName());
      }
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "The file of manifest {}:{} of a push that did not commit could not be undone: {}",
          form.getName(),
          form.getReference(),
          e.getMessage());
    }
  }

  private byte @Nullable [] readManifestFile(
      final BaseRepoInfo<ID> repoInfo, final HelmOciManifestPushForm form) {

    try {
      final var resource =
          this.helmStorageService.getManifest(
              repoInfo.getStorageKey(), form.getName(), form.getReference(), repoInfo.getName());
      if (resource.isEmpty() || !resource.get().exists()) {
        return null;
      }

      return resource.get().getContentAsByteArray();
    } catch (final IOException | RuntimeException e) {
      log.debug(
          "The file of manifest {}:{} could not be read: {}",
          form.getName(),
          form.getReference(),
          e.getMessage());
      return null;
    }
  }

  @Override
  public HelmOciTagListInfo listTags(final ProtocolContext context, final String name) {
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
    // The distribution spec's name is the whole repository name, <repo>/<chart> here, as the
    // Docker registry answers it (RPS-1489); the bare chart name was ambiguous between repos.
    return HelmOciTagListInfo.builder().name(repoInfo.getName() + "/" + name).tags(tags).build();
  }
}
