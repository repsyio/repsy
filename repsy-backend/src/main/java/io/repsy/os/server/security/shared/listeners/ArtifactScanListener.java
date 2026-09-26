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

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.ArtifactContent;
import io.repsy.os.server.security.scanner.ResourceArtifactContent;
import io.repsy.os.server.security.scanner.VulnerabilityScanner;
import io.repsy.os.server.security.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.server.security.scanner.dtos.ScanRequest;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerProperties;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactScanListener {

  private static final String DOCKER_REPO_TYPE = "DOCKER";
  private static final String NO_SCANNER_NAME = "none";
  private static final String SCAN_NOT_FOUND_MSG_ID = "vulnerabilityScanNotFound";

  private final @NonNull VulnerabilityScannerRegistry scannerRegistry;
  private final @NonNull VulnerabilityScanTxService scanTxService;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull DockerScanTokenIssuer dockerScanTokenIssuer;
  private final @NonNull TaskScheduler taskScheduler;
  private final @NonNull TrivyScannerProperties scannerProperties;

  @Qualifier("scanTaskExecutor")
  private final @NonNull Executor scanTaskExecutor;

  @Qualifier("storageStrategiesByRepoType")
  private final @NonNull Map<String, StorageStrategy> storageStrategiesByRepoType;

  @EventListener
  public void handleArtifactPushed(final @NonNull ArtifactPushedEvent event) {

    if (isArtifactCoordinateMissing(event)) {
      log.debug(
          "Skipping vulnerability scan for {}: artifact coordinate not available yet for this"
              + " protocol",
          event.repoType());
      return;
    }

    final var scanId = this.createPendingScanOrNull(event);

    if (scanId == null) {
      return;
    }

    this.submitScan(
        event,
        scanId,
        () -> {
          this.scanTxService.recordScanFailure(scanId, "Scan executor is saturated; retry later");
          log.warn(
              "Skipping vulnerability scan for {}@{} (repo={}): scan executor is saturated",
              event.artifactName(),
              event.artifactVersion(),
              event.repoName());
        });
  }

  @EventListener
  public void handleArtifactVersionDeleted(final @NonNull ArtifactVersionDeletedEvent event) {
    this.scanTxService.deleteScansForVersion(
        event.repoId(), event.artifactName(), event.artifactVersion());
  }

  public void executeManualScan(
      final @NonNull UUID scanId, final @NonNull ArtifactPushedEvent event) {
    this.submitScan(
        event,
        scanId,
        () -> {
          this.scanTxService.recordScanFailure(scanId, "Scan executor is saturated; retry later");
          throw new RetryableException("scanExecutorSaturated");
        });
  }

  private void submitScan(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull Runnable rejectionAction) {
    try {
      this.scanTaskExecutor.execute(() -> this.executeScan(event, scanId));
    } catch (final RejectedExecutionException exception) {
      rejectionAction.run();
    }
  }

  private void executeScan(final @NonNull ArtifactPushedEvent event, final @NonNull UUID scanId) {

    final var scanner = this.resolveScanner(event, scanId);

    if (scanner == null) {
      return;
    }

    this.runScan(event, scanId, scanner, 1);
  }

  private void runScan(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull VulnerabilityScanner scanner,
      final int attempt) {

    try {
      final var scanInputs = this.resolveScanInputs(event, scanId);

      if (scanInputs == null) {
        return;
      }

      scanner.scan(
          new ScanRequest(
              scanId,
              event.repoType(),
              event.repoId(),
              event.artifactName(),
              event.artifactVersion(),
              event.storagePath(),
              scanInputs.artifactContent(),
              scanInputs.dockerRegistryReference(),
              scanInputs.registryAuthToken()));
    } catch (final ItemNotFoundException exception) {
      this.handleScanRowGone(event, scanId, exception);
    } catch (final ObjectOptimisticLockingFailureException
        | DataIntegrityViolationException exception) {
      this.handleScanWriteConflict(event, scanId, exception);
    } catch (final WebClientRequestException exception) {
      this.handleScannerUnreachable(event, scanId, scanner, attempt, exception);
    } catch (final Exception exception) {
      this.handleScanFailure(event, scanId, exception);
    }
  }

  /**
   * The scan row is gone when the repo it belonged to was deleted after the scan was queued (the
   * foreign key is {@code on delete cascade}); the scanner then fails to record its outcome with
   * {@link ItemNotFoundException}("{@value #SCAN_NOT_FOUND_MSG_ID}") when the row is already gone
   * at the read. That is a benign race, not a real scan failure, so it is logged at INFO and does
   * not record a failed scan for a row that no longer exists. Any other {@link
   * ItemNotFoundException} is treated as a genuine failure.
   */
  private void handleScanRowGone(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull ItemNotFoundException exception) {

    if (!SCAN_NOT_FOUND_MSG_ID.equals(exception.getMessage())) {
      this.handleScanFailure(event, scanId, exception);
      return;
    }

    this.logScanRowGone(event);
  }

  /**
   * The same race one step later: the row was read, the repo was deleted, and the write of the
   * outcome then found no row to update ({@link ObjectOptimisticLockingFailureException}) or a
   * finding whose scan is gone ({@link DataIntegrityViolationException} on the foreign key). Only
   * when the row is really gone is that the benign case; while it is still there, the failure is a
   * genuine one and is recorded and logged at ERROR.
   */
  private void handleScanWriteConflict(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull RuntimeException exception) {

    if (isOtherIntegrityViolation(exception) || this.scanTxService.scanExists(scanId)) {
      this.handleScanFailure(event, scanId, exception);
      return;
    }

    this.logScanRowGone(event);
  }

  private void logScanRowGone(final @NonNull ArtifactPushedEvent event) {
    log.info(
        "Skipping vulnerability scan outcome for {}@{} (repo={}): the scan row no longer exists,"
            + " most likely because the repo was deleted while the scan was in flight",
        event.artifactName(),
        event.artifactVersion(),
        event.repoName());
  }

  /**
   * A data integrity violation is the deleted-scan race only when it is a foreign key violation
   * (the finding's scan is gone); any other one is unexpected and stays a failure.
   */
  private static boolean isOtherIntegrityViolation(final @NonNull RuntimeException exception) {
    return exception instanceof DataIntegrityViolationException violation
        && !ConstraintViolations.isForeignKeyViolation(violation);
  }

  /**
   * The scanner could not be reached ({@link WebClientRequestException}: connection refused, DNS
   * failure, connection reset), which is what a scanner that is restarting, cold-starting or being
   * upgraded looks like. The submit is tried again a bounded number of times, {@code
   * repsy.security.trivy.submit-max-attempts} in all, after a growing delay.
   *
   * <p>Only this failure is retried. A 5xx answer, the {@code block()} timeout of the submit
   * ({@link IllegalStateException}) and an unreadable artifact mean the scanner was reached, or the
   * upload was under way, and re-sending an artifact of up to hundreds of megabytes to a slow
   * scanner would only make it slower. Those stay a recorded failure that can be re-run by hand.
   *
   * <p>The delay is not slept on the scan thread: the retry is handed to the {@link TaskScheduler},
   * which only queues it on the scan executor again when the time comes, so the scan thread is free
   * meanwhile and a saturated executor rejects the retry like any other scan. The row stays {@code
   * PENDING} in between, and the status poller ignores a {@code PENDING} scan the scanner does not
   * know yet. The scanner keeps one job per scan id, so a submit that landed but was never answered
   * is safe to send again.
   *
   * <p>A retry that is waiting lives in memory only. When the backend restarts meanwhile it is
   * lost, and {@code TrivyScanStatusPoller} fails the row once {@code max-scan-duration-seconds}
   * have passed since it was created, which is the same recovery as for any other stuck scan. The
   * retry budget ({@link TrivyScannerProperties#submitRetryBudgetSeconds()}) is well below that.
   */
  private void handleScannerUnreachable(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull VulnerabilityScanner scanner,
      final int attempt,
      final @NonNull WebClientRequestException exception) {

    final var maxAttempts = this.scannerProperties.submitMaxAttempts();

    if (attempt >= maxAttempts) {
      this.handleScanFailure(
          event, scanId, exception, maxAttempts > 1 ? " (after " + maxAttempts + " attempts)" : "");
      return;
    }

    final var delay = this.scannerProperties.submitRetryDelay(attempt);

    log.warn(
        "Vulnerability scanner unreachable for {}@{} (repo={}), attempt {} of {}: {}. Retrying in"
            + " {} s",
        event.artifactName(),
        event.artifactVersion(),
        event.repoName(),
        attempt,
        maxAttempts,
        exception.getMessage(),
        delay.toSeconds());

    try {
      this.taskScheduler.schedule(
          () -> this.requeueScan(event, scanId, scanner, attempt + 1),
          Instant.now(this.taskScheduler.getClock()).plus(delay));
    } catch (final RejectedExecutionException _) {
      this.handleScanFailure(event, scanId, exception, " (the retry could not be scheduled)");
    }
  }

  /** Runs on the scheduler thread, so it only hands the retry to the scan executor. */
  private void requeueScan(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull VulnerabilityScanner scanner,
      final int attempt) {

    try {
      this.scanTaskExecutor.execute(() -> this.retryScan(event, scanId, scanner, attempt));
    } catch (final RejectedExecutionException _) {
      this.scanTxService.recordScanFailure(scanId, "Scan executor is saturated; retry later");
      log.warn(
          "Giving up on the retry of the vulnerability scan for {}@{} (repo={}): scan executor is"
              + " saturated",
          event.artifactName(),
          event.artifactVersion(),
          event.repoName());
    }
  }

  private void retryScan(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull VulnerabilityScanner scanner,
      final int attempt) {

    if (!this.scanTxService.scanExists(scanId)) {
      this.logScanRowGone(event);
      return;
    }

    this.runScan(event, scanId, scanner, attempt);
  }

  private void handleScanFailure(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull Exception exception) {

    this.handleScanFailure(event, scanId, exception, "");
  }

  private void handleScanFailure(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull Exception exception,
      final @NonNull String messageSuffix) {

    log.error(
        "Vulnerability scan failed for {}@{}",
        event.artifactName(),
        event.artifactVersion(),
        exception);
    this.scanTxService.recordScanFailure(scanId, resolveFailureMessage(exception) + messageSuffix);
  }

  private @Nullable ScanInputs resolveScanInputs(
      final @NonNull ArtifactPushedEvent event, final @NonNull UUID scanId) {

    if (DOCKER_REPO_TYPE.equals(event.repoType())) {
      return this.resolveDockerScanInputs(event);
    }

    final var artifactContent = this.resolveArtifactContent(event, scanId);

    if (artifactContent == null) {
      return null;
    }

    return new ScanInputs(artifactContent, null, null);
  }

  private @Nullable ScanInputs resolveDockerScanInputs(final @NonNull ArtifactPushedEvent event) {
    final var repoInfo = this.findRepoOrNull(event);

    if (repoInfo == null) {
      return null;
    }

    final var authToken =
        repoInfo.isPrivateRepo()
            ? this.dockerScanTokenIssuer.mintReadOnlyPullToken(event.repoId(), event.repoName())
            : null;

    return new ScanInputs(null, buildDockerRegistryReference(event), authToken);
  }

  private @Nullable RepoInfo findRepoOrNull(final @NonNull ArtifactPushedEvent event) {
    try {
      return this.repoTxService.getRepo(event.repoId());
    } catch (final ItemNotFoundException _) {
      // The repo was deleted after the scan was queued. Its scan rows cascade with it, so there is
      // nothing left to fail or report.
      log.info(
          "Skipping vulnerability scan for {}@{} (repo={}): the repo was deleted before the scan"
              + " started",
          event.artifactName(),
          event.artifactVersion(),
          event.repoName());
      return null;
    }
  }

  private record ScanInputs(
      @Nullable ArtifactContent artifactContent,
      @Nullable String dockerRegistryReference,
      @Nullable String registryAuthToken) {}

  private @Nullable ArtifactContent resolveArtifactContent(
      final @NonNull ArtifactPushedEvent event, final @NonNull UUID scanId) {

    final var storageStrategy = this.storageStrategiesByRepoType.get(event.repoType());

    if (storageStrategy == null) {
      this.scanTxService.recordScanFailure(
          scanId, "No storage strategy for repo type: " + event.repoType());
      return null;
    }

    final var storagePath = StoragePath.of(event.repoId(), event.storagePath());
    final var resource = storageStrategy.get(storagePath, event.repoName());

    if (resource.isEmpty()) {
      this.scanTxService.recordScanFailure(scanId, "Artifact not found in storage");
      return null;
    }

    return new ResourceArtifactContent(resource.get(), extractFileName(event.storagePath()));
  }

  private @Nullable UUID createPendingScanOrNull(final @NonNull ArtifactPushedEvent event) {
    final var scannerName =
        this.scannerRegistry
            .findScanner(event.repoType())
            .map(VulnerabilityScanner::getName)
            .orElse(NO_SCANNER_NAME);

    try {
      return this.scanTxService.createPendingScan(
          event.repoId(), event.artifactName(), event.artifactVersion(), scannerName);
    } catch (final ItemAlreadyExistException exception) {
      log.warn(
          "Skipping vulnerability scan for {}@{} (repo={}): a scan is already PENDING/RUNNING for"
              + " this exact artifact version",
          event.artifactName(),
          event.artifactVersion(),
          event.repoName());
      return null;
    } catch (final ItemNotFoundException _) {
      log.info(
          "Skipping vulnerability scan for {}@{} (repo={}): the repo no longer exists",
          event.artifactName(),
          event.artifactVersion(),
          event.repoName());
      return null;
    }
  }

  private @Nullable VulnerabilityScanner resolveScanner(
      final @NonNull ArtifactPushedEvent event, final @NonNull UUID scanId) {

    final var scannerOpt = this.scannerRegistry.findScanner(event.repoType());

    if (scannerOpt.isEmpty()) {
      this.scanTxService.recordScanFailure(
          scanId, "No scanner registered for repo type: " + event.repoType());
      return null;
    }

    return scannerOpt.get();
  }

  private static boolean isArtifactCoordinateMissing(final @NonNull ArtifactPushedEvent event) {
    return event.artifactName() == null || event.artifactVersion() == null;
  }

  private static @NonNull String buildDockerRegistryReference(
      final @NonNull ArtifactPushedEvent event) {

    final var version = event.artifactVersion();
    final var separator = BlobDigests.startsWithDigestPrefix(version) ? "@" : ":";

    return event.repoName() + "/" + event.artifactName() + separator + version;
  }

  private static @NonNull String resolveFailureMessage(final @NonNull Exception exception) {
    return exception.getMessage() != null
        ? exception.getMessage()
        : exception.getClass().getSimpleName();
  }

  private static @NonNull String extractFileName(final @NonNull String storagePath) {
    return storagePath.substring(storagePath.lastIndexOf('/') + 1);
  }
}
