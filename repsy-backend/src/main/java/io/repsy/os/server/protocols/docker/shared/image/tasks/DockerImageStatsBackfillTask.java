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
package io.repsy.os.server.protocols.docker.shared.image.tasks;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import io.repsy.os.server.protocols.docker.shared.image.services.DockerImageStatsBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link DockerImageStatsBackfillService} on a schedule. The pass only reads rows already
 * in the database, so unlike {@code DockerManifestLayoutRepairTask} it does not need the {@code
 * maintenanceTaskExecutor}, but it still runs off the scheduler thread so a large batch never
 * delays the other periodic jobs.
 *
 * <p>The first pass waits {@code initial-delay} after startup so it does not compete with the
 * application coming up; it then repeats at the {@code interval}, which costs one query once
 * nothing is left to backfill. Set {@code repsy.docker.image-stats-backfill.enabled} to {@code
 * false} to switch the job off: an affected image keeps showing size 0 and no digest until it is
 * pushed again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
@ConditionalOnProperty(
    name = "repsy.docker.image-stats-backfill.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DockerImageStatsBackfillTask {

  private final DockerImageStatsBackfillService backfillService;

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @Scheduled(
      initialDelayString = "${repsy.docker.image-stats-backfill.initial-delay:PT10M}",
      fixedDelayString = "${repsy.docker.image-stats-backfill.interval:PT24H}")
  public void backfill() {

    try {
      final var report = this.backfillService.backfill();

      if (!report.isEmpty()) {
        log.info(
            "Docker image stats backfill: {} images refreshed, {} failed and will be retried",
            report.refreshed(),
            report.failed());
      }
    } catch (final RuntimeException e) {
      log.error("Could not backfill the size and digest of the Docker images", e);
    }
  }
}
