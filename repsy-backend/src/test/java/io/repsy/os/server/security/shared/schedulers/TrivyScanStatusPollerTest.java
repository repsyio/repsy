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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.dtos.ScanOutcome;
import io.repsy.os.server.security.scanner.trivy.ScanJobNotFoundException;
import io.repsy.os.server.security.scanner.trivy.ScanJobStatus;
import io.repsy.os.server.security.scanner.trivy.ScanJobStatusResponse;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerProperties;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerStatusClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrivyScanStatusPollerTest {

  private static final long MAX_SCAN_DURATION_SECONDS = 600;

  private VulnerabilityScanRepository repository;
  private VulnerabilityScanTxService txService;
  private TrivyScannerStatusClient statusClient;
  private TrivyScanStatusPoller poller;

  @BeforeEach
  void setUp() {
    this.repository = mock(VulnerabilityScanRepository.class);
    this.txService = mock(VulnerabilityScanTxService.class);
    this.statusClient = mock(TrivyScannerStatusClient.class);
    this.poller =
        new TrivyScanStatusPoller(
            this.repository,
            this.txService,
            this.statusClient,
            new TrivyScannerProperties(
                "http://scanner", "key", 5, 3000, MAX_SCAN_DURATION_SECONDS));
  }

  @Test
  void completedShouldRecordTheOutcome() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    final var outcome = new ScanOutcome(List.of(), "0.1");
    this.scannerReports(scan, ScanJobStatus.COMPLETED, outcome, null);

    this.poller.pollActiveScans();

    verify(this.txService).recordScanOutcome(scan.getId(), outcome);
  }

  @Test
  void completedWithoutAResultShouldRecordAFailure() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    this.scannerReports(scan, ScanJobStatus.COMPLETED, null, null);

    this.poller.pollActiveScans();

    verify(this.txService)
        .recordScanFailure(scan.getId(), "Scanner reported completion without a result");
  }

  @Test
  void failedShouldRecordTheScannerErrorMessage() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    this.scannerReports(scan, ScanJobStatus.FAILED, null, "image not found");

    this.poller.pollActiveScans();

    verify(this.txService).recordScanFailure(scan.getId(), "image not found");
  }

  @Test
  void queuedShouldMarkAPendingScanQueued() {

    final var scan = this.activeScan(ScanStatus.PENDING);
    this.scannerReports(scan, ScanJobStatus.QUEUED, null, null);

    this.poller.pollActiveScans();

    verify(this.txService).markQueued(scan.getId());
  }

  @Test
  void queuedShouldLeaveAScanThatIsAlreadyQueuedAlone() {

    final var scan = this.activeScan(ScanStatus.QUEUED);
    this.scannerReports(scan, ScanJobStatus.QUEUED, null, null);

    this.poller.pollActiveScans();

    verifyNoInteractions(this.txService);
  }

  @Test
  void runningShouldMarkAQueuedScanRunning() {

    final var scan = this.activeScan(ScanStatus.QUEUED);
    this.scannerReports(scan, ScanJobStatus.RUNNING, null, null);

    this.poller.pollActiveScans();

    verify(this.txService).markRunning(scan.getId());
  }

  @Test
  void runningShouldLeaveAScanThatIsAlreadyRunningAlone() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    this.scannerReports(scan, ScanJobStatus.RUNNING, null, null);

    this.poller.pollActiveScans();

    verifyNoInteractions(this.txService);
  }

  @Test
  void aMissingStatusShouldChangeNothingAndBeRetriedOnTheNextTick() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    this.scannerReports(scan, null, null, null);

    this.poller.pollActiveScans();

    verifyNoInteractions(this.txService);
  }

  @Test
  void aScannerErrorShouldChangeNothingAndBeRetriedOnTheNextTick() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    when(this.statusClient.fetchStatus(scan.getId())).thenThrow(new IllegalStateException("down"));

    this.poller.pollActiveScans();

    verifyNoInteractions(this.txService);
  }

  @Test
  void aJobTheScannerDoesNotKnowYetShouldBeWaitedForWhileThePendingScanIsNotSubmitted() {

    final var scan = this.activeScan(ScanStatus.PENDING);
    when(this.statusClient.fetchStatus(scan.getId()))
        .thenThrow(new ScanJobNotFoundException(scan.getId()));

    this.poller.pollActiveScans();

    verifyNoInteractions(this.txService);
  }

  @Test
  void aJobTheScannerHasLostShouldFailAScanThatWasAlreadySubmitted() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    when(this.statusClient.fetchStatus(scan.getId()))
        .thenThrow(new ScanJobNotFoundException(scan.getId()));

    this.poller.pollActiveScans();

    verify(this.txService)
        .recordScanFailure(scan.getId(), "Scanner restarted, job lost, please retry");
  }

  @Test
  void aScanOverTheMaximumDurationShouldFailWithoutAskingTheScanner() {

    final var scan = this.activeScan(ScanStatus.RUNNING);
    scan.setStartedAt(Instant.now().minusSeconds(MAX_SCAN_DURATION_SECONDS + 1));

    this.poller.pollActiveScans();

    verify(this.txService).recordScanFailure(scan.getId(), "Scan exceeded maximum duration");
    verify(this.statusClient, never()).fetchStatus(any());
    verifyNoMoreInteractions(this.txService);
  }

  private VulnerabilityScan activeScan(final ScanStatus status) {
    final var scan = new VulnerabilityScan();
    scan.setId(UUID.randomUUID());
    scan.setStatus(status);
    scan.setCreatedAt(Instant.now());
    when(this.repository.findAllByStatusIn(any())).thenReturn(List.of(scan));

    return scan;
  }

  private void scannerReports(
      final VulnerabilityScan scan,
      final ScanJobStatus status,
      final ScanOutcome result,
      final String errorMessage) {
    when(this.statusClient.fetchStatus(scan.getId()))
        .thenReturn(new ScanJobStatusResponse(scan.getId(), status, result, errorMessage));
  }
}
