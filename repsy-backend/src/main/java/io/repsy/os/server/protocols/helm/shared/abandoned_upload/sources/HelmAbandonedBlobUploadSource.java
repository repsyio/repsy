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
package io.repsy.os.server.protocols.helm.shared.abandoned_upload.sources;

import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.server.protocols.shared.sources.AbandonedBlobUploadSource;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Helm OCI blobs (RPS-1112): an upload-session file is always collectable, a digest file once no
 * manifest content of the repo mentions the digest and no chart version's own digest equals it.
 * Deleting a digest file also deletes its {@code helm_oci_blob} row, since nothing else owns that
 * cleanup for a blob no manifest ever reached.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelmAbandonedBlobUploadSource implements AbandonedBlobUploadSource {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String DIGEST_FIELD_NAME = "digest";

  private final HelmOciManifestRepository helmOciManifestRepository;
  private final HelmOciBlobRepository helmOciBlobRepository;
  private final HelmChartVersionRepository helmChartVersionRepository;
  private final HelmStorageService helmStorageService;

  @Override
  public RepoType repoType() {
    return RepoType.HELM;
  }

  @Override
  public List<StaleFile> listStaleBlobFiles(final UUID repoId, final Instant notModifiedSince) {
    return this.helmStorageService.listStaleBlobFiles(repoId, notModifiedSince);
  }

  @Override
  public Predicate<StaleFile> collectableIn(final UUID repoId) {

    final var referenced = this.loadReferencedDigests(repoId);

    return file ->
        UPLOAD_SESSION_NAME.matcher(file.name()).matches() || !referenced.contains(file.name());
  }

  @Override
  public long deleteBlobFile(final UUID repoId, final String repoName, final String fileName)
      throws IOException {

    final var freed =
        this.helmStorageService.deleteBlobFile(new RepoRef(repoId, repoName), fileName);

    if (!UPLOAD_SESSION_NAME.matcher(fileName).matches()) {
      this.helmOciBlobRepository.deleteByRepoIdAndDigest(repoId, fileName);
    }

    return freed;
  }

  /**
   * Every digest the repo's Helm OCI manifests or chart versions still reference, loaded once per
   * repo per pass rather than once per candidate file.
   */
  private Set<String> loadReferencedDigests(final UUID repoId) {

    final var digests = new HashSet<String>();

    try (final var contents = this.helmOciManifestRepository.streamContentByRepoId(repoId)) {
      contents.forEach(content -> collectDigests(content, digests));
    }

    for (final var chartVersion : this.helmChartVersionRepository.findAllByChartRepoId(repoId)) {
      digests.add(chartVersion.getDigest());
    }

    return digests;
  }

  private static void collectDigests(final String manifestJson, final Set<String> into) {

    try {
      collectDigests(OBJECT_MAPPER.readTree(manifestJson), into);
    } catch (final JacksonException e) {
      log.warn("Failed to parse a Helm OCI manifest while sweeping unreferenced blobs", e);
    }
  }

  private static void collectDigests(final JsonNode node, final Set<String> into) {

    if (node.isObject()) {
      node.properties().forEach(entry -> collectDigestsFromProperty(entry, into));
    } else if (node.isArray()) {
      node.forEach(child -> collectDigests(child, into));
    }
  }

  private static void collectDigestsFromProperty(
      final Map.Entry<String, JsonNode> property, final Set<String> into) {

    if (DIGEST_FIELD_NAME.equals(property.getKey()) && property.getValue().isString()) {
      into.add(property.getValue().asString());
    } else {
      collectDigests(property.getValue(), into);
    }
  }
}
