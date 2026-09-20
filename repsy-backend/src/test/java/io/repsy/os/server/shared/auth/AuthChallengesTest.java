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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthChallenges")
class AuthChallengesTest {

  private static final String CHALLENGE = "Basic realm=\"Repsy Managed Repository\"";

  @Test
  @DisplayName("adds the challenge and keeps the message id of the failure")
  void addsChallenge() {
    final var original = new UnAuthorizedException("accessNotAllowed");

    final var challenged = AuthChallenges.challenged(original, CHALLENGE);

    assertThat(challenged).hasMessage("accessNotAllowed").hasCause(original);
    assertThat(challenged.getHeaders()).containsOnly(Map.entry(WWW_AUTHENTICATE, CHALLENGE));
  }

  @Test
  @DisplayName("keeps the other headers of the failure")
  void keepsOtherHeaders() {
    final var original = new UnAuthorizedException("unAuthorized", Map.of("X-Reason", "expired"));

    final var challenged = AuthChallenges.challenged(original, CHALLENGE);

    assertThat(challenged.getHeaders())
        .containsOnly(Map.entry("X-Reason", "expired"), Map.entry(WWW_AUTHENTICATE, CHALLENGE));
  }

  @Test
  @DisplayName("leaves a failure that already carries a challenge as it is")
  void keepsExistingChallenge() {
    final var original =
        new UnAuthorizedException("unAuthorized", Map.of("www-authenticate", "Bearer"));

    final var challenged = AuthChallenges.challenged(original, CHALLENGE);

    assertThat(challenged).isSameAs(original);
  }
}
