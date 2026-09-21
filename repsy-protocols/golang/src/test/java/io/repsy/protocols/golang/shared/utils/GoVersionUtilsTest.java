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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
