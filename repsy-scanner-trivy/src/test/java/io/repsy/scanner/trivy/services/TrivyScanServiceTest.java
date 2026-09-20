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
    assertThat(args).endsWith("--", "--config=/etc/passwd");
    assertThat(args).containsSubsequence("image", "--format", "json");
  }
}
