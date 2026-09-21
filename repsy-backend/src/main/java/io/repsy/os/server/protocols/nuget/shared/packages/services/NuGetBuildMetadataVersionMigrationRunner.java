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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Migrates, on startup, the NuGet versions that were stored with build metadata to their canonical
 * version. A clean database costs one query and logs nothing. A failure is logged and does not stop
 * the application from starting, since the next start tries again.
 */
@Slf4j
@NullMarked
@Component
@RequiredArgsConstructor
public class NuGetBuildMetadataVersionMigrationRunner implements ApplicationRunner {

  private final NuGetBuildMetadataVersionMigrationService migrationService;

  @Override
  public void run(final ApplicationArguments args) {
    try {
      final var report = this.migrationService.migrate();
      if (report.isEmpty()) {
        return;
      }

      log.info(
          "NuGet versions stored with build metadata: {} migrated to their canonical version, {}"
              + " failed and are retried on the next start, {} left for an operator (the"
              + " canonical version already exists)",
          report.migrated(),
          report.failed(),
          report.conflicts().size());
    } catch (final RuntimeException e) {
      log.error("Could not migrate the NuGet versions stored with build metadata", e);
    }
  }
}
