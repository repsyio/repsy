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
package io.repsy.os.server.security.shared.listeners;

import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityScanner;
import io.repsy.os.server.security.scanner.dtos.ScanRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactScanListener {

  private final @NonNull VulnerabilityScanner scanner;
  private final @NonNull VulnerabilityScanTxService scanTxService;

  @Async("scanTaskExecutor")
  @EventListener
  public void handleArtifactPushed(final @NonNull ArtifactPushedEvent event) {

    if (event.artifactName() == null || event.artifactVersion() == null) {
      log.debug(
          "Skipping vulnerability scan for {}: artifact coordinate not available yet for this"
              + " protocol",
          event.repoType());
      return;
    }

    final var scanId =
        this.scanTxService.createPendingScan(
            event.repoId(),
            event.artifactName(),
            event.artifactVersion(),
            event.storagePath(),
            this.scanner.getName());

    this.scanTxService.markRunning(scanId);

    try {
      final var outcome =
          this.scanner.scan(
              new ScanRequest(
                  event.repoType(),
                  event.repoId(),
                  event.artifactName(),
                  event.artifactVersion(),
                  event.storagePath()));

      this.scanTxService.recordScanOutcome(scanId, outcome);
    } catch (final Exception exception) {
      log.error(
          "Vulnerability scan failed for {}@{}",
          event.artifactName(),
          event.artifactVersion(),
          exception);
      this.scanTxService.recordScanFailure(scanId, exception.getMessage());
    }
  }
}
