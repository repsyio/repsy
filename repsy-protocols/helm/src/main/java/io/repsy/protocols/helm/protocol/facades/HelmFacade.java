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
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexDto;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListDto;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
public interface HelmFacade<ID> {

  HelmIndexDto generateIndex(ProtocolContext context);

  Resource getChart(ProtocolContext context, String filename) throws IOException;

  HelmChartInfo pushChart(
      ProtocolContext context,
      String name,
      String version,
      String description,
      String appVersion,
      @Nullable String type,
      String digest,
      InputStream chartStream,
      long size)
      throws IOException;

  void deleteChart(ProtocolContext context, String name, String version) throws IOException;

  // OCI methods

  UUID startBlobUpload(ProtocolContext context);

  long uploadBlobChunk(
      ProtocolContext context, UUID uploadId, InputStream stream, long contentLength)
      throws IOException;

  HelmOciBlobInfo finalizeBlob(
      ProtocolContext context,
      UUID uploadId,
      String digest,
      String mediaType,
      InputStream stream,
      long contentLength)
      throws IOException;

  Optional<HelmOciBlobInfo> checkBlob(ProtocolContext context, String digest);

  Resource getBlob(ProtocolContext context, String digest) throws IOException;

  Optional<HelmOciManifestInfo> checkManifest(
      ProtocolContext context, String name, String reference);

  HelmOciManifestInfo getManifest(ProtocolContext context, String name, String reference)
      throws IOException;

  HelmChartInfo findOrCreateChart(HelmChartForm form, ID repoId);

  HelmOciBlobInfo findOrCreateBlob(HelmOciBlobForm form, ID repoId);

  HelmOciManifestInfo findOrCreateManifest(HelmOciManifestForm form, ID repoId);

  void pushManifest(ProtocolContext context, String name, String reference, byte[] contentBytes)
      throws IOException;

  HelmOciTagListDto listTags(ProtocolContext context, String name);
}
