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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.errors.TrivyTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTrivyCommandRunnerTest {

  @TempDir Path tempDir;

  private final ProcessTrivyCommandRunner runner = new ProcessTrivyCommandRunner();

  private String script(final String body) throws IOException {
    final var file = this.tempDir.resolve("trivy");
    Files.writeString(file, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file.toString();
  }

  @Test
  void returnsTheStandardOutputOfASuccessfulRun() throws IOException {
    final var trivy = this.script("echo \"out $1\"");

    assertThat(this.runner.run(List.of(trivy, "arg"), 30)).isEqualTo("out arg\n");
  }

  @Test
  void failsWithTheStandardErrorOfARunThatExitsNonZero() throws IOException {
    final var trivy = this.script("echo 'DB error: boom' >&2\nexit 3");

    assertThatThrownBy(() -> this.runner.run(List.of(trivy), 30))
        .isExactlyInstanceOf(TrivyScanException.class)
        .hasMessage("trivy exited with code 3: DB error: boom\n");
  }

  @Test
  void failsWhenTheBinaryCannotBeStarted() {
    assertThatThrownBy(
            () -> this.runner.run(List.of(this.tempDir.resolve("missing").toString()), 30))
        .isExactlyInstanceOf(TrivyScanException.class)
        .hasMessage("Failed to start trivy process");
  }

  @Test
  void killsARunThatTakesLongerThanTheTimeoutAndReportsIt() throws IOException {
    // a child process of the script keeps the output pipes open: it must be killed as well
    final var trivy = this.script("sleep 60\necho done");
    final var started = System.nanoTime();

    assertThatThrownBy(() -> this.runner.run(List.of(trivy), 1))
        .isExactlyInstanceOf(TrivyTimeoutException.class)
        .hasMessage("Trivy scan timed out after 1s");

    assertThat(System.nanoTime() - started).isLessThan(20_000_000_000L);
  }

  @Test
  void truncatesALongErrorMessage() throws IOException {
    final var trivy = this.script("head -c 5000 /dev/zero | tr '\\0' x >&2\nexit 1");

    assertThatThrownBy(() -> this.runner.run(List.of(trivy), 30))
        .hasMessageEndingWith("... [truncated]")
        .satisfies(exception -> assertThat(exception.getMessage().length()).isLessThan(2000));
  }
}
