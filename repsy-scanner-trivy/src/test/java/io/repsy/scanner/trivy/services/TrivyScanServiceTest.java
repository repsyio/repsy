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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.ScanJobStatus;
import io.repsy.scanner.trivy.jobs.InMemoryJobStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TrivyScanServiceTest {

  @TempDir Path tempDir;

  @Test
  void endsTheImageCommandWithADoubleDashBeforeTheReference() throws IOException {
    final var argsFile = this.tempDir.resolve("args.txt");
    final var fakeTrivy = this.tempDir.resolve("trivy");
    Files.writeString(
        fakeTrivy,
        "#!/bin/sh\nfor arg in \"$@\"; do echo \"$arg\" >> '" + argsFile + "'; done\necho '{}'\n");
    Files.setPosixFilePermissions(fakeTrivy, PosixFilePermissions.fromString("rwxr-xr-x"));

    final var service =
        new TrivyScanService(
            new TrivyScannerProperties(fakeTrivy.toString(), 30, "db-repo", "java-db-repo"),
            JsonMapper.builder().build(),
            new InMemoryJobStore(),
            Runnable::run);

    service.submitDockerScan("scan-1", "--config=/etc/passwd", null, false, "DOCKER", "img", "1");

    final List<String> args = Files.readAllLines(argsFile);
    assertThat(args).containsSequence("--", "--config=/etc/passwd");
    assertThat(args).containsSubsequence("image", "--format", "json");
  }

  // A fake trivy: `version` runs versionOutput (and counts its calls), everything else prints the
  // report JSON of a scan.
  private TrivyScanService serviceWithFakeTrivy(
      final String versionOutput, final String reportJson, final InMemoryJobStore jobStore)
      throws IOException {

    final var fakeTrivy = this.tempDir.resolve("trivy");
    Files.writeString(
        fakeTrivy,
        "#!/bin/sh\n"
            + "if [ \"$1\" = version ]; then\n"
            + "  echo x >> '"
            + this.tempDir.resolve("version-calls.txt")
            + "'\n"
            + versionOutput
            + "\nelse\n"
            + "  echo '"
            + reportJson
            + "'\nfi\n");
    Files.setPosixFilePermissions(fakeTrivy, PosixFilePermissions.fromString("rwxr-xr-x"));

    return new TrivyScanService(
        new TrivyScannerProperties(fakeTrivy.toString(), 30, "db-repo", "java-db-repo"),
        JsonMapper.builder().build(),
        jobStore,
        Runnable::run);
  }

  private static String scannerVersionOf(final InMemoryJobStore jobStore, final String scanId) {
    final var job = jobStore.get(scanId).orElseThrow();
    assertThat(job.status()).isEqualTo(ScanJobStatus.COMPLETED);
    return job.result().scannerVersion();
  }

  @Test
  void reportsTheTrivyBinaryVersionWhenTheReportHasNone() throws IOException {
    final var jobStore = new InMemoryJobStore();
    final var service =
        this.serviceWithFakeTrivy("echo '{\"Version\":\"0.66.0\"}'", "{\"Results\":[]}", jobStore);

    service.submitDockerScan("scan-1", "img:1", null, false, "DOCKER", "img", "1");
    service.submitDockerScan("scan-2", "img:2", null, false, "DOCKER", "img", "2");

    assertThat(scannerVersionOf(jobStore, "scan-1")).isEqualTo("0.66.0");
    assertThat(scannerVersionOf(jobStore, "scan-2")).isEqualTo("0.66.0");
    // read once, not per scan
    assertThat(Files.readAllLines(this.tempDir.resolve("version-calls.txt"))).hasSize(1);
  }

  @Test
  void prefersAVersionThatTheReportCarries() throws IOException {
    final var jobStore = new InMemoryJobStore();
    final var service =
        this.serviceWithFakeTrivy(
            "echo '{\"Version\":\"0.66.0\"}'",
            "{\"Trivy\":{\"Version\":\"0.70.1\"},\"Results\":[]}",
            jobStore);

    service.submitDockerScan("scan-1", "img:1", null, false, "DOCKER", "img", "1");

    assertThat(scannerVersionOf(jobStore, "scan-1")).isEqualTo("0.70.1");
  }

  @Test
  void completesTheScanWithoutAVersionWhenTheVersionCommandFails() throws IOException {
    final var jobStore = new InMemoryJobStore();
    final var service = this.serviceWithFakeTrivy("exit 3", "{\"Results\":[]}", jobStore);

    service.submitDockerScan("scan-1", "img:1", null, false, "DOCKER", "img", "1");

    assertThat(scannerVersionOf(jobStore, "scan-1")).isNull();
  }
}
