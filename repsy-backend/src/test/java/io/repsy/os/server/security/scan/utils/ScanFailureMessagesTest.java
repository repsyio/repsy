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
package io.repsy.os.server.security.scan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScanFailureMessagesTest {

  @Test
  @DisplayName("null stays null")
  void nullStaysNull() {
    assertThat(ScanFailureMessages.sanitize(null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\n\n", "\t"})
  @DisplayName("blank text is nothing to show")
  void blankIsNull(final String raw) {
    assertThat(ScanFailureMessages.sanitize(raw)).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "stub scanner: simulated scan failure|stub scanner: simulated scan failure",
        "Scanner adapter returned error: 503 SERVICE_UNAVAILABLE|Scanner adapter returned error: 503 SERVICE_UNAVAILABLE",
        "Scan exceeded maximum duration|Scan exceeded maximum duration",
        "Scanner restarted, job lost, please retry|Scanner restarted, job lost, please retry",
        "No scanner registered for repo type: NPM|No scanner registered for repo type: NPM",
        "artifactNotFound|artifactNotFound",
        "Scan executor is saturated; retry later|Scan executor is saturated; retry later",
      })
  @DisplayName("the backend's own messages and the stub's pass through untouched")
  void curatedMessagesPassThrough(final String raw, final String expected) {
    assertThat(ScanFailureMessages.sanitize(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "failed to reach http://scanner-stub:8090/scan/abc now|failed to reach [redacted] now",
        "GET https://user:pw@scanner.internal.example/scan failed|GET [redacted] failed",
        "Connection refused: scanner-stub/172.18.0.3:8090|Connection refused: [redacted]",
        "connect to 10.0.0.7 timed out|connect to [redacted] timed out",
        "cannot resolve scanner.svc.cluster.local:8443 here|cannot resolve [redacted] here",
        "could not open /tmp/trivy-123/cache/db.json for reading|could not open [redacted] for reading",
        "read /var/lib/repsy/storage/uuid/file.jar: no such file|read [redacted]: no such file",
        "cannot read C:\\Users\\svc\\trivy\\db|cannot read [redacted]",
        "connect to [fe80::1]:8090 failed|connect to [redacted] failed",
      })
  @DisplayName("URLs, addresses, host:port pairs and file paths are redacted")
  void redactsInternals(final String raw, final String expected) {
    assertThat(ScanFailureMessages.sanitize(raw)).isEqualTo(expected);
  }

  @Test
  @DisplayName("keeps the first line only: no stack trace or scanner stderr reaches the panel")
  void keepsFirstLine() {
    final var raw =
        "\n  trivy failed: unsupported artifact\n\tat io.repsy.Foo.bar(Foo.java:12)\nCaused by: x";

    assertThat(ScanFailureMessages.sanitize(raw)).isEqualTo("trivy failed: unsupported artifact");
  }

  @Test
  @DisplayName("strips control characters and collapses whitespace")
  void stripsControlCharacters() {
    assertThat(ScanFailureMessages.sanitize("a\u0000b\u001b[31m   c\t\td"))
        .isEqualTo("a b [31m c d");
  }

  @Test
  @DisplayName("bounds the length and marks the cut")
  void boundsLength() {
    final var sanitized = ScanFailureMessages.sanitize("word ".repeat(200));

    assertThat(sanitized).hasSizeLessThanOrEqualTo(ScanFailureMessages.MAX_LENGTH).endsWith("...");
    assertThat(sanitized).startsWith("word word");
  }

  @Test
  @DisplayName("a text of exactly the limit is not cut")
  void limitIsInclusive() {
    final var exact = "x".repeat(ScanFailureMessages.MAX_LENGTH);

    assertThat(ScanFailureMessages.sanitize(exact)).isEqualTo(exact);
  }

  @Test
  @DisplayName("is idempotent")
  void idempotent() {
    final var once =
        ScanFailureMessages.sanitize("open /tmp/a/b failed at http://h:1/x " + "y".repeat(300));

    assertThat(ScanFailureMessages.sanitize(once)).isEqualTo(once);
  }
}
