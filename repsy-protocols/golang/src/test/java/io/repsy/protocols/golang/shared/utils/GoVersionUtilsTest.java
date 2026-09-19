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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
