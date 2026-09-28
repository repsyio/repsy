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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.scanner.trivy.config.AdvisoryProperties;
import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.AdvisoryPackage;
import io.repsy.scanner.trivy.dtos.AdvisoryRequest;
import io.repsy.scanner.trivy.dtos.FixStatus;
import io.repsy.scanner.trivy.dtos.Severity;
import io.repsy.scanner.trivy.errors.AdvisoryTimeoutException;
import io.repsy.scanner.trivy.errors.AdvisoryTooLargeException;
import io.repsy.scanner.trivy.errors.AdvisoryUnavailableException;
import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.errors.TrivyTimeoutException;
import io.repsy.scanner.trivy.jobs.InMemoryJobStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AdvisoryLookupServiceTest {

  private static final Instant DB_UPDATED = Instant.parse("2026-09-26T19:03:57Z");

  private static final String LODASH_REPORT =
      """
      {"Results":[{"Target":"Node.js","Class":"lang-pkgs","Type":"node-pkg","Vulnerabilities":[
        {"VulnerabilityID":"CVE-2021-23337","PkgName":"lodash","InstalledVersion":"4.17.20",
        "FixedVersion":"4.17.21","Status":"fixed","Severity":"HIGH",
        "PrimaryURL":"https://avd.aquasec.com/nvd/cve-2021-23337","Description":"Command injection"}
      ]}]}
      """;

  @TempDir Path tempDir;

  private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
  private final List<String> sboms = Collections.synchronizedList(new ArrayList<>());
  private final List<Path> sbomFiles = Collections.synchronizedList(new ArrayList<>());
  private final TrivyDatabaseAccess access = new TrivyDatabaseAccess();

  private BiFunction<List<String>, Long, String> onSbom = (command, timeout) -> LODASH_REPORT;
  private AdvisoryLookupService service;

  @BeforeEach
  void setUp() throws IOException {
    TrivyTestFiles.writeVulnerabilityDb(this.tempDir, "db", DB_UPDATED, FAR_AHEAD);
    this.service = this.serviceWith(2, 0);
  }

  private AdvisoryLookupService serviceWith(final int concurrency, final long maxWaitSeconds) {
    final var trivyProperties =
        new TrivyScannerProperties(
            "trivy-bin", 300, "db", "java-db", this.tempDir.toString(), Duration.ofHours(12));
    final var mapper = JsonMapper.builder().build();
    final TrivyCommandRunner runner =
        (command, timeoutSeconds) -> {
          if (command.get(1).equals("version")) {
            return "{\"Version\":\"0.66.0\"}";
          }

          this.commands.add(command);
          final var file = Path.of(command.get(command.size() - 1));
          this.sbomFiles.add(file);

          try {
            this.sboms.add(Files.readString(file));
          } catch (final IOException exception) {
            throw new IllegalStateException(exception);
          }

          return this.onSbom.apply(command, timeoutSeconds);
        };
    final var scanService =
        new TrivyScanService(
            trivyProperties, mapper, new InMemoryJobStore(), runner, this.access, Runnable::run);

    return new AdvisoryLookupService(
        trivyProperties,
        new AdvisoryProperties(20_000, 10 * 1024 * 1024, 30, concurrency, maxWaitSeconds),
        mapper,
        runner,
        this.access,
        new TrivyDatabaseMetadata(trivyProperties, mapper),
        new CycloneDxSbomWriter(mapper),
        scanService);
  }

  private static AdvisoryRequest npm(final AdvisoryPackage... packages) {
    return new AdvisoryRequest("npm", List.of(packages));
  }

  @Test
  void runsTrivyOnAnSbomOfTheOfflineDatabaseAndMapsTheFindings() {
    final var response = this.service.lookup(npm(new AdvisoryPackage("lodash", "4.17.20")));

    assertThat(this.commands).hasSize(1);
    final var command = this.commands.getFirst();
    assertThat(command)
        .containsSubsequence(
            "trivy-bin",
            "sbom",
            "--format",
            "json",
            "--quiet",
            "--cache-dir",
            this.tempDir.toString(),
            "--offline-scan",
            "--skip-db-update",
            "--skip-java-db-update");
    assertThat(command.getLast()).endsWith(".cdx.json");
    assertThat(this.sboms.getFirst()).contains("\"pkg:npm/lodash@4.17.20\"");

    assertThat(response.dbUpdatedAt()).isEqualTo(DB_UPDATED);
    assertThat(response.scannerVersion()).isEqualTo("0.66.0");
    assertThat(response.findings()).hasSize(1);
    final var finding = response.findings().getFirst();
    assertThat(finding.cveId()).isEqualTo("CVE-2021-23337");
    assertThat(finding.packageName()).isEqualTo("lodash");
    assertThat(finding.packageVersion()).isEqualTo("4.17.20");
    assertThat(finding.fixedVersion()).isEqualTo("4.17.21");
    assertThat(finding.severity()).isEqualTo(Severity.HIGH);
    assertThat(finding.fixStatus()).isEqualTo(FixStatus.FIXED);
  }

  @Test
  void answersNoFindingsForAReportWithoutVulnerabilities() {
    this.onSbom = (command, timeout) -> "{\"Results\":[{\"Target\":\"Node.js\"}]}";

    assertThat(this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))).findings()).isEmpty();
  }

  @Test
  void deletesTheSbomAfterwardsEvenWhenTrivyFails() {
    this.onSbom =
        (command, timeout) -> {
          throw new TrivyScanException("trivy exited with code 1: boom");
        };

    assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
        .isExactlyInstanceOf(TrivyScanException.class)
        .hasMessage("Advisory lookup failed");

    assertThat(this.sbomFiles).hasSize(1);
    assertThat(this.sbomFiles.getFirst()).doesNotExist();
  }

  @Test
  void looksUpEachPairOnceEvenWhenTheRequestRepeatsIt() {
    this.service.lookup(
        npm(
            new AdvisoryPackage("ms", "2.0.0"),
            new AdvisoryPackage("ms", "2.1.3"),
            new AdvisoryPackage("ms", "2.0.0")));

    assertThat(this.sboms.getFirst().split("\"purl\"", -1)).hasSize(3);
  }

  @Test
  void answersAnEmptyRequestWithoutRunningTrivy() {
    final var response = this.service.lookup(npm());

    assertThat(response.findings()).isEmpty();
    assertThat(response.dbUpdatedAt()).isEqualTo(DB_UPDATED);
    assertThat(this.commands).isEmpty();
  }

  @Test
  void refusesAnEcosystemOtherThanNpm() {
    assertThatThrownBy(
            () ->
                this.service.lookup(
                    new AdvisoryRequest("maven", List.of(new AdvisoryPackage("a", "1")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ecosystem");
    assertThatThrownBy(() -> this.service.lookup(new AdvisoryRequest(null, List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> this.service.lookup(new AdvisoryRequest("npm", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("packages");
  }

  @Test
  void refusesMalformedPairs() {
    for (final var pair :
        List.of(
            new AdvisoryPackage(null, "1.0.0"),
            new AdvisoryPackage(" ", "1.0.0"),
            new AdvisoryPackage("a", null),
            new AdvisoryPackage("a", ""),
            new AdvisoryPackage("a", "1.0\n.0"),
            new AdvisoryPackage("a".repeat(215), "1.0.0"),
            new AdvisoryPackage("a", "1".repeat(257)),
            new AdvisoryPackage("@scope", "1.0.0"),
            new AdvisoryPackage("@/name", "1.0.0"),
            new AdvisoryPackage("@scope/", "1.0.0"),
            new AdvisoryPackage("scope/name", "1.0.0"),
            new AdvisoryPackage("@a/b/c", "1.0.0"))) {
      assertThatThrownBy(() -> this.service.lookup(npm(pair)))
          .as("%s", pair)
          .isInstanceOf(IllegalArgumentException.class);
    }

    final var withNull = new ArrayList<AdvisoryPackage>();
    withNull.add(null);
    assertThatThrownBy(() -> this.service.lookup(new AdvisoryRequest("npm", withNull)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(this.commands).isEmpty();
  }

  @Test
  void acceptsScopedAndUnscopedNamesAndTheLongestAllowedValues() {
    this.service.lookup(
        npm(
            new AdvisoryPackage("@babel/traverse", "7.20.0"),
            new AdvisoryPackage("lodash", "4.17.20"),
            new AdvisoryPackage("a".repeat(214), "1".repeat(256))));

    assertThat(this.commands).hasSize(1);
  }

  @Test
  void refusesMoreThan20000PairsAndAcceptsExactly20000() {
    final var pairs = new ArrayList<AdvisoryPackage>();
    for (var i = 0; i < 20_000; i++) {
      pairs.add(new AdvisoryPackage("p" + i, "1.0.0"));
    }

    this.service.lookup(new AdvisoryRequest("npm", pairs));
    assertThat(this.commands).hasSize(1);

    pairs.add(new AdvisoryPackage("one-more", "1.0.0"));
    assertThatThrownBy(() -> this.service.lookup(new AdvisoryRequest("npm", pairs)))
        .isInstanceOf(AdvisoryTooLargeException.class);
    assertThat(this.commands).hasSize(1);
  }

  @Test
  void isUnavailableWithoutAVulnerabilityDatabase() throws IOException {
    Files.delete(this.tempDir.resolve("db/trivy.db"));

    assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
        .isInstanceOf(AdvisoryUnavailableException.class)
        .hasMessageContaining("not been downloaded");
    assertThat(this.commands).isEmpty();
  }

  @Test
  void isUnavailableWhileAScanHasTheDatabase() {
    try (var ignored = this.access.enterScan()) {
      assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
          .isInstanceOf(AdvisoryUnavailableException.class)
          .hasMessageContaining("in use by a scan");
    }

    assertThat(this.commands).isEmpty();
  }

  @Test
  void isUnavailableWhileTheDatabaseIsBeingRefreshed() {
    try (var ignored = this.access.enterRefresh()) {
      assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
          .isInstanceOf(AdvisoryUnavailableException.class);
    }
  }

  @Test
  void isUnavailableWhenTheLookupsAlreadyInProgressFillTheAdmission() throws Exception {
    final var service = this.serviceWith(1, 0);
    final var inTrivy = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    this.onSbom =
        (command, timeout) -> {
          inTrivy.countDown();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return LODASH_REPORT;
        };

    final var first =
        CompletableFuture.supplyAsync(() -> service.lookup(npm(new AdvisoryPackage("a", "1"))));
    assertThat(inTrivy.await(10, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> service.lookup(npm(new AdvisoryPackage("b", "1"))))
        .isInstanceOf(AdvisoryUnavailableException.class)
        .hasMessageContaining("Too many");

    release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS).findings()).hasSize(1);

    // the permit is given back: the next lookup is served
    assertThat(service.lookup(npm(new AdvisoryPackage("c", "1"))).findings()).hasSize(1);
  }

  @Test
  void answersWhenTwoLookupsRunAtOnce() throws Exception {
    final var bothInTrivy = new CountDownLatch(2);
    this.onSbom =
        (command, timeout) -> {
          bothInTrivy.countDown();
          try {
            assertThat(bothInTrivy.await(10, TimeUnit.SECONDS)).isTrue();
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return LODASH_REPORT;
        };

    final var first =
        CompletableFuture.supplyAsync(
            () -> this.service.lookup(npm(new AdvisoryPackage("a", "1"))));
    final var second =
        CompletableFuture.supplyAsync(
            () -> this.service.lookup(npm(new AdvisoryPackage("b", "1"))));

    assertThat(first.get(15, TimeUnit.SECONDS).findings()).hasSize(1);
    assertThat(second.get(15, TimeUnit.SECONDS).findings()).hasSize(1);
  }

  @Test
  void reportsATimeoutOfTrivyAndGivesTheLookupPermitBack() {
    this.onSbom =
        (command, timeout) -> {
          throw new TrivyTimeoutException("Trivy scan timed out after 30s");
        };

    assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
        .isInstanceOf(AdvisoryTimeoutException.class)
        .hasMessageContaining("30s");

    // a scan can enter at once: the lookup released the database
    this.access.enterScan().close();
  }

  @Test
  void passesTheConfiguredTimeoutToTrivy() {
    final var timeouts = new ArrayList<Long>();
    this.onSbom =
        (command, timeout) -> {
          timeouts.add(timeout);
          return LODASH_REPORT;
        };

    this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3")));

    assertThat(timeouts).containsExactly(30L);
  }

  @Test
  void isUnavailableWhenTrivyFindsTheDatabaseBeingReplacedOrInUse() {
    for (final var message :
        List.of(
            "trivy exited with code 1: DB error: --skip-db-update cannot be specified on the first run",
            "trivy exited with code 1: vulnerability database may be in use by another process: timeout")) {
      this.onSbom =
          (command, timeout) -> {
            throw new TrivyScanException(message);
          };

      assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
          .as(message)
          .isInstanceOf(AdvisoryUnavailableException.class);
    }
  }

  @Test
  void failsWithAGenericMessageWhenTrivyPrintsNoJson() {
    this.onSbom = (command, timeout) -> "not json";

    assertThatThrownBy(() -> this.service.lookup(npm(new AdvisoryPackage("ms", "2.1.3"))))
        .isExactlyInstanceOf(TrivyScanException.class)
        .hasMessage("Advisory lookup failed");
  }
}
