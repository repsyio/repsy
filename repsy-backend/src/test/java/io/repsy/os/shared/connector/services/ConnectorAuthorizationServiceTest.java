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
package io.repsy.os.shared.connector.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.ConnectorAuthorizationForm;
import io.repsy.os.generated.model.ConnectorTokenExchangeForm;
import io.repsy.os.shared.connector.configs.ConnectorProperties;
import io.repsy.os.shared.connector.entities.ConnectorAuthorizationCode;
import io.repsy.os.shared.connector.repositories.ConnectorAuthorizationCodeRepository;
import io.repsy.os.shared.connector.utils.PkceUtils;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("ConnectorAuthorizationService")
class ConnectorAuthorizationServiceTest {

  private static final UUID USER_ID = UUID.fromString("0198a000-0000-7000-8000-000000000003");
  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final String REDIRECT = "https://mcp.repsy.io/oauth/upstream/callback";
  private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
  private static final String CHALLENGE = PkceUtils.challengeOf(VERIFIER);
  private static final String CODE = "the-one-time-code";
  private static final String INVALID_CODE = "invalidConnectorCode";
  private static final String VALIDATION_ERROR = "validationError";

  private final ConnectorAuthorizationCodeRepository codeRepository =
      mock(ConnectorAuthorizationCodeRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final PersonalAccessTokenService tokenService = mock(PersonalAccessTokenService.class);

  private User user;

  @BeforeEach
  void setUp() {
    this.user = new User();
    this.user.setId(USER_ID);
    when(this.userRepository.getReferenceById(USER_ID)).thenReturn(this.user);
    when(this.codeRepository.saveAndFlush(any(ConnectorAuthorizationCode.class)))
        .thenAnswer(
            invocation -> {
              final ConnectorAuthorizationCode row = invocation.getArgument(0);

              row.setId(UUID.randomUUID());

              return row;
            });
  }

  private ConnectorAuthorizationService service(final boolean enabled) {
    return new ConnectorAuthorizationService(
        new ConnectorProperties(enabled, List.of(REDIRECT)),
        this.codeRepository,
        this.userRepository,
        this.tokenService,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ConnectorAuthorizationForm form() {
    return ConnectorAuthorizationForm.builder()
        .redirectUri(REDIRECT)
        .clientName("Claude")
        .scopes(Set.of(TokenScope.REPO_READ))
        .codeChallenge(CHALLENGE)
        .codeChallengeMethod("S256")
        .build();
  }

  private static ConnectorTokenExchangeForm exchange(
      final String code, final String verifier, final String redirectUri) {
    return ConnectorTokenExchangeForm.builder()
        .code(code)
        .codeVerifier(verifier)
        .redirectUri(redirectUri)
        .build();
  }

  private ConnectorAuthorizationCode storedCode(final Instant expiresAt) {
    final var row = new ConnectorAuthorizationCode();

    row.setUser(this.user);
    row.setCodeHash(TokenHash.hash(CODE));
    row.setClientName("Claude");
    row.setScopes(Set.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ));
    row.setRedirectUri(REDIRECT);
    row.setCodeChallenge(CHALLENGE);
    row.setExpiresAt(expiresAt);
    when(this.codeRepository.findByCodeHash(TokenHash.hash(CODE))).thenReturn(Optional.of(row));

    return row;
  }

  @Test
  @DisplayName("stores the hash of a new code for 60 seconds and returns the code once")
  void issuesACode() {
    final var info = this.service(true).authorize(USER_ID, form());
    final var captor = ArgumentCaptor.forClass(ConnectorAuthorizationCode.class);

    verify(this.codeRepository).deleteExpired(NOW);
    verify(this.codeRepository).saveAndFlush(captor.capture());
    final var row = captor.getValue();

    assertThat(info.getCode()).matches("[A-Za-z0-9_-]{43}");
    assertThat(info.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofSeconds(60)));
    assertThat(info.getId()).isEqualTo(row.getId());
    assertThat(row.getCodeHash()).isEqualTo(TokenHash.hash(info.getCode()));
    assertThat(row.getScopes())
        .containsExactlyInAnyOrder(TokenScope.PROFILE_READ, TokenScope.REPO_READ);
    assertThat(row.getRedirectUri()).isEqualTo(REDIRECT);
    assertThat(row.getCodeChallenge()).isEqualTo(CHALLENGE);
    assertThat(row.getUser()).isSameAs(this.user);
  }

  @Test
  @DisplayName("answers 404 for everything while connected applications are off")
  void isNotFoundWhileOff() {
    final var service = this.service(false);
    final var form = form();
    final var exchange = exchange(CODE, VERIFIER, REDIRECT);

    assertThatThrownBy(() -> service.authorize(USER_ID, form))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
    assertThatThrownBy(() -> service.exchange(exchange))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
    verifyNoInteractions(this.codeRepository);
  }

  @Test
  @DisplayName("refuses a redirect that is not registered")
  void refusesAnUnregisteredRedirect() {
    final var service = this.service(true);
    final var form = form().redirectUri(REDIRECT + "/other");

    assertThatThrownBy(() -> service.authorize(USER_ID, form))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("connectorRedirectUriNotRegistered");
  }

  @Test
  @DisplayName("refuses scan:read, an explicit profile:read and no scope at all")
  void refusesAScopeThatIsNotGrantable() {
    final var service = this.service(true);
    final var scanRead = form().scopes(Set.of(TokenScope.SCAN_READ));
    final var profileRead = form().scopes(Set.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ));
    final var none = form().scopes(Set.of());

    assertThatThrownBy(() -> service.authorize(USER_ID, scanRead)).hasMessage(VALIDATION_ERROR);
    assertThatThrownBy(() -> service.authorize(USER_ID, profileRead)).hasMessage(VALIDATION_ERROR);
    assertThatThrownBy(() -> service.authorize(USER_ID, none)).hasMessage(VALIDATION_ERROR);
  }

  @Test
  @DisplayName("refuses a plain or malformed PKCE challenge and a blank name")
  void refusesABadChallengeOrName() {
    final var service = this.service(true);
    final var plain = form().codeChallengeMethod("plain");
    final var malformed = form().codeChallenge("short");
    final var blank = form().clientName(" \t\n");

    assertThatThrownBy(() -> service.authorize(USER_ID, plain)).hasMessage(VALIDATION_ERROR);
    assertThatThrownBy(() -> service.authorize(USER_ID, malformed)).hasMessage(VALIDATION_ERROR);
    assertThatThrownBy(() -> service.authorize(USER_ID, blank)).hasMessage(VALIDATION_ERROR);
    verify(this.codeRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("keeps a cleaned application name of at most 150 characters")
  void cleansTheName() {
    assertThat(ConnectorAuthorizationService.clientName("  Claude\u0000\u001b "))
        .isEqualTo("Claude");
    assertThat(ConnectorAuthorizationService.clientName("x".repeat(200))).hasSize(150);
  }

  @Test
  @DisplayName("exchanges a code for a connector token and uses the code up")
  void exchangesACode() {
    final var row = this.storedCode(NOW.plusSeconds(30));
    final var created = AccessTokenCreated.builder().token("rat-x").build();

    when(this.tokenService.createConnectorToken(USER_ID, "Claude", row.getScopes()))
        .thenReturn(created);

    assertThat(this.service(true).exchange(exchange(CODE, VERIFIER, REDIRECT))).isSameAs(created);
    verify(this.codeRepository).delete(row);
  }

  @Test
  @DisplayName("an unknown code is invalid")
  void refusesAnUnknownCode() {
    final var service = this.service(true);
    final var form = exchange("another-code", VERIFIER, REDIRECT);

    when(this.codeRepository.findByCodeHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.exchange(form)).hasMessage(INVALID_CODE);
  }

  @Test
  @DisplayName(
      "an expired code, a wrong verifier and another redirect are one answer, code used up")
  void refusesAMismatchAndUsesTheCodeUp() {
    final var service = this.service(true);
    final var expired = exchange(CODE, VERIFIER, REDIRECT);
    final var wrongVerifier = exchange(CODE, VERIFIER.replace('d', 'e'), REDIRECT);
    final var otherRedirect = exchange(CODE, VERIFIER, REDIRECT + "/other");

    this.storedCode(NOW);
    assertThatThrownBy(() -> service.exchange(expired)).hasMessage(INVALID_CODE);

    this.storedCode(NOW.plusSeconds(30));
    assertThatThrownBy(() -> service.exchange(wrongVerifier)).hasMessage(INVALID_CODE);

    this.storedCode(NOW.plusSeconds(30));
    assertThatThrownBy(() -> service.exchange(otherRedirect)).hasMessage(INVALID_CODE);

    verify(this.codeRepository, times(3)).delete(any(ConnectorAuthorizationCode.class));
    verifyNoInteractions(this.tokenService);
  }
}
