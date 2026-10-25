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

import io.repsy.libs.scanner.dtos.ScanOutcome;
import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.ScanJob;
import io.repsy.scanner.trivy.dtos.trivy.TrivyReport;
import io.repsy.scanner.trivy.dtos.trivy.TrivyVersion;
import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.jobs.JobStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

  private static final String VERSION_COMMAND = "version";

  private static final String WARMUP_TARGET = ".";
  private static final String STAGING_DIR = "refresh-staging";
  private static final String DOWNLOAD_DB_ONLY_FLAG = "--download-db-only";
  private static final String DOWNLOAD_JAVA_DB_ONLY_FLAG = "--download-java-db-only";
  private static final String DB_REPOSITORY_FLAG = "--db-repository";
  private static final String JAVA_DB_REPOSITORY_FLAG = "--java-db-repository";

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

  private final TrivyScannerProperties properties;
  private final ObjectMapper objectMapper;
  private final JobStore jobStore;
  private final TrivyCommandRunner runner;
  private final TrivyDatabaseAccess databaseAccess;

  @Qualifier("scanWorkerExecutor")
  private final Executor scanWorkerExecutor;

  // The version of the Trivy binary, read once (a failed read is not cached, so it is retried).
  private volatile @Nullable String trivyVersion;

  public void submitScan(
      final String scanId,
      final MultipartFile file,
      final String repoType,
      final String artifactName,
      final String artifactVersion) {

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
      final String scanId,
      final String imageReference,
      final @Nullable String registryAuthToken,
      final boolean registryInsecure,
      final String repoType,
      final String artifactName,
      final String artifactVersion) {

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

  public Optional<ScanJob> findStatus(final String scanId) {
    return this.jobStore.get(scanId);
  }

  // Startup: trivy downloads what is missing or past its NextUpdate straight into the cache
  // directory. Nothing is served yet, so nothing needs protecting, and trivy alone decides (it also
  // replaces a database of an older schema after a Trivy upgrade).
  public void warmUpDatabases() {
    try (var ignored = this.databaseAccess.enterRefresh()) {
      this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_DB_ONLY_FLAG, this.cacheDir()));
      this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_JAVA_DB_ONLY_FLAG, this.cacheDir()));
    }
  }

  // Scheduled refresh: trivy downloads both databases into an empty staging cache directory while
  // scans and lookups go on undisturbed, and only a complete download replaces the live files, in
  // a moment during which nothing else uses them. Trivy itself would delete metadata.json, then
  // rewrite trivy.db in place, so a download that fails half way would leave no usable database.
  public void refreshDatabases() {
    final var staging = Path.of(this.cacheDir()).resolve(STAGING_DIR);

    try {
      TrivyDatabaseSwapper.recreate(staging);

      this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_DB_ONLY_FLAG, staging.toString()));
      this.runTrivy(this.buildDownloadOnlyCommand(DOWNLOAD_JAVA_DB_ONLY_FLAG, staging.toString()));

      try (var ignored = this.databaseAccess.enterRefresh()) {
        TrivyDatabaseSwapper.swap(staging, Path.of(this.cacheDir()));
      }
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to replace the Trivy databases", exception);
    } finally {
      TrivyDatabaseSwapper.deleteRecursivelyQuietly(staging);
    }
  }

  private String cacheDir() {
    return this.properties.cacheDir();
  }

  private void enqueue(
      final String scanId, final @Nullable Path artifactPath, final Runnable task) {

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
      final String scanId, final Path artifactPath, final @Nullable String fileName) {

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
      final String scanId,
      final String imageReference,
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

  private ScanOutcome runAndParse(final List<String> command) {
    final String stdout;

    try (var ignored = this.databaseAccess.enterScan()) {
      stdout = this.runTrivy(command);
    }

    final var report = this.parseReport(stdout);
    final var findings = TrivyFindingMapper.toFindings(report.results());

    return new ScanOutcome(findings, this.resolveScannerVersion(report));
  }

  // Trivy 0.66's JSON report has no "Trivy" key, so the version comes from the binary itself; a
  // report that does carry one (an older or newer Trivy) wins.
  private @Nullable String resolveScannerVersion(final TrivyReport report) {
    if (report.trivy() != null && StringUtils.isNotBlank(report.trivy().version())) {
      return report.trivy().version();
    }

    return this.trivyVersion();
  }

  public @Nullable String trivyVersion() {
    final var known = this.trivyVersion;

    if (known != null) {
      return known;
    }

    try {
      final var parsed =
          this.objectMapper.readValue(
              this.runTrivy(this.buildVersionCommand()), TrivyVersion.class);

      if (StringUtils.isNotBlank(parsed.version())) {
        this.trivyVersion = parsed.version();
        log.info("Trivy version {}", parsed.version());
      }
    } catch (final JacksonException | TrivyScanException exception) {
      log.warn("Could not read the Trivy version; scans report none", exception);
    }

    return this.trivyVersion;
  }

  private void updateStatus(final String scanId, final UnaryOperator<ScanJob> transition) {

    this.jobStore.get(scanId).ifPresent(job -> this.jobStore.update(scanId, transition.apply(job)));
  }

  private @Nullable Path extractIfArchive(
      final Path artifactPath, final @Nullable String fileName) {

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

  private Path extractTarGz(final Path artifactPath) {
    try {
      return TarGzExtractor.extract(artifactPath);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to extract archive for scanning", exception);
    }
  }

  private Path extractWheel(final Path artifactPath) {
    try {
      return WheelExtractor.extract(artifactPath);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to extract archive for scanning", exception);
    }
  }

  private Path writeToTempFile(final MultipartFile file) {
    try {
      final var tempFile = Files.createTempFile("scan-", "." + extractExtension(file));
      file.transferTo(tempFile);
      return tempFile;
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to materialize uploaded artifact", exception);
    }
  }

  private List<String> buildRootfsCommand(final Path artifactPath) {
    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("rootfs");
    command.add("--format");
    command.add("json");
    command.add("--quiet");
    command.add("--cache-dir");
    command.add(this.cacheDir());
    this.addDbRepositoryFlags(command);
    command.add(artifactPath.toString());

    return command;
  }

  private List<String> buildImageCommand(
      final String imageReference,
      final @Nullable String registryAuthToken,
      final boolean registryInsecure) {

    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("image");
    command.add("--format");
    command.add("json");
    command.add("--quiet");
    command.add("--cache-dir");
    command.add(this.cacheDir());
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

  private List<String> buildVersionCommand() {
    return List.of(this.properties.binaryPath(), VERSION_COMMAND, "--format", "json");
  }

  private List<String> buildDownloadOnlyCommand(
      final String downloadOnlyFlag, final String cacheDir) {
    final var command = new ArrayList<String>();
    command.add(this.properties.binaryPath());
    command.add("rootfs");
    command.add(downloadOnlyFlag);
    command.add("--cache-dir");
    command.add(cacheDir);
    this.addDbRepositoryFlags(command);
    command.add(WARMUP_TARGET);

    return command;
  }

  private void addDbRepositoryFlags(final List<String> command) {
    command.add(DB_REPOSITORY_FLAG);
    command.add(this.properties.dbRepository());
    command.add(JAVA_DB_REPOSITORY_FLAG);
    command.add(this.properties.javaDbRepository());
  }

  /**
   * Trivy falls back to the next repository of a list only for some failures (a rate limit, not
   * ghcr.io's {@code DENIED: invalid token}) and takes the first one otherwise, so the configured
   * list is walked here instead: attempt n downloads from repository n, wrapping around (RPS-2333).
   */
  private static List<String> withDbRepositoriesOfAttempt(
      final List<String> command, final int attempt) {

    final var rewritten = new ArrayList<>(command);

    for (final var flag : List.of(DB_REPOSITORY_FLAG, JAVA_DB_REPOSITORY_FLAG)) {
      final var index = rewritten.indexOf(flag);

      if (index >= 0) {
        rewritten.set(index + 1, repositoryOfAttempt(rewritten.get(index + 1), attempt));
      }
    }

    return rewritten;
  }

  private static String repositoryOfAttempt(final String configured, final int attempt) {
    final var repositories =
        Arrays.stream(configured.split(",")).map(String::strip).filter(r -> !r.isEmpty()).toList();

    return repositories.isEmpty()
        ? configured
        : repositories.get((attempt - 1) % repositories.size());
  }

  private String runTrivy(final List<String> command) {
    for (var attempt = 1; ; attempt++) {
      try {
        return this.runner.run(
            withDbRepositoriesOfAttempt(command, attempt), this.properties.timeoutSeconds());
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

  private TrivyReport parseReport(final String json) {
    try {
      return this.objectMapper.readValue(json, TrivyReport.class);
    } catch (final JacksonException exception) {
      throw new TrivyScanException("Failed to parse trivy JSON output", exception);
    }
  }

  private void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException exception) {
      log.warn("Failed to delete temporary scan file {}", path, exception);
    }
  }

  private static String extractExtension(final MultipartFile file) {
    final var fileName = file.getOriginalFilename();

    if (fileName == null) {
      return DEFAULT_EXTENSION;
    }

    final var dotIndex = fileName.lastIndexOf('.');

    return dotIndex >= 0 ? fileName.substring(dotIndex + 1) : DEFAULT_EXTENSION;
  }

  private static String resolveFailureMessage(final Exception exception) {
    final var message =
        exception.getMessage() != null
            ? exception.getMessage()
            : exception.getClass().getSimpleName();

    return truncateErrorMessage(message);
  }

  private static String truncateErrorMessage(final String message) {
    if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
      return message;
    }

    return message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + TRUNCATION_SUFFIX;
  }
}
