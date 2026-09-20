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
package io.repsy.protocols.helm.shared.chart.services;

import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes the files a deleted chart version leaves on disk, and answers the bytes that freed.
 *
 * <p>A Helm OCI push stores more than the chart archive: every tag is a manifest file, and next to
 * the chart layer the client uploads a config blob (and a provenance layer, when it signs). The
 * chart layer and the manifest files belong to the chart version. A config or provenance blob is
 * addressed by its digest, so two charts can share one, and it is removed only when no remaining
 * manifest of the repo still names it.
 */
@Slf4j
@NullMarked
@RequiredArgsConstructor
public abstract class AbstractHelmChartFilesService<ID> {

  private static final Pattern SHA256_DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final HelmStorageService<ID> helmStorageService;
  private final ChartService<ID> chartService;
  private final OciBlobService<ID> ociBlobService;
  private final OciManifestService<ID> ociManifestService;

  /**
   * A chart version whose database rows are already deleted.
   *
   * @param name the chart name
   * @param version the chart version
   * @param digest the digest of the chart archive
   * @param manifests the manifests the version had, as they were before their rows were deleted
   */
  public record DeletedChart(
      String name, String version, String digest, List<HelmOciManifestInfo> manifests) {}

  /**
   * Deletes the archive, the manifest files and the blobs no manifest references any more of the
   * given chart versions. It has to run after the rows of the versions (chart and manifests) are
   * deleted, because the blobs still referenced are the ones a remaining manifest names.
   *
   * @return the bytes the deleted files held
   */
  public long deleteFiles(
      final ID repoId,
      final UUID storageKey,
      final String repoName,
      final Collection<DeletedChart> charts)
      throws IOException {

    long freed = 0;
    final Set<String> archiveDigests = new HashSet<>();
    final Set<String> candidateBlobs = new LinkedHashSet<>();

    for (final var chart : charts) {
      freed += this.deleteChartVersionFiles(repoId, storageKey, repoName, chart, candidateBlobs);
      archiveDigests.add(chart.digest());
    }

    // The archive goes with its chart, above; only the blobs beside it can be shared.
    candidateBlobs.removeAll(archiveDigests);
    final var stillReferenced = this.findStillReferenced(repoId, candidateBlobs);
    for (final var digest : candidateBlobs) {
      if (stillReferenced.contains(digest)) {
        log.debug("Keeping OCI blob {}, another manifest still references it", digest);
        continue;
      }
      freed += this.helmStorageService.deleteBlob(storageKey, digest, repoName);
      this.ociBlobService.deleteByRepoIdAndDigest(repoId, digest);
    }

    return freed;
  }

  /**
   * Deletes the archive and the manifest files of one chart version, adds the digests its manifests
   * name to {@code referencedBlobs} and drops the row of its archive blob.
   */
  private long deleteChartVersionFiles(
      final ID repoId,
      final UUID storageKey,
      final String repoName,
      final DeletedChart chart,
      final Set<String> referencedBlobs)
      throws IOException {
    final var filename = chart.name() + "-" + chart.version() + HelmConstants.TGZ_EXTENSION;
    long freed =
        this.helmStorageService.deleteChartFile(storageKey, filename, chart.digest(), repoName);

    for (final var manifest : chart.manifests()) {
      freed +=
          this.helmStorageService.deleteManifestFile(
              storageKey, manifest.name(), manifest.reference(), repoName);
      referencedBlobs.addAll(referencedDigests(manifest.content()));
    }

    if (!chart.manifests().isEmpty()
        && !this.chartService.existsByRepoIdAndDigest(repoId, chart.digest())) {
      this.ociBlobService.deleteByRepoIdAndDigest(repoId, chart.digest());
    }
    return freed;
  }

  /**
   * The candidates a manifest of the repo still mentions. Any mention keeps a blob: a manifest is
   * matched as text, so an unusual manifest (an index, an annotation) can only keep too much, never
   * too little.
   */
  private Set<String> findStillReferenced(final ID repoId, final Set<String> candidates) {
    // A chart that stays may be stored under a digest a deleted manifest also named (a tag that was
    // pushed again for another chart), and its archive must not go with the manifest.
    final Set<String> referenced =
        candidates.stream()
            .filter(digest -> this.chartService.existsByRepoIdAndDigest(repoId, digest))
            .collect(Collectors.toCollection(HashSet::new));
    if (referenced.size() == candidates.size()) {
      return referenced;
    }

    try (final var contents = this.ociManifestService.streamContentByRepoId(repoId)) {
      final var iterator = contents.iterator();
      while (iterator.hasNext() && referenced.size() < candidates.size()) {
        final var content = iterator.next();
        candidates.stream().filter(content::contains).forEach(referenced::add);
      }
    }
    return referenced;
  }

  /**
   * The digests a manifest names: its config and every layer. A manifest that cannot be read, or an
   * entry without a well-formed digest, names nothing, so it never keeps or deletes the wrong blob.
   */
  static Set<String> referencedDigests(final String manifestContent) {
    final JsonNode manifest;
    try {
      manifest = OBJECT_MAPPER.readTree(manifestContent);
    } catch (final JacksonException e) {
      log.warn("Could not read the OCI manifest to find the blobs it references", e);
      return Set.of();
    }

    final Set<String> digests = new LinkedHashSet<>();
    addDigest(digests, manifest.get("config"));

    final var layers = manifest.get("layers");
    if (layers != null && layers.isArray()) {
      layers.forEach(layer -> addDigest(digests, layer));
    }
    return digests;
  }

  private static void addDigest(final Set<String> digests, final @Nullable JsonNode descriptor) {
    if (descriptor == null) {
      return;
    }
    final var digest = descriptor.get("digest");
    if (digest != null
        && digest.isString()
        && SHA256_DIGEST_PATTERN.matcher(digest.asString()).matches()) {
      digests.add(digest.asString());
    }
  }
}
