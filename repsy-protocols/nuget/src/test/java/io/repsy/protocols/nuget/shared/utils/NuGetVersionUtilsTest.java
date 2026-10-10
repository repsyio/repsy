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
package io.repsy.protocols.nuget.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NuGetVersionUtilsTest {

  @ParameterizedTest
  @CsvSource({
    "1, 1.0.0",
    "1.0, 1.0.0",
    "1.2, 1.2.0",
    "1.2.3, 1.2.3",
    "1.0.0.0, 1.0.0",
    "1.2.3.4, 1.2.3.4",
    "1.0.0.5, 1.0.0.5",
    "1.0-Alpha, 1.0.0-alpha",
    "1.0.0-Alpha.1, 1.0.0-alpha.1",
    "1.0+Build, 1.0.0",
    "1.0.0.0+Build, 1.0.0",
    "1.2.3.4+Build, 1.2.3.4",
    "1.0-beta+Build, 1.0.0-beta",
    "1.0.0-rc.1+build.5, 1.0.0-rc.1",
    "1.0.0-beta+build-1, 1.0.0-beta",
    "1.0.0+a-b, 1.0.0",
    "1.0.0+a, 1.0.0",
    "1.0.0+b, 1.0.0",
  })
  @DisplayName("normalizes a version to its canonical three-part form, without build metadata")
  void normalizesVersion(final String raw, final String expected) {
    assertThat(NuGetVersionUtils.normalizeNuGetVersion(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "1.0.0, false",
    "1.2.3.4, false",
    "1.0.0-beta, false",
    "1.0.0-beta2, false",
    "1.0.0-a-b, false",
    "1.0.0-beta.1, true",
    "1.0.0-RC.1, true",
    "1.0.0-a-b.c, true",
    "1.0.0+build, true",
    "1.0.0-beta+build, true",
    "1.0.0-beta.1+build.7, true",
  })
  @DisplayName(
      "tells a SemVer 2.0.0-only version from one a SemVer 1.0.0 client can use (RPS-1275)")
  void detectsSemVer2(final String version, final boolean expected) {
    assertThat(NuGetVersionUtils.isSemVer2(version)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "2.0.0, true",
    "2.0.0-beta, true",
    " 2.0.0 , true",
    "2, true",
    "10.0.0, true",
    "1.0.0, false",
    "1, false",
    "0.9, false",
    "abc, false",
    "'', false",
    "-2.0.0, false",
    "2abc, false",
  })
  @DisplayName("opts in to SemVer 2.0.0 for a semVerLevel of 2.0.0 or more (RPS-1275)")
  void acceptsSemVer2(final String level, final boolean expected) {
    assertThat(NuGetVersionUtils.acceptsSemVer2(level)).isEqualTo(expected);
  }

  @Test
  @DisplayName("does not opt in to SemVer 2.0.0 without a semVerLevel (RPS-1275)")
  void noSemVerLevel() {
    assertThat(NuGetVersionUtils.acceptsSemVer2(null)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "1.0.0.5, 1.0.0.6",
    "1.0.0.5, 1.0.0.10",
    "1.0.0, 1.0.0.1",
    "1.0.0.1, 1.0.1",
    "1.9.9.9, 2.0.0",
    "1.0.0.5-beta, 1.0.0.5",
    "1.0.0-beta, 1.0.0.1-alpha",
    "1.0.0.5-alpha, 1.0.0.5-beta",
    "1.0.0-alpha, 1.0.0",
    "1.0.0-beta.2, 1.0.0-beta.11",
    "1.0.0-Alpha, 1.0.0-beta",
    "1.2, 1.10",
    "2, 10.0.0",
  })
  @DisplayName("orders the first version before the second")
  void ordersVersions(final String lower, final String higher) {
    assertThat(NuGetVersionUtils.compareVersions(lower, higher)).isNegative();
    assertThat(NuGetVersionUtils.compareVersions(higher, lower)).isPositive();
  }

  @ParameterizedTest
  @CsvSource({
    "1.0, 1.0.0",
    "1.0.0.0, 1.0.0",
    "1.0.0+a, 1.0.0+b",
    "1.0.0.5, 1.0.0.5+build",
    "1.0.0-Beta, 1.0.0-beta",
  })
  @DisplayName("treats versions NuGet considers the same as equal")
  void comparesEqualVersions(final String first, final String second) {
    assertThat(NuGetVersionUtils.compareVersions(first, second)).isZero();
    assertThat(NuGetVersionUtils.compareVersions(second, first)).isZero();
  }

  @ParameterizedTest
  @CsvSource({"a.b.c, 1.0.0", "1.0.0, a.b.c", "1.2.3.4.5, 1.2.3.4", ".1, 1.0.0", "'', 1.0.0"})
  @DisplayName("falls back to a case-insensitive string comparison for an unparseable version")
  void fallsBackForUnparseableVersion(final String first, final String second) {
    assertThat(NuGetVersionUtils.compareVersions(first, second))
        .isEqualTo(first.compareToIgnoreCase(second));
  }
}
