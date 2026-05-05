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
package io.repsy.os.server.protocols.helm.protocol.facades;

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.facades.AbstractHelmProtocolTxFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexDto;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service("helmProtocolTxFacade")
@Transactional(readOnly = true)
@NullMarked
public class HelmProtocolTxFacade extends AbstractHelmProtocolTxFacade<UUID> {

  public HelmProtocolTxFacade(
      final HelmStorageService<UUID> helmStorageServiceImpl,
      final ChartService<UUID> helmChartService,
      final OciBlobService<UUID> helmOciBlobService,
      final OciManifestService<UUID> helmOciManifestService) {
    super(helmStorageServiceImpl, helmChartService, helmOciBlobService, helmOciManifestService);
  }

  @Override
  public HelmIndexDto generateIndex(final ProtocolContext context) {
    return super.generateIndex(context);
  }

  @Override
  public Resource getChart(final ProtocolContext context, final String filename)
      throws IOException {
    return super.getChart(context, filename);
  }

  @Override
  @Transactional
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
    return super.pushChart(
        context, name, version, description, appVersion, type, digest, chartStream, size);
  }

  @Override
  @Transactional
  public void deleteChart(final ProtocolContext context, final String name, final String version)
      throws IOException {
    super.deleteChart(context, name, version);
  }

  @Override
  public UUID startBlobUpload(final ProtocolContext context) {
    return UuidCreator.getTimeOrderedEpoch();
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public long uploadBlobChunk(
      final ProtocolContext context,
      final UUID uploadId,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    return super.uploadBlobChunk(context, uploadId, stream, contentLength);
  }

  @Override
  @Transactional
  public HelmOciBlobInfo finalizeBlob(
      final ProtocolContext context,
      final UUID uploadId,
      final String digest,
      final String mediaType,
      final InputStream stream,
      final long contentLength)
      throws IOException {
    return super.finalizeBlob(context, uploadId, digest, mediaType, stream, contentLength);
  }

  @Override
  @Transactional
  public HelmChartInfo findOrCreateChart(final HelmChartForm form, final UUID repoId) {
    return super.findOrCreateChart(form, repoId);
  }

  @Override
  @Transactional
  public HelmOciBlobInfo findOrCreateBlob(final HelmOciBlobForm form, final UUID repoId) {
    return super.findOrCreateBlob(form, repoId);
  }

  @Override
  @Transactional
  public HelmOciManifestInfo findOrCreateManifest(
      final HelmOciManifestForm form, final UUID repoId) {
    return super.findOrCreateManifest(form, repoId);
  }

  @Override
  @Transactional
  public void pushManifest(
      final ProtocolContext context,
      final String name,
      final String reference,
      final byte[] contentBytes)
      throws IOException {
    super.pushManifest(context, name, reference, contentBytes);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Resource getBlob(final ProtocolContext context, final String digest) throws IOException {
    return super.getBlob(context, digest);
  }
}
