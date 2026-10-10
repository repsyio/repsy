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
package io.repsy.protocols.shared.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FieldLimits")
class FieldLimitsTest {

  @Test
  @DisplayName("dropIfTooLong keeps null, a value at the limit, and drops one past it")
  void dropIfTooLong() {
    assertThat(FieldLimits.dropIfTooLong(null, 3)).isNull();
    assertThat(FieldLimits.dropIfTooLong("abc", 3)).isEqualTo("abc");
    assertThat(FieldLimits.dropIfTooLong("abcd", 3)).isNull();
  }

  @Test
  @DisplayName("the logging dropIfTooLong decides exactly as the silent one")
  void dropIfTooLongNamed() {
    assertThat(FieldLimits.dropIfTooLong(null, 3, "homepage")).isNull();
    assertThat(FieldLimits.dropIfTooLong("abc", 3, "homepage")).isEqualTo("abc");
    assertThat(FieldLimits.dropIfTooLong("abcd", 3, "homepage")).isNull();
  }

  @Test
  @DisplayName("dropEntriesIfTooLong keeps null, null entries and fitting ones")
  void dropEntriesIfTooLong() {
    assertThat(FieldLimits.dropEntriesIfTooLong(null, 3, "author")).isNull();
    assertThat(
            FieldLimits.dropEntriesIfTooLong(Arrays.asList("abc", null, "abcd", "a"), 3, "author"))
        .isEqualTo(Arrays.asList("abc", null, "a"));
    assertThat(FieldLimits.dropEntriesIfTooLong(List.of(), 3, "author")).isEmpty();
  }
}
