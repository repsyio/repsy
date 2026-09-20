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
package io.repsy.scanner.trivy.services;

import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.ScanJob;
import io.repsy.scanner.trivy.dtos.ScanOutcome;
import io.repsy.scanner.trivy.dtos.trivy.TrivyReport;
import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.jobs.JobStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrivyScanService {

  private static final String DEFAULT_EXTENSION = "bin";

  private static final int MAX_ERROR_MESSAGE_LENGTH = 1900;
  private static final String TRUNCATION_SUFFIX = "... [truncated]";

  private static final String WARMUP_TARGET = ".";
  private static final String DOWNLOAD_DB_ONLY_FLAG = "--download-db-only";
  private static final String DOWNLOAD_JAVA_DB_ONLY_FLAG = "--download-java-db-only";

  private static final int MAX_DB_DOWNLOAD_ATTEMPTS = 5;
  private static final long DB_DOWNLOAD_INITIAL_RETRY_DELAY_MS = 5_000;
  private static final int DB_DOWNLOAD_RETRY_BACKOFF_MULTIPLIER = 2;
  private static final List<String> DB_DOWNLOAD_FAILURE_MARKERS =
      List.of(
          "failed to download",
          "Java DB update failed",
          "OCI artifact error",
          "unexpected status code",
          "failed to fetch the layer");

  private final @NonNull TrivyScannerProperties properties;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull JobStore jobStore;

  @Qualifier("scanWorkerExecutor")
  private final @NonNull Executor scanWorkerExecutor;

  public void submitScan(
      final @NonNull String scanId,
      final @NonNull MultipartFile file,
      final @NonNull String repoType,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    log.info(
        "Queuing scan {} for {}@{} (repoType={}, file={})",
        scanId,
        artifactName,
        artifactVersion,
        repoType,
        file.getOriginalFilename());

    final var artifactPath = this.writeToTempFile(file);
    final var fileName = file.getOriginalFilename();

    if (!this.jobStore.putIfAbsent(scanId, ScanJob.queued(scanId))) {
      log.debug("Scan {} already queued/running, skipping duplicate submit", scanId);
      this.deleteQuietly(artifactPath);
      return;
    }

    this.enqueue(scanId, artifactPath, () -> this.runFileScan(scanId, artifactPath, fileName));
  }

  public void submitDockerScan(
      final @NonNull String scanId,
      final @NonNull String imageReference,
      final @Nullable String registryAuthToken,
      final boolean registryInsecure,
      final @NonNull String repoType,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    log.info(
        "Queuing docker scan {} for image {} ({}@{}, repoType={}, insecure={})",
        scanId,
        imageReference,
        artifactName,
        artifactVersion,
        repoType,
        registryInsecure);

    if (!this.jobStore.putIfAbsent(scanId, ScanJob.queued(scanId))) {
      log.debug("Docker scan {} already queued/running, skipping duplicate submit", scanId);
      return;
    }

    this.enqueue(
        scanId,
        null,
        () -> this.runDockerScan(scanId, imageReference, registryAuthToken, registryInsecure));
  }

  public @NonNull Optional<ScanJob> getStatus(final @NonNull String scanId) {
    return this.jobStore.get(scanId);
  }

  public void warmUpDatabases() {
    this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_DB_ONLY_FLAG));
    this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_JAVA_DB_ONLY_FLAG));
  }

  private void enqueue(
      final @NonNull String scanId,
      final @Nullable Path artifactPath,
      final @NonNull Runnable task) {

    try {
      this.scanWorkerExecutor.execute(task);
    } catch (final RejectedExecutionException exception) {
      log.error("Failed to enqueue scan {}: worker queue full", scanId, exception);
      this.updateStatus(scanId, job -> job.toFailed("Scanner busy, please retry"));

      if (artifactPath != null) {
        this.deleteQuietly(artifactPath);
      }
    }
  }

  private void runFileScan(
      final @NonNull String scanId,
      final @NonNull Path artifactPath,
      final @Nullable String fileName) {

    this.updateStatus(scanId, ScanJob::toRunning);

    Path extractedDir = null;
    try {
      extractedDir = this.extractIfArchive(artifactPath, fileName);

      final var scanTarget = extractedDir != null ? extractedDir : artifactPath;
      final var outcome = this.runAndParse(this.buildRootfsCommand(scanTarget));

      this.updateStatus(scanId, job -> job.toCompleted(outcome));
    } catch (final Exception exception) {
      log.error("Vulnerability scan {} failed", scanId, exception);
      this.updateStatus(scanId, job -> job.toFailed(resolveFailureMessage(exception)));
    } finally {
      this.deleteQuietly(artifactPath);

      if (extractedDir != null) {
        TarGzExtractor.deleteRecursivelyQuietly(extractedDir);
      }
    }
  }

  private void runDockerScan(
      final @NonNull String scanId,
      final @NonNull String imageReference,
      final @Nullable String registryAuthToken,
      final boolean registryInsecure) {

    this.updateStatus(scanId, ScanJob::toRunning);

    try {
      final var outcome =
          this.runAndParse(
              this.buildImageCommand(imageReference, registryAuthToken, registryInsecure));

      this.updateStatus(scanId, job -> job.toCompleted(outcome));
    } catch (final Exception exception) {
      log.error("Docker vulnerability scan {} failed", scanId, exception);
      this.updateStatus(scanId, job -> job.toFailed(resolveFailureMessage(exception)));
    }
  }

  private @NonNull ScanOutcome runAndParse(final @NonNull List<String> command) {
    final var stdout = this.runTrivy(command);
    final var report = this.parseReport(stdout);
    final var findings = TrivyFindingMapper.toFindings(report.results());
    final var scannerVersion = report.trivy() != null ? report.trivy().version() : null;

    return new ScanOutcome(findings, scannerVersion);
  }

  private void updateStatus(
      final @NonNull String scanId, final @NonNull UnaryOperator<ScanJob> transition) {

    this.jobStore.get(scanId).ifPresent(job -> this.jobStore.update(scanId, transition.apply(job)));
  }

  private @Nullable Path extractIfArchive(
      final @NonNull Path artifactPath, final @Nullable String fileName) {

    if (fileName == null) {
      return null;
    }

    if (TarGzExtractor.isTarGz(fileName)) {
      return this.extractTarGz(artifactPath);
    }

    if (WheelExtractor.isWheel(fileName)) {
      return this.extractWheel(artifactPath);
    }

    return null;
  }

  private @NonNull Path extractTarGz(final @NonNull Path artifactPath) {
    try {
      return TarGzExtractor.extract(artifactPath);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to extract archive for scanning", exception);
    }
  }

  private @NonNull Path extractWheel(final @NonNull Path artifactPath) {
    try {
      return WheelExtractor.extract(artifactPath);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to extract archive for scanning", exception);
    }
  }

  private @NonNull Path writeToTempFile(final @NonNull MultipartFile file) {
    try {
      final var tempFile = Files.createTempFile("scan-", "." + extractExtension(file));
      file.transferTo(tempFile);
      return tempFile;
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to materialize uploaded artifact", exception);
    }
  }

  private @NonNull List<String> buildRootfsCommand(final @NonNull Path artifactPath) {
    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("rootfs");
    command.add("--format");
    command.add("json");
    command.add("--quiet");
    this.addDbRepositoryFlags(command);
    command.add(artifactPath.toString());

    return command;
  }

  private @NonNull List<String> buildImageCommand(
      final @NonNull String imageReference,
      final @Nullable String registryAuthToken,
      final boolean registryInsecure) {

    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("image");
    command.add("--format");
    command.add("json");
    command.add("--quiet");
    this.addDbRepositoryFlags(command);

    if (registryInsecure) {
      command.add("--insecure");
    }

    if (registryAuthToken != null) {
      command.add("--registry-token");
      command.add(registryAuthToken);
    }

    command.add("--");
    command.add(imageReference);

    return command;
  }

  private @NonNull List<String> buildDownloadOnlyCommand(final @NonNull String downloadOnlyFlag) {
    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("rootfs");
    command.add(downloadOnlyFlag);
    this.addDbRepositoryFlags(command);
    command.add(WARMUP_TARGET);

    return command;
  }

  private void addDbRepositoryFlags(final @NonNull List<String> command) {
    command.add("--db-repository");
    command.add(this.properties.dbRepository());
    command.add("--java-db-repository");
    command.add(this.properties.javaDbRepository());
  }

  private @NonNull String runTrivy(final @NonNull List<String> command) {
    for (var attempt = 1; ; attempt++) {
      try {
        final var process = new ProcessBuilder(command).start();
        return this.awaitOutput(process);
      } catch (final IOException exception) {
        throw new TrivyScanException("Failed to start trivy process", exception);
      } catch (final TrivyScanException exception) {
        if (attempt >= MAX_DB_DOWNLOAD_ATTEMPTS || !isDbDownloadFailure(exception.getMessage())) {
          throw exception;
        }

        final var delayMs = dbDownloadRetryDelayMs(attempt);
        log.warn(
            "Trivy DB download looked transient (attempt {}/{}), retrying in {}ms: {}",
            attempt,
            MAX_DB_DOWNLOAD_ATTEMPTS,
            delayMs,
            exception.getMessage());
        sleepQuietly(delayMs);
      }
    }
  }

  private static boolean isDbDownloadFailure(final @Nullable String message) {
    if (message == null) {
      return false;
    }

    return DB_DOWNLOAD_FAILURE_MARKERS.stream().anyMatch(message::contains);
  }

  private static long dbDownloadRetryDelayMs(final int failedAttempt) {
    return DB_DOWNLOAD_INITIAL_RETRY_DELAY_MS
        * (long) Math.pow(DB_DOWNLOAD_RETRY_BACKOFF_MULTIPLIER, failedAttempt - 1);
  }

  private static void sleepQuietly(final long delayMs) {
    try {
      Thread.sleep(delayMs);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new TrivyScanException("Interrupted while waiting to retry trivy", exception);
    }
  }

  private @NonNull String awaitOutput(final @NonNull Process process) {
    final var stdoutFuture =
        CompletableFuture.supplyAsync(() -> readFully(process.getInputStream()));
    final var stderrFuture =
        CompletableFuture.supplyAsync(() -> readFully(process.getErrorStream()));

    final var finished = this.waitForProcess(process);
    final var stdout = stdoutFuture.join();
    final var stderr = stderrFuture.join();

    if (StringUtils.isNotBlank(stderr)) {
      log.debug("trivy stderr output: {}", stderr);
    }

    if (!finished) {
      process.destroyForcibly();
      throw new TrivyScanException(
          "Trivy scan timed out after " + this.properties.timeoutSeconds() + "s");
    }

    if (process.exitValue() != 0) {
      throw new TrivyScanException(
          truncateErrorMessage("trivy exited with code " + process.exitValue() + ": " + stderr));
    }

    return stdout;
  }

  private boolean waitForProcess(final @NonNull Process process) {
    try {
      return process.waitFor(this.properties.timeoutSeconds(), TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new TrivyScanException("Interrupted while waiting for trivy", exception);
    }
  }

  private @NonNull TrivyReport parseReport(final @NonNull String json) {
    try {
      return this.objectMapper.readValue(json, TrivyReport.class);
    } catch (final JacksonException exception) {
      throw new TrivyScanException("Failed to parse trivy JSON output", exception);
    }
  }

  private void deleteQuietly(final @NonNull Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException exception) {
      log.warn("Failed to delete temporary scan file {}", path, exception);
    }
  }

  private static @NonNull String readFully(final @NonNull InputStream inputStream) {
    try {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to read trivy process output", exception);
    }
  }

  private static @NonNull String extractExtension(final @NonNull MultipartFile file) {
    final var fileName = file.getOriginalFilename();

    if (fileName == null) {
      return DEFAULT_EXTENSION;
    }

    final var dotIndex = fileName.lastIndexOf('.');

    return dotIndex >= 0 ? fileName.substring(dotIndex + 1) : DEFAULT_EXTENSION;
  }

  private static @NonNull String resolveFailureMessage(final @NonNull Exception exception) {
    final var message =
        exception.getMessage() != null
            ? exception.getMessage()
            : exception.getClass().getSimpleName();

    return truncateErrorMessage(message);
  }

  private static @NonNull String truncateErrorMessage(final @NonNull String message) {
    if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
      return message;
    }

    return message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + TRUNCATION_SUFFIX;
  }
}
