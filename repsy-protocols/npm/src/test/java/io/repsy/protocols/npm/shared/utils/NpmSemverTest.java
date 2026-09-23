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
package io.repsy.protocols.npm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NpmSemver (RPS-1208)")
class NpmSemverTest {

  @Nested
  @DisplayName("parse")
  class Parse {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "1.2.3",
          "0.0.0",
          "1.0.0-alpha",
          "1.0.0-alpha.1",
          "1.0.0-0.3.7",
          "1.0.0-x.7.z.92",
          "1.0.0+build.1",
          "1.0.0-beta+exp.sha.5114f85",
          // one numeric part above Integer.MAX_VALUE (2147483647): the regression this exists for
          "2147483648.0.0",
          // arbitrary precision beyond a long too
          "99999999999999999999999999999999999999.0.0",
        })
    @DisplayName("accepts every version real npm/node-semver accepts")
    void acceptsValidVersions(final String version) {
      assertThat(NpmSemver.isValid(version)).isTrue();
      assertThat(NpmSemver.parse(version)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "1.2", // needs all three parts
          "1", //
          "v1.2.3", // no leading v
          "1.2.3.4", //
          "01.2.3", // leading zero
          "1.02.3", //
          "1.2.03", //
          "1.2.3-", // empty pre-release
          "", //
          "not-a-version",
        })
    @DisplayName("refuses a syntactically invalid version with the fixed invalidPackageVersion id")
    void refusesInvalidVersions(final String version) {
      assertThat(NpmSemver.isValid(version)).isFalse();
      assertThatThrownBy(() -> NpmSemver.parse(version))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("invalidPackageVersion");
    }

    @Test
    @DisplayName("the version above int32 max parses to the numeric part it declares")
    void aboveInt32MaxParsesCorrectly() {
      final var version = NpmSemver.parse("2147483648.0.0");
      final var oneMore = NpmSemver.parse("2147483649.0.0");

      assertThat(version.compareTo(oneMore)).isLessThan(0);
      assertThat(oneMore.compareTo(version)).isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("compareTo")
  class CompareTo {

    @Test
    @DisplayName("orders major, then minor, then patch numerically")
    void ordersNumerically() {
      assertThat(NpmSemver.parse("2.0.0").compareTo(NpmSemver.parse("10.0.0"))).isLessThan(0);
      assertThat(NpmSemver.parse("1.2.0").compareTo(NpmSemver.parse("1.10.0"))).isLessThan(0);
      assertThat(NpmSemver.parse("1.0.2").compareTo(NpmSemver.parse("1.0.10"))).isLessThan(0);
    }

    @Test
    @DisplayName("a release has higher precedence than its own pre-release")
    void releaseBeatsPreRelease() {
      assertThat(NpmSemver.parse("1.0.0").compareTo(NpmSemver.parse("1.0.0-alpha")))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("pre-release identifiers compare numeric-before-alphanumeric, per semver.org #11")
    void preReleasePrecedenceOrder() {
      // 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2
      // < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
      final var ordered =
          new String[] {
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
          };

      for (var i = 0; i < ordered.length - 1; i++) {
        assertThat(NpmSemver.parse(ordered[i]).compareTo(NpmSemver.parse(ordered[i + 1])))
            .as("%s should be < %s", ordered[i], ordered[i + 1])
            .isLessThan(0);
      }
    }

    @Test
    @DisplayName("build metadata plays no part in precedence")
    void buildMetadataIgnored() {
      assertThat(NpmSemver.parse("1.0.0+build1").compareTo(NpmSemver.parse("1.0.0+build2")))
          .isZero();
    }

    @Test
    @DisplayName("a huge numeric part still compares correctly against a small one")
    void hugeNumericPartComparesCorrectly() {
      assertThat(
              NpmSemver.parse("99999999999999999999999999999999999999.0.0")
                  .compareTo(NpmSemver.parse("2.0.0")))
          .isGreaterThan(0);
    }
  }
}
