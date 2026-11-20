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
package io.repsy.os.shared.connector.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PkceUtils")
class PkceUtilsTest {

  /** The example of RFC 7636, appendix B. */
  private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

  private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

  @Test
  @DisplayName("computes the S256 challenge of the RFC 7636 example")
  void computesTheChallengeOfTheRfcExample() {
    assertThat(PkceUtils.challengeOf(VERIFIER)).isEqualTo(CHALLENGE);
  }

  @Test
  @DisplayName("verifies a verifier against its challenge and nothing else")
  void verifiesOnlyTheMatchingVerifier() {
    assertThat(PkceUtils.verify(VERIFIER, CHALLENGE)).isTrue();
    assertThat(PkceUtils.verify(VERIFIER.replace('d', 'e'), CHALLENGE)).isFalse();
    assertThat(PkceUtils.verify(VERIFIER, CHALLENGE.replace('E', 'F'))).isFalse();
  }

  @Test
  @DisplayName("refuses a verifier that is too short, too long or holds a reserved character")
  void refusesAMalformedVerifier() {
    final var tooShort = "a".repeat(42);
    final var tooLong = "a".repeat(129);
    final var reserved = "a".repeat(42) + "/";

    assertThat(PkceUtils.verify(tooShort, PkceUtils.challengeOf(tooShort))).isFalse();
    assertThat(PkceUtils.verify(tooLong, PkceUtils.challengeOf(tooLong))).isFalse();
    assertThat(PkceUtils.verify(reserved, PkceUtils.challengeOf(reserved))).isFalse();
  }

  @Test
  @DisplayName("knows an S256 challenge by its shape")
  void knowsAChallengeByItsShape() {
    assertThat(PkceUtils.isChallenge(CHALLENGE)).isTrue();
    assertThat(PkceUtils.isChallenge(CHALLENGE + "=")).isFalse();
    assertThat(PkceUtils.isChallenge(VERIFIER + "x")).isFalse();
    assertThat(PkceUtils.isChallenge("")).isFalse();
  }
}
