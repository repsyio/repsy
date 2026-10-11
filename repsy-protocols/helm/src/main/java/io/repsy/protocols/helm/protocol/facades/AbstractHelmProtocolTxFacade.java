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

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import io.repsy.protocols.helm.shared.chart.services.AbstractHelmChartFilesService;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.Resource;

/**
 * The transactional persistence layer of the Helm protocol. It is the one class a product extends
 * (the backend's {@code HelmProtocolFacade}); the work is split by concern into {@link
 * HelmClassicChartFacade} (index and chart archives), {@link HelmOciBlobFacade} and {@link
 * HelmOciManifestFacade}, which this class delegates to. The constructor and the protected
 * collaborators are the contract of a subclass.
 */
public abstract class AbstractHelmProtocolTxFacade<ID> implements HelmProtocolFacade<ID> {

  protected final HelmStorageService<ID> helmStorageService;
  protected final ChartService<ID> chartService;
  protected final OciBlobService<ID> ociBlobService;
  protected final OciManifestService<ID> ociManifestService;
  protected final AbstractHelmChartFilesService<ID> chartFilesService;

  private final HelmClassicChartFacade<ID> classic;
  private final HelmOciBlobFacade<ID> blobs;
  private final HelmOciManifestFacade<ID> manifests;

  protected AbstractHelmProtocolTxFacade(
      final HelmStorageService<ID> helmStorageService,
      final ChartService<ID> chartService,
      final OciBlobService<ID> ociBlobService,
      final OciManifestService<ID> ociManifestService,
      final AbstractHelmChartFilesService<ID> chartFilesService) {

    this.helmStorageService = helmStorageService;
    this.chartService = chartService;
    this.ociBlobService = ociBlobService;
    this.ociManifestService = ociManifestService;
    this.chartFilesService = chartFilesService;

    this.classic =
        new HelmClassicChartFacade<>(
            helmStorageService, chartService, ociManifestService, chartFilesService);
    this.blobs = new HelmOciBlobFacade<>(helmStorageService, ociBlobService);
    this.manifests =
        new HelmOciManifestFacade<>(helmStorageService, chartService, ociManifestService);
  }

  @Override
  public HelmIndexInfo generateIndex(final ProtocolContext context) {
    return this.classic.generateIndex(context);
  }

  @Override
  public Resource getChart(final ProtocolContext context, final String filename)
      throws IOException {
    return this.classic.getChart(context, filename);
  }

  @Override
  public HelmChartInfo pushChart(
      final ProtocolContext context,
      final HelmChartMetadata metadata,
      final String digest,
      final InputStream chartStream,
      final long size)
      throws IOException {
    return this.classic.pushChart(context, metadata, digest, chartStream, size);
  }

  @Override
  public void deleteChart(final ProtocolContext context, final String name, final String version)
      throws IOException {
    this.classic.deleteChart(context, name, version);
  }

  @Override
  public Optional<HelmChartInfo> findChartByNameAndVersion(
      final ProtocolContext context, final String name, final String version) {
    return this.classic.findChartByNameAndVersion(context, name, version);
  }

  @Override
  public UUID startBlobUpload(final ProtocolContext context) {
    return this.blobs.startBlobUpload(context);
  }

  @Override
  public long uploadBlobChunk(
      final ProtocolContext context,
      final UUID uploadId,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    return this.blobs.uploadBlobChunk(context, uploadId, stream, contentLength);
  }

  @Override
  public long getUploadSize(final ProtocolContext context, final UUID uploadId) throws IOException {
    return this.blobs.getUploadSize(context, uploadId);
  }

  @Override
  public HelmOciBlobInfo finalizeBlob(
      final ProtocolContext context,
      final UUID uploadId,
      final String digest,
      final String mediaType,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    return this.blobs.finalizeBlob(context, uploadId, digest, mediaType, stream, contentLength);
  }

  @Override
  public Optional<HelmOciBlobInfo> checkBlob(final ProtocolContext context, final String digest) {
    return this.blobs.checkBlob(context, digest);
  }

  @Override
  public Resource getBlob(final ProtocolContext context, final String digest) throws IOException {
    return this.blobs.getBlob(context, digest);
  }

  @Override
  public HelmOciBlobInfo getOrCreateBlob(final HelmOciBlobForm form, final ID repoId) {
    return this.blobs.getOrCreateBlob(form, repoId);
  }

  @Override
  public Optional<HelmOciManifestInfo> checkManifest(
      final ProtocolContext context, final String name, final String reference) {
    return this.manifests.checkManifest(context, name, reference);
  }

  @Override
  public HelmOciManifestInfo getManifest(
      final ProtocolContext context, final String name, final String reference) throws IOException {
    return this.manifests.getManifest(context, name, reference);
  }

  @Override
  public HelmOciManifestPushResult pushManifest(
      final ProtocolContext context, final HelmOciManifestPushForm form, final byte[] contentBytes)
      throws IOException {
    return this.manifests.pushManifest(context, form, contentBytes);
  }

  @Override
  public HelmOciTagListInfo listTags(final ProtocolContext context, final String name) {
    return this.manifests.listTags(context, name);
  }
}
