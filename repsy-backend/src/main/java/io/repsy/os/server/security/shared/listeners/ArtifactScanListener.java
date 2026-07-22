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
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactScanListener {

  private static final String DOCKER_REPO_TYPE = "DOCKER";
  private static final String NO_SCANNER_NAME = "none";

  private final @NonNull VulnerabilityScannerRegistry scannerRegistry;
  private final @NonNull VulnerabilityScanTxService scanTxService;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull DockerScanTokenIssuer dockerScanTokenIssuer;

  @Qualifier("storageStrategiesByRepoType")
  private final @NonNull Map<String, StorageStrategy> storageStrategiesByRepoType;

  /**
   * {@code ArtifactPushedEventPostProcessor} already skips publishing this event entirely when
   * {@code securityScanEnabled} is off, so no disabled-check is needed here — a later manual "Scan
   * Now" for a version that was never auto-scanned recomputes its {@code storagePath} live via
   * {@code ArtifactStorageResolverRegistry} instead of depending on a scan row from this listener.
   */
  @Async("scanTaskExecutor")
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

    this.executeScan(event, scanId);
  }

  /**
   * Not {@code @Async}: this is a cheap, DB-only cleanup (no external HTTP call like the scan
   * itself), and running it synchronously means a UI reload immediately after a delete never sees
   * stale scan rows for the just-deleted version.
   */
  @EventListener
  public void handleArtifactVersionDeleted(final @NonNull ArtifactVersionDeletedEvent event) {
    this.scanTxService.deleteScansForVersion(
        event.repoId(), event.artifactName(), event.artifactVersion());
  }

  /**
   * Entry point for a manually-triggered re-scan (see {@code VulnerabilityScanTriggerService}) —
   * unlike {@link #handleArtifactPushed}, the {@code PENDING} row already exists by the time this
   * is called (created synchronously so the triggering HTTP request can return its id immediately),
   * so this only needs to run the scanner/scan-execution half of the flow. Must stay {@code public}
   * and be invoked through the injected bean (not {@code this}) for the {@code @Async} proxy to
   * apply.
   */
  @Async("scanTaskExecutor")
  public void executeManualScan(
      final @NonNull UUID scanId, final @NonNull ArtifactPushedEvent event) {
    this.executeScan(event, scanId);
  }

  private void executeScan(final @NonNull ArtifactPushedEvent event, final @NonNull UUID scanId) {

    final var scanner = this.resolveScanner(event, scanId);

    if (scanner == null) {
      return;
    }

    this.scanTxService.markRunning(scanId);
    this.runScan(event, scanId, scanner);
  }

  private void runScan(
      final @NonNull ArtifactPushedEvent event,
      final @NonNull UUID scanId,
      final @NonNull VulnerabilityScanner scanner) {

    try {
      final var scanInputs = this.resolveScanInputs(event, scanId);

      if (scanInputs == null) {
        return;
      }

      final var outcome =
          scanner.scan(
              new ScanRequest(
                  event.repoType(),
                  event.repoId(),
                  event.artifactName(),
                  event.artifactVersion(),
                  event.storagePath(),
                  scanInputs.artifactContent(),
                  scanInputs.dockerRegistryReference(),
                  scanInputs.registryAuthToken()));

      this.scanTxService.recordScanOutcome(scanId, outcome);
    } catch (final Exception exception) {
      log.error(
          "Vulnerability scan failed for {}@{}",
          event.artifactName(),
          event.artifactVersion(),
          exception);
      this.scanTxService.recordScanFailure(scanId, resolveFailureMessage(exception));
    }
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

  private @NonNull ScanInputs resolveDockerScanInputs(final @NonNull ArtifactPushedEvent event) {
    final var reference = buildDockerRegistryReference(event);
    final var authToken = this.resolveRegistryAuthToken(event);

    return new ScanInputs(null, reference, authToken);
  }

  /**
   * Only mints a token when the repo is private — public repos are pullable without any credential
   * (matching {@code DockerAuthPreProcessor}'s own skip-auth rule for public READ), so the scanner
   * never carries a token it doesn't need.
   */
  private @Nullable String resolveRegistryAuthToken(final @NonNull ArtifactPushedEvent event) {

    final var repoInfo = this.repoTxService.getRepo(event.repoId());

    if (!repoInfo.isPrivateRepo()) {
      return null;
    }

    return this.dockerScanTokenIssuer.mintReadOnlyPullToken(event.repoId(), event.repoName());
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

  /**
   * Returns {@code null} (after logging why) instead of propagating when a scan record cannot be
   * created for this push — covers the rare case of the same artifact version being pushed twice in
   * quick succession (e.g. a CI retry): the unique partial index on {@code vulnerability_scan} (see
   * {@code V0009__Init_Vulnerability_Scanning}) rejects the second insert while the first scan is
   * still PENDING/RUNNING for the identical repo/artifact/version. There is no scanId to record a
   * failure against in that case (creation itself failed) and nothing distinct to mark failed
   * either — the already in-flight scan already covers this exact artifact content — so the skip is
   * logged at WARN (this app's default {@code logging.level.root} is WARN, and INFO would be
   * silently dropped there) instead of disappearing into the default {@code @Async}
   * uncaught-exception handler.
   */
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

  /**
   * Builds the path-only registry reference ({@code {repoName}/{imageName}:{tag}} or {@code
   * {repoName}/{imageName}@{digest}}, no host:port) — {@code artifactVersion} holds either a tag or
   * a digest depending on how the image was pushed (see {@code
   * AbstractDockerProtocolTxFacade#verifyTag}), distinguished by the {@code sha256:} prefix.
   */
  private static @NonNull String buildDockerRegistryReference(
      final @NonNull ArtifactPushedEvent event) {

    final var version = event.artifactVersion();
    final var separator = version.startsWith(DockerConstants.SHA256_PREFIX) ? "@" : ":";

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
