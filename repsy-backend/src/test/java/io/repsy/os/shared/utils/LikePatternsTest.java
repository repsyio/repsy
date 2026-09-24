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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("LikePatterns")
class LikePatternsTest {

  @Test
  @DisplayName("lower-cases the query and wraps it in the given prefix and suffix")
  void lowerCasesAndWraps() {
    assertThat(LikePatterns.of("%", "MyRepo", "%")).isEqualTo("%myrepo%");
    assertThat(LikePatterns.of("", "MyRepo", "%")).isEqualTo("myrepo%");
    assertThat(LikePatterns.of("", "", "")).isEmpty();
  }

  @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
  @DisplayName("takes %, _ and the backslash literally by escaping them with a backslash")
  @CsvSource(
      delimiter = '|',
      value = {"a_b|a\\_b", "100%|100\\%", "a\\b|a\\\\b", "%_\\|\\%\\_\\\\"})
  void escapesWildcards(final String query, final String escaped) {
    assertThat(LikePatterns.of("", query, "")).isEqualTo(escaped);
  }
}
