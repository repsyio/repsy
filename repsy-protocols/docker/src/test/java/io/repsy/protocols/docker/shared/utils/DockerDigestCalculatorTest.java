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
package io.repsy.protocols.docker.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DockerDigestCalculator")
class DockerDigestCalculatorTest {

  private static final byte[] BYTES = "abc".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("calculates the sha256 digest of the bytes (FIPS 180 test vector for \"abc\")")
  void calculatesSha256() throws Exception {
    assertThat(DockerDigestCalculator.calculateDigest(BYTES))
        .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  @DisplayName("calculates the sha512 digest of the bytes (FIPS 180 test vector for \"abc\")")
  void calculatesSha512() throws Exception {
    assertThat(DockerDigestCalculator.calculateSha512Digest(BYTES))
        .isEqualTo(
            "sha512:ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
  }

  @Test
  @DisplayName("normalizes the hex digits of a digest to lower case")
  void normalizesToLowerCase() {
    assertThat(DockerDigestCalculator.normalize("sha256:ABCDEF0123"))
        .isEqualTo("sha256:abcdef0123");
    assertThat(DockerDigestCalculator.normalize("sha512:abc")).isEqualTo("sha512:abc");
  }

  @Test
  @DisplayName("tells a sha512 digest from a sha256 one")
  void recognizesSha512() {
    assertThat(DockerDigestCalculator.isSha512("sha512:" + "a".repeat(128))).isTrue();
    assertThat(DockerDigestCalculator.isSha512("sha256:" + "a".repeat(64))).isFalse();
  }

  @Test
  @DisplayName("reports a digest reference as sent, hex lower-cased, in its own algorithm")
  void reportsTheReferenceForADigestReference() {
    final var sha256 = "sha256:" + "a".repeat(64);
    final var sha512 = "sha512:" + "b".repeat(128);

    assertThat(DockerDigestCalculator.reportedDigest(sha512, sha256)).isEqualTo(sha512);
    assertThat(DockerDigestCalculator.reportedDigest(sha256, sha256)).isEqualTo(sha256);
    assertThat(DockerDigestCalculator.reportedDigest("sha512:" + "B".repeat(128), sha256))
        .isEqualTo(sha512);
  }

  @Test
  @DisplayName("reports the canonical sha256 digest for a tag")
  void reportsTheCanonicalDigestForATag() {
    final var sha256 = "sha256:" + "a".repeat(64);

    assertThat(DockerDigestCalculator.reportedDigest("latest", sha256)).isEqualTo(sha256);
    assertThat(DockerDigestCalculator.reportedDigest("v1-sha512", sha256)).isEqualTo(sha256);
  }
}
