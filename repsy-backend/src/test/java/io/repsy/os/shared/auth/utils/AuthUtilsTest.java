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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthUtils.boundBySession")
class AuthUtilsTest {

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
