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
package io.repsy.os.shared.auth.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AuthUtils")
class AuthUtilsTest {

  private static String encode(final String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(UTF_8));
  }

  @Test
  @DisplayName("extractCredentialsFromBasicToken reads the username and password")
  void extractsUsernameAndPassword() {
    final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode("user:secret"));

    assertThat(credentials).isNotNull();
    assertThat(credentials.getUsername()).isEqualTo("user");
    assertThat(credentials.getPassword()).isEqualTo("secret");
  }

  @Test
  @DisplayName("extractCredentialsFromBasicToken maps an empty username to null")
  void emptyUsernameIsNull() {
    final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode(":secret"));

    assertThat(credentials).isNotNull();
    assertThat(credentials.getUsername()).isNull();
    assertThat(credentials.getPassword()).isEqualTo("secret");
  }

  @Test
  @DisplayName("extractCredentialsFromBasicToken keeps an empty password")
  void emptyPasswordIsKept() {
    final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode("user:"));

    assertThat(credentials).isNotNull();
    assertThat(credentials.getUsername()).isEqualTo("user");
    assertThat(credentials.getPassword()).isEmpty();
  }

  @Test
  @DisplayName("extractCredentialsFromBasicToken returns null for a value without a colon")
  void noColonIsNull() {
    assertThat(AuthUtils.extractCredentialsFromBasicToken(encode("user"))).isNull();
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "!!!", "@@@@", "%%%"})
  @DisplayName("extractCredentialsFromBasicToken returns null for a token that is not base64")
  void undecodableTokenIsNull(final String token) {
    assertThat(AuthUtils.extractCredentialsFromBasicToken(token)).isNull();
  }

  @Test
  @DisplayName("extractCredentialsFromAuthHeader returns null for an undecodable Basic header")
  void authHeaderWithUndecodableTokenIsNull() {
    assertThat(AuthUtils.extractCredentialsFromAuthHeader("Basic !!!")).isNull();
  }
}
