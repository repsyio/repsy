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
package io.repsy.os.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("UsageUtils")
class UsageUtilsTest {

  @ParameterizedTest(name = "{0} bytes -> {1}")
  @DisplayName("humanReadable keeps whole bytes below one KB")
  @CsvSource({"0, 0 B", "1, 1 B", "1023, 1023 B"})
  void bytes(final long usage, final String expected) {
    assertThat(UsageUtils.humanReadable(usage)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} bytes -> {1}")
  @DisplayName("humanReadable rounds up to two decimals within a unit")
  @CsvSource({
    "1024, 1.00 KB",
    "1025, 1.01 KB",
    "1536, 1.50 KB",
    "1048565, 1023.99 KB",
    "1048577, 1.01 MB",
    "1073731338, 1023.99 MB",
    "1073741825, 1.01 GB",
    "1099500890357, 1023.99 GB",
    "1099511627777, 1.01 TB"
  })
  void withinUnit(final long usage, final String expected) {
    assertThat(UsageUtils.humanReadable(usage)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} bytes -> {1}")
  @DisplayName("humanReadable rolls over to the next unit when rounding up reaches 1024.00")
  @CsvSource({
    // First value whose ceiling-rounded quotient is 1024.00, the last byte, and the exact boundary.
    "1048566, 1.00 MB",
    "1048575, 1.00 MB",
    "1048576, 1.00 MB",
    "1073731339, 1.00 GB",
    "1073741823, 1.00 GB",
    "1073741824, 1.00 GB",
    "1099500890358, 1.00 TB",
    "1099511627775, 1.00 TB",
    "1099511627776, 1.00 TB"
  })
  void rolloverAtBoundary(final long usage, final String expected) {
    assertThat(UsageUtils.humanReadable(usage)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} bytes -> {1}")
  @DisplayName("humanReadable keeps TB as the largest unit")
  @CsvSource({
    "5497558138880, 5.00 TB",
    "1125899906842623, 1024.00 TB",
    "1125899906842624, 1024.00 TB"
  })
  void terabytesAreTheLargestUnit(final long usage, final String expected) {
    assertThat(UsageUtils.humanReadable(usage)).isEqualTo(expected);
  }
}
