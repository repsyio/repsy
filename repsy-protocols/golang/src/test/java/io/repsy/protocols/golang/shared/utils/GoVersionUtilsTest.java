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
package io.repsy.protocols.golang.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GoVersionUtils")
class GoVersionUtilsTest {

  @ParameterizedTest
  @CsvSource({
    "github.com/!burnt!sushi/toml, github.com/BurntSushi/toml",
    "github.com/google/uuid, github.com/google/uuid",
    "'', ''",
    "!a!b!c, ABC"
  })
  @DisplayName("decodeModulePath() turns Go's !x escapes into uppercase letters")
  void decodesUppercaseEscapes(final String encoded, final String expected) {
    assertThat(GoVersionUtils.decodeModulePath(encoded)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "github.com/BurntSushi/toml, github.com/!burnt!sushi/toml",
    "github.com/google/uuid, github.com/google/uuid",
    "'', ''",
    "ABC, !a!b!c"
  })
  @DisplayName(
      "escapeModulePath() turns upper-case letters into !x escapes (RPS-1232), the exact inverse of"
          + " decodeModulePath()")
  void escapesUppercaseLetters(final String decoded, final String expected) {
    assertThat(GoVersionUtils.escapeModulePath(decoded)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "github.com/BurntSushi/toml",
        "github.com/google/uuid",
        "example.com/Foo/Bar",
        "example.com/e2e-mixed"
      })
  @DisplayName(
      "escapeModulePath() and decodeModulePath() round-trip: escaping never needs a"
          + " case-sensitive filesystem, and decoding it back recovers the original path (RPS-1232)")
  void escapeAndDecodeRoundTrip(final String decoded) {
    final var escaped = GoVersionUtils.escapeModulePath(decoded);

    assertThat(escaped).isEqualTo(escaped.toLowerCase(Locale.ROOT));
    assertThat(GoVersionUtils.decodeModulePath(escaped)).isEqualTo(decoded);
  }

  @Test
  @DisplayName(
      "escapeModulePath() is the identity for an already all-lower-case path (RPS-1232): a module"
          + " stored before this encoding was introduced keeps its storage path unchanged")
  void escapeIsIdentityForLowerCasePaths() {
    final var lowerCasePath = "github.com/burntsushi/toml";

    assertThat(GoVersionUtils.escapeModulePath(lowerCasePath)).isEqualTo(lowerCasePath);
  }

  private static byte[] goMod(final String goDirective) {
    return ("module example.com/demo\n\n" + goDirective + "\n").getBytes(StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @CsvSource({"go 1.21, 1.21", "go 1.21.0 // toolchain note, 1.21.0", "go   1.23rc1, 1.23rc1"})
  @DisplayName("extractGoVersionFromMod() reads the version of the go directive")
  void readsTheGoDirective(final String directive, final String expected) {
    assertThat(GoVersionUtils.extractGoVersionFromMod(goMod(directive))).isEqualTo(expected);
  }

  @Test
  @DisplayName("extractGoVersionFromMod() is null when there is no go directive")
  void noGoDirective() {
    assertThat(GoVersionUtils.extractGoVersionFromMod(goMod("require a.b/c v1.0.0"))).isNull();
  }

  @Test
  @DisplayName("extractGoVersionFromMod() keeps a go version of exactly the column length")
  void keepsAGoVersionAtTheLimit() {
    final var version = "1".repeat(GoVersionUtils.MAX_GO_VERSION_LENGTH);

    assertThat(GoVersionUtils.extractGoVersionFromMod(goMod("go " + version))).isEqualTo(version);
  }

  @Test
  @DisplayName("extractGoVersionFromMod() drops a go version one character over the column")
  void dropsAGoVersionOverTheLimit() {
    final var version = "1".repeat(GoVersionUtils.MAX_GO_VERSION_LENGTH + 1);

    assertThat(GoVersionUtils.extractGoVersionFromMod(goMod("go " + version))).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v0.0.1",
        "v1.2.3",
        "v1.2.3+incompatible",
        "v2.0.0",
        "v1.0.0-20240101120000-0123456789ab",
        "v1.0.0-beta.1",
        "v1.0.0-rc1+build.5"
      })
  @DisplayName(
      "isValidSemver() accepts real Go semver strings, including build metadata and"
          + " pseudo-versions (RPS-1227)")
  void isValidSemverAcceptsRealGoVersions(final String version) {
    assertThat(GoVersionUtils.isValidSemver(version)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "banana",
        "1.0.0",
        "v1.0",
        "v1.0.0.0",
        "v01.0.0",
        "v1.0.0-",
        "v1.0.0+",
        "",
        "vv1.0.0"
      })
  @DisplayName("isValidSemver() rejects anything that is not a well-formed Go semver string")
  void isValidSemverRejectsInvalidVersions(final String version) {
    assertThat(GoVersionUtils.isValidSemver(version)).isFalse();
  }

  @Test
  @DisplayName(
      "COMPARATOR compares a 25-digit numeric field without overflowing (regression for the"
          + " Long.parseLong overflow)")
  void comparatorHandlesArbitrarilyLongNumericFieldsWithoutOverflow() {
    final var huge = "v" + "1".repeat(25) + ".0.0";

    assertThat(GoVersionUtils.COMPARATOR.compare(huge, "v9.9.9")).isPositive();
    assertThat(GoVersionUtils.COMPARATOR.compare("v9.9.9", huge)).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare(huge, huge)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1.0.0-rc1", "v1.0.0-beta.1", "v1.0.0-20240101120000-0123456789ab"})
  @DisplayName("hasPreRelease() is true for any version with a '-' suffix, pseudo or not")
  void hasPreReleaseIsTrueForAnyPreReleaseSuffix(final String version) {
    assertThat(GoVersionUtils.hasPreRelease(version)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1.0.0", "v2.3.4+incompatible", "banana"})
  @DisplayName("hasPreRelease() is false for a release, build-metadata-only, or malformed version")
  void hasPreReleaseIsFalseWithoutAPreReleaseSuffix(final String version) {
    assertThat(GoVersionUtils.hasPreRelease(version)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "v1.0.0-20240101120000-0123456789ab",
        "v0.0.0-0.20240101120000-0123456789ab",
        "v1.0.0-20240101120000-0123456789abcdef"
      })
  @DisplayName(
      "isPseudoVersion() recognizes the 14-digit-timestamp + 12+-hex-commit shape (RPS-1720 C8)")
  void isPseudoVersionRecognizesThePseudoShape(final String version) {
    assertThat(GoVersionUtils.isPseudoVersion(version)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1.0.0", "v1.0.0-rc1", "v1.0.0-beta.1", "v1.0.0+incompatible", "banana"})
  @DisplayName(
      "isPseudoVersion() is false for a release, a tagged pre-release, or a malformed version")
  void isPseudoVersionIsFalseForNonPseudoVersions(final String version) {
    assertThat(GoVersionUtils.isPseudoVersion(version)).isFalse();
  }

  @Test
  @DisplayName("COMPARATOR puts a pre-release before its release, whichever side it is on")
  void comparatorOrdersPreReleaseBeforeRelease() {
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0-beta", "v1.0.0")).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0", "v1.0.0-beta")).isPositive();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0", "v1.0.0")).isZero();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.2.0-rc1", "v1.10.0-rc1")).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.01", "v1.0.1")).isZero();
  }

  @Test
  @DisplayName("COMPARATOR orders pseudo-versions by timestamp and other pre-releases by text")
  void comparatorOrdersPseudoVersionsByTimestamp() {
    final var older = "v0.0.0-20230101000000-aaaaaaaaaaaa";
    final var newer = "v0.0.0-20240101000000-000000000000";

    assertThat(GoVersionUtils.COMPARATOR.compare(older, newer)).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare(newer, older)).isPositive();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0-alpha", "v1.0.0-beta")).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0-beta", older)).isPositive();
  }

  @Test
  @DisplayName("COMPARATOR falls back to text for a string that is not a semantic version")
  void comparatorFallsBackToText() {
    assertThat(GoVersionUtils.COMPARATOR.compare("banana", "v1.0.0")).isNegative();
    assertThat(GoVersionUtils.COMPARATOR.compare("v1.0.0", "banana")).isPositive();
  }

  @Test
  @DisplayName("latestOf takes the highest release, then the highest tagged pre-release")
  void latestOfPrefersReleasesThenTaggedPreReleases() {
    assertThat(GoVersionUtils.latestOf(List.of("v1.0.0", "v1.2.0-beta", "v1.1.0")))
        .contains("v1.1.0");
    assertThat(
            GoVersionUtils.latestOf(
                List.of("v1.2.0-beta", "v1.2.0-rc1", "v0.0.0-20240101000000-0123456789ab")))
        .contains("v1.2.0-rc1");
  }

  @Test
  @DisplayName("latestOf takes the highest pseudo-version only when nothing is tagged")
  void latestOfFallsBackToPseudoVersions() {
    assertThat(
            GoVersionUtils.latestOf(
                List.of(
                    "v0.0.0-20230101000000-aaaaaaaaaaaa", "v0.0.0-20240101000000-bbbbbbbbbbbb")))
        .contains("v0.0.0-20240101000000-bbbbbbbbbbbb");
    assertThat(GoVersionUtils.latestOf(List.of())).isEqualTo(Optional.empty());
  }

  @ParameterizedTest
  @CsvSource({
    "/io.repsy/hello/@v/v1.2.0.zip, v1.2.0",
    "/io.repsy/hello/@v/v1.2.0.mod, v1.2.0",
    "/io.repsy/hello/@v/v1.2.0.info, v1.2.0",
    "/io.repsy/hello/@v/v1.2.0, v1.2.0",
    "/io.repsy/hello/@v/list, list",
    "v1.2.0.zip, v1.2.0"
  })
  @DisplayName("extractVersionFromPath drops the directory and a known extension")
  void extractsTheVersionFromAPath(final String path, final String expected) {
    assertThat(GoVersionUtils.extractVersionFromPath(path)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"/github.com/foo/bar/@v/v1.2.3.zip, github.com/foo/bar", "/a/@v/list, a"})
  @DisplayName("extractModulePath reads the module path before /@v/")
  void extractsTheModulePath(final String path, final String expected) {
    assertThat(GoVersionUtils.extractModulePath(path)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/github.com/foo/bar", "/@v/v1.0.0", "", "/x@v/v1"})
  @DisplayName("extractModulePath is null without a module before /@v/")
  void noModulePath(final String path) {
    assertThat(GoVersionUtils.extractModulePath(path)).isNull();
  }
}
