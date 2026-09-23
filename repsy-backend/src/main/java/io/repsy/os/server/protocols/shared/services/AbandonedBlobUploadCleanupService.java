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
package io.repsy.os.server.protocols.shared.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes the blob uploads that were started and never finalized (RPS-1041), and the finalized
 * blobs that ended up referenced by nothing (RPS-1112, RPS-1172), releasing the disk usage they
 * were charged for either way.
 *
 * <p>A Docker layer or Helm OCI blob is written to a temp file named after the upload session
 * ({@code blobs/<uuid>} and {@code oci/blobs/<uuid>}) and renamed to its digest ({@code
 * sha256:...}) when the client finalizes the upload. Both protocols charge the repo for the bytes
 * as they are written, so a push that is aborted half way (client crash, network drop, a refused
 * manifest) leaves a file on disk that nothing deletes and bytes that stay charged to the repo.
 *
 * <p>A stale file (last written before the configured TTL, so an upload still receiving bytes is
 * left alone) is collectable when:
 *
 * <ul>
 *   <li>its name is a UUID (still an upload session, not finalized) and, for Docker, no layer row
 *       carries that UUID as its id: a layer stored under its own id (imported from an older
 *       storage layout) is a finalized layer, not an upload;
 *   <li>its name is a digest and, for Docker, no {@code docker_layer} row of the repo carries that
 *       digest — whether because nothing ever referenced it, or because the row was already deleted
 *       by orphan-layer cleanup and only the blob delete failed;
 *   <li>its name is a digest and, for Helm, no manifest content of the repo mentions it and no
 *       chart version's own digest equals it either.
 * </ul>
 *
 * <p>Deleting a Helm digest blob this way also deletes its {@code helm_oci_blob} row, since nothing
 * else owns that cleanup for a blob no manifest ever reached.
 *
 * <p><b>Known race, accepted as-is:</b> a client that {@code HEAD}s a blob that already exists (so
 * it skips re-uploading it) and only then pushes the manifest referencing it could, in theory, have
 * that blob swept between the {@code HEAD} and the manifest push, if that gap exceeds the TTL. The
 * TTL (default {@code PT24H}) is already far longer than a normal push takes, so this is treated as
 * an extremely narrow, documented edge case rather than something to engineer around.
 *
 * <p>A pass never runs twice at once, and a failure on one file or repo is logged and skipped so it
 * cannot stop the rest of the pass.
 */
@Slf4j
@Service
public class AbandonedBlobUploadCleanupService {

  private static final Pattern UPLOAD_SESSION_NAME =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String DIGEST_FIELD_NAME = "digest";

  private final @NonNull RepoRepository repoRepository;
  private final @NonNull LayerRepository layerRepository;
  private final @NonNull HelmOciManifestRepository helmOciManifestRepository;
  private final @NonNull HelmOciBlobRepository helmOciBlobRepository;
  private final @NonNull HelmChartVersionRepository helmChartVersionRepository;
  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull HelmStorageService helmStorageService;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull Duration ttl;

  private final ReentrantLock passLock = new ReentrantLock();

  public AbandonedBlobUploadCleanupService(
      final @NonNull RepoRepository repoRepository,
      final @NonNull LayerRepository layerRepository,
      final @NonNull HelmOciManifestRepository helmOciManifestRepository,
      final @NonNull HelmOciBlobRepository helmOciBlobRepository,
      final @NonNull HelmChartVersionRepository helmChartVersionRepository,
      final @NonNull DockerStorageService dockerStorageService,
      final @NonNull HelmStorageService helmStorageService,
      final @NonNull UsageUpdateService usageUpdateService,
      final @Value("${repsy.storage.abandoned-upload-cleanup.ttl:PT24H}") @NonNull Duration ttl) {
    this.repoRepository = repoRepository;
    this.layerRepository = layerRepository;
    this.helmOciManifestRepository = helmOciManifestRepository;
    this.helmOciBlobRepository = helmOciBlobRepository;
    this.helmChartVersionRepository = helmChartVersionRepository;
    this.dockerStorageService = dockerStorageService;
    this.helmStorageService = helmStorageService;
    this.usageUpdateService = usageUpdateService;
    this.ttl = ttl;
  }

  /** Runs one pass over every Docker and Helm repo with the configured TTL. */
  public long cleanupAbandonedUploads() {

    return this.cleanupAbandonedUploads(Instant.now().minus(this.ttl));
  }

  /**
   * Runs one pass over every Docker and Helm repo.
   *
   * @param notModifiedSince an upload last written at or after this instant is still in progress
   * @return the bytes released from the repos' disk usage, {@code 0} when another pass is running
   */
  public long cleanupAbandonedUploads(final @NonNull Instant notModifiedSince) {

    if (!this.passLock.tryLock()) {
      log.debug("An abandoned blob upload cleanup is already running, skipping this one");
      return 0L;
    }

    try {
      final var released =
          this.cleanupRepos(RepoType.DOCKER, notModifiedSince)
              + this.cleanupRepos(RepoType.HELM, notModifiedSince);

      if (released > 0) {
        log.info("Released {} bytes held by abandoned blob uploads", released);
      }

      return released;
    } finally {
      this.passLock.unlock();
    }
  }

  private long cleanupRepos(final @NonNull RepoType type, final @NonNull Instant notModifiedSince) {

    var released = 0L;

    for (final var repo : this.repoRepository.findAllByTypeOrderByCreatedAtDescNameAsc(type)) {
      try {
        released += this.cleanupRepo(repo, notModifiedSince);
      } catch (final RuntimeException e) {
        log.warn("Failed to clean up the abandoned blob uploads of repo {}", repo.getId(), e);
      }
    }

    return released;
  }

  private long cleanupRepo(final @NonNull Repo repo, final @NonNull Instant notModifiedSince) {

    final var files = this.listStaleBlobFiles(repo, notModifiedSince);
    final var helmReferencedDigests = this.helmReferencedDigestsIfNeeded(repo, files);

    var released = 0L;
    for (final var file : files) {
      released += this.collectIfNeeded(repo, file, helmReferencedDigests);
    }

    if (released > 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-released)));
    }

    return released;
  }

  /**
   * The repo's referenced Helm digests, loaded once per repo per pass — only when the repo is Helm
   * and has at least one stale file candidate to check them against.
   */
  private @NonNull Set<String> helmReferencedDigestsIfNeeded(
      final @NonNull Repo repo, final @NonNull List<StaleFile> files) {

    return repo.getType() == RepoType.HELM && !files.isEmpty()
        ? this.loadHelmReferencedDigests(repo.getId())
        : Set.of();
  }

  private long collectIfNeeded(
      final @NonNull Repo repo,
      final @NonNull StaleFile file,
      final @NonNull Set<String> helmReferencedDigests) {

    if (!this.isCollectable(repo, file, helmReferencedDigests)) {
      return 0L;
    }

    try {
      final var freed = this.deleteBlobFile(repo, file);
      this.deleteHelmBlobRowIfNeeded(repo, file);
      return freed;
    } catch (final IOException | RuntimeException e) {
      log.warn("Failed to delete abandoned upload {} of repo {}", file.name(), repo.getId(), e);
      return 0L;
    }
  }

  private void deleteHelmBlobRowIfNeeded(final @NonNull Repo repo, final @NonNull StaleFile file) {

    if (repo.getType() == RepoType.HELM && !UPLOAD_SESSION_NAME.matcher(file.name()).matches()) {
      this.helmOciBlobRepository.deleteByRepoIdAndDigest(repo.getId(), file.name());
    }
  }

  private @NonNull List<StaleFile> listStaleBlobFiles(
      final @NonNull Repo repo, final @NonNull Instant notModifiedSince) {

    return repo.getType() == RepoType.DOCKER
        ? this.dockerStorageService.listStaleBlobFiles(repo.getId(), notModifiedSince)
        : this.helmStorageService.listStaleBlobFiles(repo.getId(), notModifiedSince);
  }

  private long deleteBlobFile(final @NonNull Repo repo, final @NonNull StaleFile file)
      throws IOException {

    return repo.getType() == RepoType.DOCKER
        ? this.dockerStorageService.deleteBlobFile(repo.getId(), repo.getName(), file.name())
        : this.helmStorageService.deleteBlobFile(repo.getId(), repo.getName(), file.name());
  }

  private boolean isCollectable(
      final @NonNull Repo repo,
      final @NonNull StaleFile file,
      final @NonNull Set<String> helmReferencedDigests) {

    if (UPLOAD_SESSION_NAME.matcher(file.name()).matches()) {
      return repo.getType() != RepoType.DOCKER
          || !this.layerRepository.existsByIdAndRepoId(UUID.fromString(file.name()), repo.getId());
    }

    // A digest-named file is a finalized blob. It is collectable once nothing references it any
    // more (RPS-1112 for Helm, RPS-1172 for Docker).
    return repo.getType() == RepoType.DOCKER
        ? !this.layerRepository.existsByRepoIdAndDigest(repo.getId(), file.name())
        : !helmReferencedDigests.contains(file.name());
  }

  /**
   * Every digest the repo's Helm OCI manifests or chart versions still reference, loaded once per
   * repo per pass rather than once per candidate file.
   */
  private @NonNull Set<String> loadHelmReferencedDigests(final @NonNull UUID repoId) {

    final var digests = new HashSet<String>();

    try (final var contents = this.helmOciManifestRepository.streamContentByRepoId(repoId)) {
      contents.forEach(content -> collectDigests(content, digests));
    }

    for (final var chartVersion : this.helmChartVersionRepository.findAllByChartRepoId(repoId)) {
      digests.add(chartVersion.getDigest());
    }

    return digests;
  }

  private static void collectDigests(
      final @NonNull String manifestJson, final @NonNull Set<String> into) {

    try {
      collectDigests(OBJECT_MAPPER.readTree(manifestJson), into);
    } catch (final JacksonException e) {
      log.warn("Failed to parse a Helm OCI manifest while sweeping unreferenced blobs", e);
    }
  }

  private static void collectDigests(
      final @NonNull JsonNode node, final @NonNull Set<String> into) {

    if (node.isObject()) {
      node.properties().forEach(entry -> collectDigestsFromProperty(entry, into));
    } else if (node.isArray()) {
      node.forEach(child -> collectDigests(child, into));
    }
  }

  private static void collectDigestsFromProperty(
      final Map.@NonNull Entry<String, JsonNode> property, final @NonNull Set<String> into) {

    if (DIGEST_FIELD_NAME.equals(property.getKey()) && property.getValue().isString()) {
      into.add(property.getValue().asString());
    } else {
      collectDigests(property.getValue(), into);
    }
  }
}
