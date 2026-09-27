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
package io.repsy.protocols.helm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HelmVersionComparator (RPS-1614)")
class HelmVersionComparatorTest {

  private static List<String> sorted(final String... versions) {
    return List.of(versions).stream().sorted(HelmVersionComparator.INSTANCE).toList();
  }

  @Test
  @DisplayName("compares the numbers as numbers, not as strings")
  void numbersAreNumeric() {
    assertThat(sorted("1.10.0", "1.9.0", "1.2.0")).containsExactly("1.2.0", "1.9.0", "1.10.0");
  }

  @Test
  @DisplayName("a pre-release sorts below its release, and its identifiers by SemVer precedence")
  void preReleasesSortBelowTheRelease() {
    assertThat(
            sorted(
                "1.0.0",
                "1.0.0-rc.1",
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-beta.11",
                "1.0.0-beta.2",
                "1.0.0-rc.1.1"))
        .containsExactly(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0-rc.1.1",
            "1.0.0");
  }

  @Test
  @DisplayName("build metadata does not count, and the string breaks the tie")
  void buildMetadataIsATieBrokenByTheString() {
    assertThat(sorted("1.0.0+b", "1.0.0+a", "1.0.0"))
        .containsExactly("1.0.0", "1.0.0+a", "1.0.0+b");
    assertThat(HelmVersionComparator.INSTANCE.compare("1.0.0+a", "1.0.0+b")).isNegative();
    assertThat(HelmVersionComparator.INSTANCE.compare("1.0.0", "1.0.0")).isZero();
  }

  @Test
  @DisplayName("a value that is not SemVer sorts below every version and among its kind by string")
  void aValueThatIsNotSemVerSortsFirst() {
    assertThat(sorted("0.0.1", "zzz", "aaa")).containsExactly("aaa", "zzz", "0.0.1");
  }
}
