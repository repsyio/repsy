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
package io.repsy.protocols.ruby.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RubyGemVersionComparator (RPS-1688)")
class RubyGemVersionComparatorTest {

  private final RubyGemVersionComparator comparator = new RubyGemVersionComparator();

  @Test
  @DisplayName("orders 10.0.0 after 9.0.0, unlike a plain string sort")
  void ordersByNumericPrecedenceNotString() {

    assertThat(comparator.compare("10.0.0", "9.0.0")).isPositive();
    assertThat(comparator.compare("9.0.0", "10.0.0")).isNegative();

    // A plain string sort gets this backwards: "10.0.0" < "9.0.0" lexicographically.
    assertThat("10.0.0".compareTo("9.0.0")).isNegative();

    final var sorted = List.of("10.0.0", "9.0.0", "1.2.0").stream().sorted(comparator).toList();
    assertThat(sorted).containsExactly("1.2.0", "9.0.0", "10.0.0");
  }

  @Test
  @DisplayName("a pre-release segment sorts before the release it precedes")
  void preReleaseSortsBeforeRelease() {
    assertThat(comparator.compare("1.0.0.rc1", "1.0.0")).isNegative();
    assertThat(comparator.compare("1.0.0", "1.0.0.rc1")).isPositive();
  }

  @Test
  @DisplayName("a shorter version with equal leading segments sorts before a longer one")
  void shorterPrefixSortsBefore() {
    assertThat(comparator.compare("1.0", "1.0.1")).isNegative();
    assertThat(comparator.compare("1.0", "1.0.0")).isZero();
  }

  @Test
  @DisplayName("equal versions compare as equal")
  void equalVersionsCompareEqual() {
    assertThat(comparator.compare("1.2.3", "1.2.3")).isZero();
  }
}
