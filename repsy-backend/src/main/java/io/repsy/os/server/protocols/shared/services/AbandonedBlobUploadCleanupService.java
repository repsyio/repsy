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
import io.repsy.os.server.protocols.shared.sources.AbandonedBlobUploadSource;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull List<AbandonedBlobUploadSource> sources;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull Duration ttl;

  private final ReentrantLock passLock = new ReentrantLock();

  public AbandonedBlobUploadCleanupService(
      final @NonNull RepoTxService repoTxService,
      final @NonNull List<AbandonedBlobUploadSource> sources,
      final @NonNull UsageUpdateService usageUpdateService,
      final @Value("${repsy.storage.abandoned-upload-cleanup.ttl:PT24H}") @NonNull Duration ttl) {
    this.repoTxService = repoTxService;
    this.sources =
        sources.stream()
            .sorted(Comparator.comparing((AbandonedBlobUploadSource s) -> s.repoType().name()))
            .toList();
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
      var released = 0L;

      for (final var source : this.sources) {
        released += this.cleanupRepos(source, notModifiedSince);
      }

      if (released > 0) {
        log.info("Released {} bytes held by abandoned blob uploads", released);
      }

      return released;
    } finally {
      this.passLock.unlock();
    }
  }

  private long cleanupRepos(
      final @NonNull AbandonedBlobUploadSource source, final @NonNull Instant notModifiedSince) {

    var released = 0L;

    for (final var repo : this.repoTxService.findReposByType(source.repoType())) {
      try {
        released += this.cleanupRepo(source, repo, notModifiedSince);
      } catch (final RuntimeException e) {
        log.warn("Failed to clean up the abandoned blob uploads of repo {}", repo.getId(), e);
      }
    }

    return released;
  }

  private long cleanupRepo(
      final @NonNull AbandonedBlobUploadSource source,
      final @NonNull Repo repo,
      final @NonNull Instant notModifiedSince) {

    final var files = source.listStaleBlobFiles(repo.getId(), notModifiedSince);

    if (files.isEmpty()) {
      return 0L;
    }

    final var collectable = source.collectableIn(repo.getId());

    var released = 0L;
    for (final var file : files) {
      released += this.collectIfNeeded(source, repo, file, collectable);
    }

    if (released > 0) {
      this.usageUpdateService.updateUsage(
          new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-released)));
    }

    return released;
  }

  private long collectIfNeeded(
      final @NonNull AbandonedBlobUploadSource source,
      final @NonNull Repo repo,
      final @NonNull StaleFile file,
      final @NonNull Predicate<StaleFile> collectable) {

    if (!collectable.test(file)) {
      return 0L;
    }

    try {
      return source.deleteBlobFile(repo.getId(), repo.getName(), file.name());
    } catch (final IOException | RuntimeException e) {
      log.warn("Failed to delete abandoned upload {} of repo {}", file.name(), repo.getId(), e);
      return 0L;
    }
  }
}
