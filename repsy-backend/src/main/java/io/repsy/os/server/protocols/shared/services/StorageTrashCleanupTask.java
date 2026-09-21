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

import io.repsy.libs.storage.core.services.StorageStrategy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Empties the trash of the filesystem storage on a schedule. Deleting a repo, a package or a
 * version moves its files into a per-day directory of the protocol's {@code trash} directory, and
 * nothing else ever removes them: without this job the disk keeps growing after usage counters say
 * the space was freed.
 *
 * <p>Every protocol keeps its trash in a directory of its own, so {@link
 * StorageStrategy#clearTrash()} is called once per strategy. It is {@code @Async} on the {@code
 * maintenanceTaskExecutor} and the strategy beans are proxied, so this method only submits one task
 * per protocol and returns: it does not hold the single scheduler thread that the other periodic
 * jobs share, and it needs no {@code @Async} of its own. What is removed is decided by {@code
 * os.app.storage.file-system.trash-retention} ({@code StorageTrashProperties}); the first run after
 * an upgrade deletes the whole backlog that is older than that.
 *
 * <p>The application runs on a single node (RPS-410) and a pass is idempotent per trash directory
 * (a directory that is already gone is simply not listed), so two overlapping passes need no lock.
 *
 * <p>The first pass waits {@code initial-delay} after startup so it does not compete with the
 * application coming up. Set {@code repsy.storage.trash-cleanup.enabled} to {@code false} to switch
 * the job off.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "repsy.storage.trash-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StorageTrashCleanupTask {

  private final @NonNull Map<String, StorageStrategy> storageStrategiesByRepoType;

  public StorageTrashCleanupTask(
      final @Qualifier("storageStrategiesByRepoType") @NonNull Map<String, StorageStrategy>
              storageStrategiesByRepoType) {

    this.storageStrategiesByRepoType = storageStrategiesByRepoType;
  }

  @Scheduled(
      initialDelayString = "${repsy.storage.trash-cleanup.initial-delay:PT15M}",
      fixedDelayString = "${repsy.storage.trash-cleanup.interval:PT24H}")
  public void cleanup() {

    log.info(
        "triggering trash cleanup for {} storage paths", this.storageStrategiesByRepoType.size());

    this.storageStrategiesByRepoType.forEach(
        (repoType, strategy) -> {
          try {
            strategy.clearTrash();
          } catch (final RuntimeException e) {
            log.warn("could not trigger the trash cleanup of {} storage", repoType, e);
          }
        });
  }
}
