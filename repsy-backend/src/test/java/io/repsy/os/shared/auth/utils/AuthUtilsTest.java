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

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AuthUtils")
class AuthUtilsTest {

  @Nested
  @DisplayName("boundBySession")
  class BoundBySession {

    @Test
    @DisplayName("keeps the regular lifetime early in the session")
    void keepsTheTimeoutEarlyInTheSession() {
      final var result =
          AuthUtils.boundBySession(AuthUtils.TIMEOUT_REFRESH_TOKEN, Instant.now().minusSeconds(60));

      assertThat(result).isEqualTo(AuthUtils.TIMEOUT_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("caps the lifetime at the time left until the session ends")
    void capsTheTimeoutAtTheSessionEnd() {
      final var sessionStart = Instant.now().minus(AuthUtils.TIMEOUT_SESSION).plusSeconds(600);

      final var result = AuthUtils.boundBySession(AuthUtils.TIMEOUT_REFRESH_TOKEN, sessionStart);

      assertThat(result).isBetween(Duration.ofSeconds(595), Duration.ofSeconds(600));
    }

    @Test
    @DisplayName("is not positive once the session has ended")
    void isNotPositiveAfterTheSessionEnd() {
      final var sessionStart = Instant.now().minus(AuthUtils.TIMEOUT_SESSION).minusSeconds(1);

      final var result = AuthUtils.boundBySession(AuthUtils.TIMEOUT_ACCESS_TOKEN, sessionStart);

      assertThat(result).isNegative();
    }
  }

  @Nested
  @DisplayName("extractCredentialsFromBasicToken")
  class ExtractCredentialsFromBasicToken {

    private static String encode(final String raw) {
      return Base64.getEncoder().encodeToString(raw.getBytes(UTF_8));
    }

    @Test
    @DisplayName("reads the username and password")
    void extractsUsernameAndPassword() {
      final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode("user:secret"));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isEqualTo("user");
      assertThat(credentials.getPassword()).isEqualTo("secret");
    }

    @Test
    @DisplayName("maps an empty username to null")
    void emptyUsernameIsNull() {
      final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode(":secret"));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isNull();
      assertThat(credentials.getPassword()).isEqualTo("secret");
    }

    @Test
    @DisplayName("keeps an empty password")
    void emptyPasswordIsKept() {
      final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode("user:"));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isEqualTo("user");
      assertThat(credentials.getPassword()).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"pa:ss", "pa:ss:word", ":pass", "pass:", "::", "a::b"})
    @DisplayName("keeps every colon after the first one in the password")
    void colonsInThePasswordAreKept(final String password) {
      final var credentials =
          AuthUtils.extractCredentialsFromBasicToken(encode("user:" + password));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isEqualTo("user");
      assertThat(credentials.getPassword()).isEqualTo(password);
    }

    @Test
    @DisplayName("maps an empty username to null when the password contains a colon")
    void emptyUsernameWithColonPassword() {
      final var credentials = AuthUtils.extractCredentialsFromBasicToken(encode(":pa:ss"));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isNull();
      assertThat(credentials.getPassword()).isEqualTo("pa:ss");
    }

    @Test
    @DisplayName("returns null for a value without a colon")
    void noColonIsNull() {
      assertThat(AuthUtils.extractCredentialsFromBasicToken(encode("user"))).isNull();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "!!!", "@@@@", "%%%"})
    @DisplayName("returns null for a token that is not base64")
    void undecodableTokenIsNull(final String token) {
      assertThat(AuthUtils.extractCredentialsFromBasicToken(token)).isNull();
    }

    @Test
    @DisplayName("extractCredentialsFromAuthHeader returns null for an undecodable Basic header")
    void authHeaderWithUndecodableTokenIsNull() {
      assertThat(AuthUtils.extractCredentialsFromAuthHeader("Basic !!!")).isNull();
    }
  }
}
