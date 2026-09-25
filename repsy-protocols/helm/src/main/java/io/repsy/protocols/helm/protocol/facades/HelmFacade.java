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
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexDto;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
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

  /**
   * Reports how many bytes of the upload session are written so far, so a {@code PATCH} chunk's
   * {@code Content-Range} can be checked against it and the upload-status endpoint can answer the
   * running {@code Range}.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException When no such upload
   *     session exists
   */
  long getUploadSize(ProtocolContext context, UUID uploadId) throws IOException;

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

  /**
   * Looks up an existing chart by its actual (name, version) identity -- the pair a chart push keys
   * on -- as opposed to {@link #checkManifest}, which looks up by the OCI manifest's own reference
   * (a tag OR a digest). A real OCI push is two separate manifest-push requests (one by digest, one
   * by the tag reference, both routed through the same handler), and only the tag-referenced one is
   * ever checked against {@code checkManifest}'s own by-reference lookup for an override refusal --
   * the digest-referenced one always looks "new" to that check (a fresh digest never already exists
   * as its own reference), so it is never gated by it. This lookup exists so the override check can
   * also run against the CHART's own identity, closing that gap for both push sub-requests
   * (RPS-1218).
   */
  Optional<HelmChartInfo> findChartByNameAndVersion(
      ProtocolContext context, String name, String version);

  HelmOciBlobInfo findOrCreateBlob(HelmOciBlobForm form, ID repoId);

  /**
   * Writes the chart version, the manifest that points at it and the manifest file as one unit
   * (RPS-1354), in a single transaction: the version row is written first, then the manifest row,
   * both flushed, and the file last. A failure anywhere rolls the rows back, so a manifest push
   * that does not succeed leaves the chart version row, the tag and the storage as they were. A
   * brand-new manifest whose file cannot be written also has its partial file removed, and a
   * transaction that rolls back after the file was written (a failing commit) has the file removed,
   * or the bytes of the replaced manifest put back (RPS-1366).
   *
   * <p>A push that loses a race on a row fails with {@code DataIntegrityViolationException} or
   * {@code OptimisticLockingFailureException}, and the whole unit is then safe to repeat. The
   * repeat belongs to the caller: it has to run outside this transaction, because the failed
   * persistence context cannot be reused.
   *
   * @return the committed manifest and the bytes its file added, to be reported once the call has
   *     returned
   */
  HelmOciManifestPushResult pushManifest(
      ProtocolContext context, HelmOciManifestPushForm form, byte[] contentBytes)
      throws IOException;

  HelmOciTagListDto listTags(ProtocolContext context, String name);
}
