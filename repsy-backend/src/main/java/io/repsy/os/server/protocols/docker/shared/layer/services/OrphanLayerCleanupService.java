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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Deletes the blobs of Docker layers whose rows were already removed, and releases the disk usage
 * they were charged for (RPS-1098).
 *
 * <p>The layer rows are gone before this runs, so a blob that fails to delete cannot be found again
 * by a later run. A failure on one blob is therefore logged and skipped: the remaining blobs are
 * still deleted, and the bytes that were actually freed are still released from the repo's usage.
 * Nothing is rethrown, because the method is {@code @Async} and an exception would only reach the
 * uncaught-exception handler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanLayerCleanupService {

  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull UsageUpdateService usageUpdateService;

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  public void cleanupBlobs(
      final @NonNull UUID repoId, final @NonNull List<OrphanLayerInfo> orphans) {

    var deleted = 0;
    var failed = 0;
    var freedBytes = 0L;

    for (final var orphan : orphans) {
      try {
        this.dockerStorageService.deleteBlob(repoId, orphan.digest());
        deleted++;
        freedBytes += orphan.size();
      } catch (final Exception e) {
        // deleteBlob declares no checked exception but the storage layer rethrows IOException
        // (for example NoSuchFileException) sneakily, so Exception is the narrowest type that
        // catches every failure.
        failed++;
        log.warn("Failed to delete orphan blob {} of repo {}", orphan.digest(), repoId, e);

        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    if (failed > 0) {
      log.warn(
          "Deleted {} of {} orphan blobs of repo {}, {} failed or were skipped",
          deleted,
          orphans.size(),
          repoId,
          orphans.size() - deleted);
    }

    if (freedBytes > 0) {
      final var usages = BaseUsages.builder().diskUsage(-1L * freedBytes).build();
      this.usageUpdateService.updateUsage(new UsageChangedInfo(repoId, usages));
    }
  }
}
