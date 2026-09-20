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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Repairs, on startup, the OCI manifests that were stored under a name other than their chart's
 * name. A clean database costs one query and logs nothing. A failure is logged and does not stop
 * the application from starting, since the next start tries again.
 */
@Slf4j
@NullMarked
@Component
@RequiredArgsConstructor
public class HelmOciManifestNameRepairRunner implements ApplicationRunner {

  private final HelmOciManifestNameRepairService helmOciManifestNameRepairService;

  @Override
  public void run(final ApplicationArguments args) {
    try {
      final var report = this.helmOciManifestNameRepairService.repair();
      if (report.isEmpty()) {
        return;
      }

      log.info(
          "Helm OCI manifests stored under a name other than their chart's: {} re-keyed, {}"
              + " duplicates removed, {} left for an operator (same tag under the chart name with a"
              + " different manifest)",
          report.rekeyed(),
          report.duplicatesRemoved(),
          report.conflicts().size());
    } catch (final RuntimeException e) {
      log.error("Could not repair the Helm OCI manifests stored under a mismatching name", e);
    }
  }
}
