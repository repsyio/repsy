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
package io.repsy.os.server.security.shared.schedulers;

import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.trivy.ScanJobNotFoundException;
import io.repsy.os.server.security.scanner.trivy.ScanJobStatusResponse;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerProperties;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerStatusClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "repsy.security.scanner", havingValue = "enabled")
public class TrivyScanStatusPoller {

  private static final List<ScanStatus> ACTIVE_STATUSES =
      List.of(ScanStatus.PENDING, ScanStatus.QUEUED, ScanStatus.RUNNING);

  private final @NonNull VulnerabilityScanRepository vulnerabilityScanRepository;
  private final @NonNull VulnerabilityScanTxService scanTxService;
  private final @NonNull TrivyScannerStatusClient statusClient;
  private final @NonNull TrivyScannerProperties properties;

  @Scheduled(fixedDelayString = "${repsy.security.trivy.poll-interval-ms:3000}")
  public void pollActiveScans() {
    for (final var scan : this.vulnerabilityScanRepository.findAllByStatusIn(ACTIVE_STATUSES)) {
      this.pollScan(scan);
    }
  }

  private void pollScan(final @NonNull VulnerabilityScan scan) {
    if (this.hasExceededMaxDuration(scan)) {
      this.scanTxService.recordScanFailure(scan.getId(), "Scan exceeded maximum duration");
      return;
    }

    try {
      final var status = this.statusClient.fetchStatus(scan.getId());
      this.applyStatus(scan, status);
    } catch (final ScanJobNotFoundException exception) {
      this.handleJobNotFound(scan);
    } catch (final Exception exception) {
      log.debug("Failed to poll scan status for {}, will retry next tick", scan.getId(), exception);
    }
  }

  private void handleJobNotFound(final @NonNull VulnerabilityScan scan) {
    if (scan.getStatus() == ScanStatus.PENDING) {
      log.debug("Scan {} not yet submitted to scanner, will retry next tick", scan.getId());
      return;
    }

    this.scanTxService.recordScanFailure(scan.getId(), "Scanner restarted, job lost, please retry");
  }

  private void applyStatus(
      final @NonNull VulnerabilityScan scan, final @NonNull ScanJobStatusResponse status) {

    switch (status.status()) {
      case COMPLETED -> this.recordCompleted(scan.getId(), status);
      case FAILED -> this.scanTxService.recordScanFailure(scan.getId(), status.errorMessage());
      case QUEUED -> this.applyQueued(scan);
      case RUNNING -> this.applyRunning(scan);
    }
  }

  private void applyQueued(final @NonNull VulnerabilityScan scan) {
    if (scan.getStatus() == ScanStatus.PENDING) {
      this.scanTxService.markQueued(scan.getId());
    }
  }

  private void applyRunning(final @NonNull VulnerabilityScan scan) {
    if (scan.getStatus() != ScanStatus.RUNNING) {
      this.scanTxService.markRunning(scan.getId());
    }
  }

  private void recordCompleted(
      final @NonNull UUID scanId, final @NonNull ScanJobStatusResponse status) {

    final var result = status.result();

    if (result == null) {
      this.scanTxService.recordScanFailure(scanId, "Scanner reported completion without a result");
      return;
    }

    this.scanTxService.recordScanOutcome(scanId, result);
  }

  private boolean hasExceededMaxDuration(final @NonNull VulnerabilityScan scan) {
    final var referenceTime =
        scan.getStartedAt() != null ? scan.getStartedAt() : scan.getCreatedAt();
    final var maxDuration = Duration.ofSeconds(this.properties.maxScanDurationSeconds());

    return Instant.now().isAfter(referenceTime.plus(maxDuration));
  }
}
