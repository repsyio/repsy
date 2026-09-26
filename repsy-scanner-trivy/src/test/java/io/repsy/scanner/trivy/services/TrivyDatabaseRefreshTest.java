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

import static io.repsy.scanner.trivy.services.TrivyTestFiles.FAR_AHEAD;
import static io.repsy.scanner.trivy.services.TrivyTestFiles.LONG_AGO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.ScanJobStatus;
import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.jobs.InMemoryJobStore;
import io.repsy.scanner.trivy.jobs.TrivyDatabaseRefreshTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

// The refresh of the Trivy databases, with a fake command runner: no network, no trivy binary.
class TrivyDatabaseRefreshTest {

  private static final Instant OLD = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant NEW = Instant.parse("2026-09-26T00:00:00Z");

  @TempDir Path cacheDir;

  private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
  private final TrivyDatabaseAccess access = new TrivyDatabaseAccess();
  private final InMemoryJobStore jobStore = new InMemoryJobStore();

  // What the fake trivy does for one command; default: a successful download into --cache-dir.
  private Function<List<String>, String> behaviour = this::download;

  private TrivyScanService service;
  private TrivyDatabaseMetadata metadata;
  private TrivyDatabaseRefreshTask task;

  @BeforeEach
  void setUp() {
    final var properties =
        new TrivyScannerProperties(
            "trivy", 30, "db-repo", "java-db-repo", this.cacheDir.toString(), Duration.ofHours(12));
    final var mapper = JsonMapper.builder().build();

    this.service =
        new TrivyScanService(
            properties,
            mapper,
            this.jobStore,
            (command, timeoutSeconds) -> {
              this.commands.add(command);
              return this.behaviour.apply(command);
            },
            this.access,
            Runnable::run);
    this.metadata = new TrivyDatabaseMetadata(properties, mapper);
    this.task = new TrivyDatabaseRefreshTask(this.service, this.metadata);
  }

  private static String flagValue(final List<String> command, final String flag) {
    return command.get(command.indexOf(flag) + 1);
  }

  // Writes what `trivy rootfs --download-db-only` / `--download-java-db-only` leave in its cache.
  private String download(final List<String> command) {
    final var cache = Path.of(flagValue(command, "--cache-dir"));

    try {
      if (command.contains("--download-db-only")) {
        TrivyTestFiles.writeVulnerabilityDb(cache, "new", NEW, FAR_AHEAD);
      } else if (command.contains("--download-java-db-only")) {
        TrivyTestFiles.writeJavaDb(cache, "new-java", NEW, FAR_AHEAD);
      }
    } catch (final IOException exception) {
      throw new IllegalStateException(exception);
    }

    return "";
  }

  private void writeLiveDatabases(final Instant nextUpdate) throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.cacheDir, "old", OLD, nextUpdate);
    TrivyTestFiles.writeJavaDb(this.cacheDir, "old-java", OLD, nextUpdate);
  }

  @Test
  void refreshDownloadsBothDatabasesIntoAStagingDirectoryAndSwapsThemIn() throws IOException {
    this.writeLiveDatabases(LONG_AGO);

    this.service.refreshDatabases();

    final var staging = this.cacheDir.resolve("refresh-staging").toString();
    assertThat(this.commands).hasSize(2);
    assertThat(this.commands.get(0))
        .containsSubsequence("trivy", "rootfs", "--download-db-only", "--cache-dir", staging)
        .containsSubsequence("--db-repository", "db-repo", "--java-db-repository", "java-db-repo");
    assertThat(this.commands.get(1))
        .containsSubsequence("rootfs", "--download-java-db-only", "--cache-dir", staging);

    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("new");
    assertThat(this.cacheDir.resolve("java-db/trivy-java.db")).hasContent("new-java");
    assertThat(this.metadata.vulnerabilityDbUpdatedAt()).contains(NEW);
    assertThat(this.cacheDir.resolve("refresh-staging")).doesNotExist();
  }

  @Test
  void refreshDownloadsTheFirstDatabaseOfAnEmptyCache() {
    this.service.refreshDatabases();

    assertThat(this.metadata.vulnerabilityDbUpdatedAt()).contains(NEW);
    assertThat(this.metadata.javaDb()).isPresent();
  }

  @Test
  void aFailedDownloadKeepsTheCurrentDatabasesAndRemovesTheStaging() throws IOException {
    this.writeLiveDatabases(LONG_AGO);
    this.behaviour =
        command -> {
          this.download(command);
          if (command.contains("--download-java-db-only")) {
            throw new TrivyScanException("trivy exited with code 1: fatal error");
          }
          return "";
        };

    assertThatThrownBy(() -> this.service.refreshDatabases())
        .isInstanceOf(TrivyScanException.class);

    // the vulnerability database was downloaded, the Java database was not: nothing is swapped
    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("old");
    assertThat(this.cacheDir.resolve("java-db/trivy-java.db")).hasContent("old-java");
    assertThat(this.metadata.vulnerabilityDbUpdatedAt()).contains(OLD);
    assertThat(this.cacheDir.resolve("refresh-staging")).doesNotExist();
  }

  @Test
  void aDownloadThatLeavesNoFilesKeepsTheCurrentDatabases() throws IOException {
    this.writeLiveDatabases(LONG_AGO);
    this.behaviour = command -> "";

    assertThatThrownBy(() -> this.service.refreshDatabases())
        .isInstanceOf(TrivyScanException.class)
        .hasMessage("Failed to replace the Trivy databases");

    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("old");
  }

  @Test
  void aRefreshWaitsForARunningScanBeforeItSwaps() throws Exception {
    this.writeLiveDatabases(LONG_AGO);
    final var scanPermit = this.access.enterScan();

    final var refresh =
        java.util.concurrent.CompletableFuture.runAsync(() -> this.service.refreshDatabases());

    // downloaded in the background, but the live files stay while the scan is running
    Thread.sleep(500);
    assertThat(refresh).isNotDone();
    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("old");

    scanPermit.close();
    refresh.get(10, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("new");
  }

  @Test
  void warmUpLetsTrivyDownloadStraightIntoTheCacheDirectory() {
    this.service.warmUpDatabases();

    assertThat(this.commands).hasSize(2);
    assertThat(this.commands.get(0))
        .containsSubsequence(
            "rootfs", "--download-db-only", "--cache-dir", this.cacheDir.toString());
    assertThat(this.commands.get(1))
        .containsSubsequence(
            "rootfs", "--download-java-db-only", "--cache-dir", this.cacheDir.toString());
  }

  @Test
  void scansRunUnderAScanPermitSoALookupIsRefusedMeanwhile() {
    final var lookupDuringScan = new ArrayList<Boolean>();
    this.behaviour =
        command -> {
          if (command.contains("image")) {
            lookupDuringScan.add(this.access.tryEnterLookup(Duration.ZERO).isPresent());
          }
          return "{\"Results\":[]}";
        };

    this.service.submitDockerScan("scan-1", "img:1", null, false, "DOCKER", "img", "1");

    assertThat(this.jobStore.get("scan-1").orElseThrow().status())
        .isEqualTo(ScanJobStatus.COMPLETED);
    assertThat(lookupDuringScan).containsExactly(false);
    // and the permit is given back afterwards
    assertThat(this.access.tryEnterLookup(Duration.ZERO)).isPresent();
  }

  @Test
  void scanCommandsPointTrivyAtTheConfiguredCacheDirectory() {
    this.service.submitDockerScan("scan-1", "img:1", null, false, "DOCKER", "img", "1");

    assertThat(this.commands.getFirst())
        .containsSubsequence("image", "--cache-dir", this.cacheDir.toString());
  }

  @Test
  void theScheduledRefreshDoesNothingWhileTheDatabasesAreNotPastTheirNextUpdate()
      throws IOException {
    this.writeLiveDatabases(FAR_AHEAD);

    this.task.refresh();

    assertThat(this.commands).isEmpty();
    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("old");
  }

  @Test
  void theScheduledRefreshDownloadsWhenADatabaseIsPastItsNextUpdate() throws IOException {
    this.writeLiveDatabases(LONG_AGO);

    this.task.refresh();

    assertThat(this.commands).hasSize(2);
    assertThat(this.metadata.vulnerabilityDbUpdatedAt()).contains(NEW);
  }

  @Test
  void aFailingScheduledRefreshLogsAndKeepsServingTheCurrentDatabase() throws IOException {
    this.writeLiveDatabases(LONG_AGO);
    this.behaviour =
        command -> {
          throw new TrivyScanException("trivy exited with code 1: fatal error");
        };

    // must not throw: an exception would cancel nothing, but it must not escape into the scheduler
    this.task.refresh();

    assertThat(this.metadata.vulnerabilityDbUpdatedAt()).contains(OLD);
    assertThat(this.access.tryEnterLookup(Duration.ZERO)).as("no permit is left").isPresent();
  }

  @Test
  void theRetryTaskDownloadsOnlyWhenThereIsNoVulnerabilityDatabase() throws IOException {
    this.writeLiveDatabases(LONG_AGO);
    this.task.downloadWhenMissing();
    assertThat(this.commands).isEmpty();

    Files.delete(this.cacheDir.resolve("db/trivy.db"));
    this.task.downloadWhenMissing();

    assertThat(this.commands).hasSize(2);
    assertThat(this.cacheDir.resolve("db/trivy.db")).hasContent("new");
  }
}
