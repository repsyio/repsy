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
package io.repsy.os.server.protocols.docker.shared.layer.services;

import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deletes the layers no manifest uses and schedules their blobs for deletion. It lives next to the
 * layers so that both the UI facade and the cleanup policies can call it without depending on each
 * other.
 */
@Service
@RequiredArgsConstructor
public class OrphanLayerService {

  private final @NonNull LayerTxService layerTxService;
  private final @NonNull OrphanLayerCleanupService orphanLayerCleanupService;

  /**
   * Deletes the layers no manifest uses and schedules their blobs for deletion.
   *
   * @return The layers whose rows are gone and whose blobs are being deleted in the background
   */
  public @NonNull List<OrphanLayerInfo> deleteOrphanLayers(final @NonNull RepoInfo repoInfo) {

    // The rows go first so a concurrent push cannot re-reference a row whose blob is about to be
    // deleted. The price: a blob whose delete fails stays on disk, still charged to the repo and
    // unreachable from the DB. AbandonedBlobUploadCleanupService sweeps it once it is older than
    // the TTL: it also collects a digest-named blob with no docker_layer row, not just UUID-named
    // upload files (RPS-1172).
    final var orphans = this.layerTxService.deleteOrphanLayers(repoInfo.getStorageKey());

    // The blobs are deleted on another thread, so only once the row deletion has committed: were
    // it to roll back, the rows would come back without their blobs (RPS-1318).
    this.afterCommit(
        () -> this.orphanLayerCleanupService.cleanupBlobs(repoInfo.getStorageKey(), orphans));

    return orphans;
  }

  /** Runs the action when the current transaction commits, and never if it rolls back. */
  private void afterCommit(final @NonNull Runnable action) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();

      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
