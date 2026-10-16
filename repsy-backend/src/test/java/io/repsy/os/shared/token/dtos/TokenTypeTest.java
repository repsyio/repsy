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
package io.repsy.os.shared.token.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.token.utils.TokenFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TokenType")
class TokenTypeTest {

  @Test
  @DisplayName("a personal token starts with rut- and a temporary CLI token with rct-")
  void prefixes() {
    assertThat(TokenType.REPSY_USER_TOKEN.getPrefix()).isEqualTo("rut-");
    assertThat(TokenType.REPSY_CLI_TOKEN.getPrefix()).isEqualTo("rct-");
    assertThat(TokenFactory.personalAccessToken()).startsWith("rut-");
    assertThat(TokenFactory.cliToken()).startsWith("rct-");
  }

  @Test
  @DisplayName("both kinds of a user's token are recognized, a deploy token is not")
  void isUserToken() {
    assertThat(TokenType.isUserToken(TokenFactory.personalAccessToken())).isTrue();
    assertThat(TokenType.isUserToken(TokenFactory.cliToken())).isTrue();
    assertThat(TokenType.isUserToken(TokenFactory.deployToken())).isFalse();
  }

  @ParameterizedTest(name = "\"{0}\" is not a user token")
  @ValueSource(strings = {"", "rct", "rut", "RCT-x", "RUT-x", "xrct-x", " rct-x", "rdt-x"})
  void lookalikesAreNot(final String secret) {
    assertThat(TokenType.isUserToken(secret)).isFalse();
  }
}
