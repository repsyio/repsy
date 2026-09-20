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
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Removes the blob uploads that were started and never finalized, and releases the disk usage they
 * were charged for (RPS-1041).
 *
 * <p>A Docker layer or Helm OCI blob is written to a temp file named after the upload session
 * ({@code blobs/<uuid>} and {@code oci/blobs/<uuid>}) and renamed to its digest when the client
 * finalizes the upload. Both protocols charge the repo for the bytes as they are written, so a push
 * that is aborted half way (client crash, network drop) leaves a file on disk that nothing deletes
 * and bytes that stay charged to the repo.
 *
 * <p>A file counts as an abandoned upload when all of these hold:
 *
 * <ul>
 *   <li>it sits directly in the blobs directory and its name is a UUID: a finalized blob is named
 *       by its digest ({@code sha256:...}), so it never matches;
 *   <li>it was last written before the configured TTL: an upload that is still receiving bytes
 *       keeps a fresh modification time, so it is left alone;
 *   <li>for Docker, no layer row carries that UUID as its id: a layer stored under its own id
 *       (imported from an older storage layout) is a finalized layer, not an upload.
 * </ul>
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

  private final @NonNull RepoRepository repoRepository;
  private final @NonNull LayerRepository layerRepository;
  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull HelmStorageService helmStorageService;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull Duration ttl;

  private final ReentrantLock passLock = new ReentrantLock();

  public AbandonedBlobUploadCleanupService(
      final @NonNull RepoRepository repoRepository,
      final @NonNull LayerRepository layerRepository,
      final @NonNull DockerStorageService dockerStorageService,
      final @NonNull HelmStorageService helmStorageService,
      final @NonNull UsageUpdateService usageUpdateService,
      final @Value("${repsy.storage.abandoned-upload-cleanup.ttl:PT24H}") @NonNull Duration ttl) {
    this.repoRepository = repoRepository;
    this.layerRepository = layerRepository;
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

    var released = 0L;

    for (final var file : this.listStaleBlobFiles(repo, notModifiedSince)) {
      if (!this.isAbandonedUpload(repo, file)) {
        continue;
      }

      try {
        released += this.deleteBlobFile(repo, file);
      } catch (final IOException | RuntimeException e) {
        log.warn("Failed to delete abandoned upload {} of repo {}", file.name(), repo.getId(), e);
      }
    }

    if (released > 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-released)));
    }

    return released;
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

  private boolean isAbandonedUpload(final @NonNull Repo repo, final @NonNull StaleFile file) {

    if (!UPLOAD_SESSION_NAME.matcher(file.name()).matches()) {
      return false;
    }

    return repo.getType() != RepoType.DOCKER
        || !this.layerRepository.existsByIdAndRepoId(UUID.fromString(file.name()), repo.getId());
  }
}
