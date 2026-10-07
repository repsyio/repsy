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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PersonalAccessTokenInfo")
class PersonalAccessTokenInfoTest {

  private static PersonalAccessTokenInfo info(
      final EnumSet<TokenScope> scopes, final Instant expirationDate) {
    return new PersonalAccessTokenInfo(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "alice",
        "ci",
        scopes,
        expirationDate,
        null,
        Instant.now());
  }

  @Test
  @DisplayName("its scopes cannot be changed by whoever reads them")
  void scopesAreReadOnly() {
    final var info = info(EnumSet.of(TokenScope.REPO_READ), Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> info.scopes().add(TokenScope.REPO_MANAGE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> info.scopes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(info.scopes()).containsExactly(TokenScope.REPO_READ);
  }

  @Test
  @DisplayName("it keeps its own copy: changing the set it was built from changes nothing")
  void scopesAreCopied() {
    final var source = EnumSet.of(TokenScope.REPO_READ);
    final var info = info(source, Instant.now().plusSeconds(60));

    source.add(TokenScope.REPO_MANAGE);

    assertThat(info.scopes()).containsExactly(TokenScope.REPO_READ);
  }

  @Test
  @DisplayName("its scopes stay in canonical order")
  void scopesStayCanonical() {
    final var info =
        info(
            EnumSet.of(TokenScope.SCAN_READ, TokenScope.PROFILE_READ, TokenScope.REPO_WRITE),
            Instant.now().plusSeconds(60));

    assertThat(info.scopes())
        .containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_WRITE, TokenScope.SCAN_READ);
  }

  @Test
  @DisplayName("a token with no scopes at all may do nothing: the missing scopes are never widened")
  void missingScopesGrantNothing() {
    final var info = info(null, Instant.now().plusSeconds(60));

    assertThat(info.scopes()).isEmpty();
    assertThatThrownBy(() -> info.scopes().add(TokenScope.REPO_READ))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("is expired from its expiration date on, and not before")
  void expiresOnItsDate() {
    final var scopes = EnumSet.of(TokenScope.REPO_READ);

    assertThat(info(scopes, Instant.now().plus(Duration.ofMinutes(5))).isExpired()).isFalse();
    assertThat(info(scopes, Instant.now().minusSeconds(1)).isExpired()).isTrue();
    assertThat(info(scopes, Instant.now().minus(Duration.ofDays(400))).isExpired()).isTrue();
  }
}
