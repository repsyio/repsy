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

import io.repsy.scanner.trivy.services.TrivyDatabaseMetadata;
import io.repsy.scanner.trivy.services.TrivyScanService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the Trivy databases current. The advisory lookup runs with {@code --skip-db-update} (it
 * must be quick and work offline), so without scan traffic, which updates the databases lazily,
 * they would go stale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrivyDatabaseRefreshTask {

  private final @NonNull TrivyScanService trivyScanService;
  private final @NonNull TrivyDatabaseMetadata databaseMetadata;

  // The startup warm-up has just refreshed them, so the first run is one interval away.
  @Scheduled(
      initialDelayString = "${scanner.trivy.db-refresh-interval}",
      fixedDelayString = "${scanner.trivy.db-refresh-interval}")
  public void refresh() {
    if (!this.databaseMetadata.isRefreshDue(Instant.now())) {
      log.debug("Trivy databases are not past their NextUpdate, nothing to refresh");
      return;
    }

    this.download();
  }

  // Without a database no lookup can be answered, and waiting a whole refresh interval for the
  // next chance (the download at startup failed, for example) is too long.
  @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
  public void downloadWhenMissing() {
    if (this.databaseMetadata.vulnerabilityDb().isEmpty()) {
      this.download();
    }
  }

  private void download() {
    try {
      log.info("Refreshing the Trivy vulnerability databases");
      this.trivyScanService.refreshDatabases();
      log.info("Trivy database refresh completed");
    } catch (final RuntimeException exception) {
      // A failed download leaves the databases that are on disk in place: trivy replaces a file
      // only once the new one has been downloaded.
      log.warn("Trivy database refresh failed; keeping the current databases", exception);
    }
  }
}
