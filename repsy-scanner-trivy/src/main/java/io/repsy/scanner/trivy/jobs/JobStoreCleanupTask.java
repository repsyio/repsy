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
package io.repsy.scanner.trivy.jobs;

import io.repsy.scanner.trivy.config.ScannerJobProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobStoreCleanupTask {

  private final @NonNull JobStore jobStore;
  private final @NonNull ScannerJobProperties properties;

  @Scheduled(fixedDelayString = "${scanner.job-retention-check-interval-ms:600000}")
  public void purgeExpiredJobs() {

    final var threshold =
        Instant.now().minus(this.properties.jobRetentionMinutes(), ChronoUnit.MINUTES);

    log.debug("Purging completed scan jobs older than {}", threshold);
    this.jobStore.removeCompletedBefore(threshold);
  }
}
