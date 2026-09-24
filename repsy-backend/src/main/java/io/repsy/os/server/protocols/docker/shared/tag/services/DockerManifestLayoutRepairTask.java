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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link DockerManifestLayoutRepairService} on a schedule. The pass reads and renames the
 * files of the manifests an earlier version stored, so it runs on the {@code
 * maintenanceTaskExecutor} instead of holding the scheduler thread the other periodic jobs share.
 *
 * <p>The first pass waits {@code initial-delay} after startup so it does not compete with the
 * application coming up; it then repeats at the {@code interval}, which costs one query once
 * nothing is left to repair. Set {@code repsy.docker.manifest-layout-repair.enabled} to {@code
 * false} to switch the job off: the registry serves the legacy files either way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
@ConditionalOnProperty(
    name = "repsy.docker.manifest-layout-repair.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DockerManifestLayoutRepairTask {

  private final DockerManifestLayoutRepairService repairService;

  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @Scheduled(
      initialDelayString = "${repsy.docker.manifest-layout-repair.initial-delay:PT10M}",
      fixedDelayString = "${repsy.docker.manifest-layout-repair.interval:PT24H}")
  public void repair() {

    try {
      final var report = this.repairService.repair();

      if (!report.isEmpty()) {
        log.info(
            "Docker manifest layout repair: {} repaired, {} left as they are (no file matches"
                + " their digest), {} failed and will be retried",
            report.repaired(),
            report.unresolved(),
            report.failed());
      }
    } catch (final RuntimeException e) {
      log.error("Could not repair the layout of the Docker manifests", e);
    }
  }
}
