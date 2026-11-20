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
package io.repsy.os.shared.token.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenKind;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.mappers.PersonalAccessTokenMapperImpl;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/** The connector tokens of {@link PersonalAccessTokenService} (RPS-2660), on a fixed clock. */
@DisplayName("PersonalAccessTokenService, connector tokens")
class PersonalAccessTokenConnectorTest {

  private static final UUID USER_ID = UUID.fromString("0198a000-0000-7000-8000-000000000002");
  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

  private final PersonalAccessTokenRepository tokenRepository =
      mock(PersonalAccessTokenRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final PersonalAccessTokenService service =
      new PersonalAccessTokenService(
          this.tokenRepository,
          this.userRepository,
          new PersonalAccessTokenMapperImpl(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    final var user = new User();

    user.setId(USER_ID);
    user.setUsername("alice");
    when(this.userRepository.lockUserIdForUpdate(USER_ID)).thenReturn(Optional.of(USER_ID));
    when(this.userRepository.getReferenceById(USER_ID)).thenReturn(user);
    when(this.tokenRepository.saveAndFlush(any(PersonalAccessToken.class)))
        .thenAnswer(
            invocation -> {
              final PersonalAccessToken token = invocation.getArgument(0);

              token.setId(UUID.randomUUID());
              token.setCreatedAt(NOW);

              return token;
            });
  }

  private static PersonalAccessTokenInfo connector(
      final Instant createdAt, final Instant expirationDate) {
    return new PersonalAccessTokenInfo(
        UUID.randomUUID(),
        USER_ID,
        "alice",
        PersonalAccessTokenKind.CONNECTOR,
        "Claude",
        Set.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ),
        expirationDate,
        null,
        createdAt);
  }

  @Test
  @DisplayName("creates a rat- token that expires 90 days from now, named after the application")
  void createsAConnectorToken() {
    final var created =
        this.service.createConnectorToken(USER_ID, "Claude", Set.of(TokenScope.REPO_READ));
    final var captor = ArgumentCaptor.forClass(PersonalAccessToken.class);

    verify(this.tokenRepository).saveAndFlush(captor.capture());
    final var row = captor.getValue();

    assertThat(created.getToken()).startsWith("rat-");
    assertThat(row.getKind()).isEqualTo(PersonalAccessTokenKind.CONNECTOR);
    assertThat(row.getName()).isEqualTo("Claude");
    assertThat(row.getTokenHash()).isEqualTo(TokenHash.hash(created.getToken()));
    assertThat(row.getScopes()).containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ);
    assertThat(row.getExpirationDate()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    verify(this.tokenRepository)
        .deleteExpiredByUserIdAndKind(USER_ID, PersonalAccessTokenKind.CONNECTOR, NOW);
  }

  @Test
  @DisplayName("refuses a connector token beyond 50 live ones")
  void refusesBeyondTheCap() {
    final var scopes = Set.of(TokenScope.REPO_READ);

    when(this.tokenRepository.countByUserIdAndKindAndExpirationDateAfter(
            USER_ID, PersonalAccessTokenKind.CONNECTOR, NOW))
        .thenReturn((long) PersonalAccessTokenService.MAX_CONNECTOR_TOKENS_PER_USER);

    assertThatThrownBy(() -> this.service.createConnectorToken(USER_ID, "Claude", scopes))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("connectorTokenLimitReached");
    verify(this.tokenRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("a use moves the expiry of a connector token to 90 days from now")
  void aUseRenewsTheToken() {
    final var token = connector(NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(60)));

    this.service.recordUse(token);

    verify(this.tokenRepository)
        .updateLastUsedTimeAndExpirationDate(token.id(), NOW, NOW.plus(Duration.ofDays(90)));
    verify(this.tokenRepository, never()).updateLastUsedTime(any(), any());
  }

  @Test
  @DisplayName("a use within a day of the last renewal writes no new expiry")
  void aUseWithinADayDoesNotRenew() {
    final var token = connector(NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofHours(2159)));

    this.service.recordUse(token);

    verify(this.tokenRepository).updateLastUsedTime(token.id(), NOW);
    verify(this.tokenRepository, never()).updateLastUsedTimeAndExpirationDate(any(), any(), any());
  }

  @Test
  @DisplayName("the expiry never goes past 365 days from the creation")
  void theExpiryIsCapped() {
    final var createdAt = NOW.minus(Duration.ofDays(300));
    final var token = connector(createdAt, NOW.plus(Duration.ofDays(10)));

    this.service.recordUse(token);

    verify(this.tokenRepository)
        .updateLastUsedTimeAndExpirationDate(token.id(), NOW, createdAt.plus(Duration.ofDays(365)));
  }

  @Test
  @DisplayName("a token at its cap is only marked as used")
  void aCappedTokenIsOnlyMarked() {
    final var createdAt = NOW.minus(Duration.ofDays(364));
    final var token = connector(createdAt, createdAt.plus(Duration.ofDays(365)));

    this.service.recordUse(token);

    verify(this.tokenRepository).updateLastUsedTime(token.id(), NOW);
  }

  @Test
  @DisplayName("lists only the connector tokens that have not expired")
  void listsTheLiveConnectorTokens() {
    this.service.getConnectorTokens(USER_ID, Pageable.unpaged());

    verify(this.tokenRepository)
        .findAllByUserIdAndKindAndExpirationDateAfter(
            USER_ID, PersonalAccessTokenKind.CONNECTOR, NOW, Pageable.unpaged());
  }

  @Test
  @DisplayName("revoking a connector token another user holds, or one of another kind, is a 404")
  void revokesOnlyOwnConnectorTokens() {
    final var tokenId = UUID.randomUUID();

    when(this.tokenRepository.findByUserIdAndIdAndKind(
            USER_ID, tokenId, PersonalAccessTokenKind.CONNECTOR))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.revokeConnectorToken(USER_ID, tokenId))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("accessTokenNotFound");
  }

  @Test
  @DisplayName("revokes a connector token of the user")
  void revokesAConnectorToken() {
    final var token = new PersonalAccessToken();
    final var tokenId = UUID.randomUUID();

    when(this.tokenRepository.findByUserIdAndIdAndKind(
            USER_ID, tokenId, PersonalAccessTokenKind.CONNECTOR))
        .thenReturn(Optional.of(token));

    this.service.revokeConnectorToken(USER_ID, tokenId);

    verify(this.tokenRepository).delete(token);
  }

  @Test
  @DisplayName("a connector token revokes itself, any other token is not a connector token")
  void revokesItsOwnConnectorToken() {
    final var token = connector(NOW, NOW.plus(Duration.ofDays(90)));
    final var personal =
        new PersonalAccessTokenInfo(
            UUID.randomUUID(),
            USER_ID,
            "alice",
            PersonalAccessTokenKind.PERSONAL,
            "ci",
            Set.of(TokenScope.REPO_READ),
            NOW.plus(Duration.ofDays(1)),
            null,
            NOW);

    this.service.revokeOwnConnectorToken(token);

    verify(this.tokenRepository).deleteById(token.id());
    assertThatThrownBy(() -> this.service.revokeOwnConnectorToken(personal))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("notAConnectorToken");
  }
}
