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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.generated.model.AccessTokenForm;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

@DisplayName("PersonalAccessTokenService")
class PersonalAccessTokenServiceTest {

  private static final UUID USER_ID = UUID.fromString("0198a000-0000-7000-8000-000000000001");
  private static final String USERNAME = "alice";

  /**
   * "Now" of every test: the clock is fixed, so the expiration rules, whose last day changes at
   * midnight UTC, do not depend on the time of day the tests run at. 365 days on from here, the
   * last day is 2027-10-06.
   */
  private static final Instant NOW = Instant.parse("2026-10-06T10:15:30Z");

  private final PersonalAccessTokenRepository tokenRepository =
      mock(PersonalAccessTokenRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final PersonalAccessTokenService service = this.serviceAt(NOW);

  private User user;

  private PersonalAccessTokenService serviceAt(final Instant now) {
    return new PersonalAccessTokenService(
        this.tokenRepository,
        this.userRepository,
        new PersonalAccessTokenMapperImpl(),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @BeforeEach
  void setUp() {
    this.user = new User();
    this.user.setId(USER_ID);
    this.user.setUsername(USERNAME);
  }

  /** What the INSERT does: the id and the creation time are assigned when the row is written. */
  private void whenFlushed() {
    when(this.tokenRepository.saveAndFlush(any(PersonalAccessToken.class)))
        .thenAnswer(
            invocation -> {
              final PersonalAccessToken token = invocation.getArgument(0);

              token.setId(UUID.randomUUID());
              token.setCreatedAt(Instant.now());

              return token;
            });
  }

  private void whenUserExists() {
    when(this.userRepository.lockUserIdForUpdate(USER_ID)).thenReturn(Optional.of(USER_ID));
    when(this.userRepository.getReferenceById(USER_ID)).thenReturn(this.user);
  }

  private static AccessTokenForm form(final Instant expirationDate, final TokenScope... scopes) {
    return AccessTokenForm.builder()
        .name("ci")
        .scopes(Set.of(scopes))
        .expirationDate(expirationDate)
        .build();
  }

  private PersonalAccessToken savedToken() {
    final var captor = ArgumentCaptor.forClass(PersonalAccessToken.class);

    verify(this.tokenRepository).saveAndFlush(captor.capture());

    return captor.getValue();
  }

  // -------------------------------------------------------------------------------------------
  // createToken
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("creates a token: the secret is returned, only its hash is stored")
  void storesTheHashNotTheSecret() {
    this.whenUserExists();
    this.whenFlushed();

    final var created = this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ));
    final var saved = this.savedToken();

    assertThat(created.getToken()).matches("rut-[A-Za-z0-9_-]{43}");
    assertThat(saved.getTokenHash()).isEqualTo(TokenHash.hash(created.getToken()));
    assertThat(saved.getTokenHash()).doesNotContain(created.getToken());
    assertThat(saved.toString())
        .doesNotContain(created.getToken())
        .doesNotContain(saved.getTokenHash());
    assertThat(created.getId()).isEqualTo(saved.getId());
    assertThat(created.getName()).isEqualTo("ci");
    assertThat(created.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("adds the implicit profile:read and keeps the scopes in canonical order")
  void addsTheImplicitScope() {
    this.whenUserExists();
    this.whenFlushed();

    final var created =
        this.service.createToken(
            USER_ID, form(null, TokenScope.SCAN_READ, TokenScope.REPO_WRITE, TokenScope.REPO_READ));

    assertThat(this.savedToken().getScopes())
        .containsExactly(
            TokenScope.PROFILE_READ,
            TokenScope.REPO_READ,
            TokenScope.REPO_WRITE,
            TokenScope.SCAN_READ);
    assertThat(created.getScopes())
        .containsExactly(
            TokenScope.PROFILE_READ,
            TokenScope.REPO_READ,
            TokenScope.REPO_WRITE,
            TokenScope.SCAN_READ);
  }

  @Test
  @DisplayName("never adds repo:manage: a token that did not ask for it cannot manage")
  void doesNotImplyManage() {
    this.whenUserExists();
    this.whenFlushed();

    this.service.createToken(USER_ID, form(null, TokenScope.REPO_WRITE));

    assertThat(this.savedToken().getScopes()).doesNotContain(TokenScope.REPO_MANAGE);
  }

  @Test
  @DisplayName("a token created without a date expires in 365 days from now")
  void defaultsToOneYear() {
    this.whenUserExists();
    this.whenFlushed();

    final var created = this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(NOW.plus(Duration.ofDays(365)));
    assertThat(this.savedToken().getExpirationDate()).isEqualTo(created.getExpirationDate());
  }

  @Test
  @DisplayName("keeps a date that is in the future and within 365 days")
  void keepsAValidDate() {
    this.whenUserExists();
    this.whenFlushed();
    final var date = NOW.plus(Duration.ofDays(30));

    final var created = this.service.createToken(USER_ID, form(date, TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(date);
  }

  @Test
  @DisplayName("accepts the instant after now: a date only has to be in the future")
  void acceptsTheNextInstant() {
    this.whenUserExists();
    this.whenFlushed();

    final var created =
        this.service.createToken(
            USER_ID, form(NOW.plus(Duration.ofMillis(1)), TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(NOW.plus(Duration.ofMillis(1)));
  }

  @Test
  @DisplayName("refuses a date in the past, and now itself, with accessTokenExpirationInPast")
  void refusesAPastDate() {
    for (final var date :
        new Instant[] {
          NOW.minus(Duration.ofDays(1)), NOW.minusSeconds(1), NOW, NOW.minus(Duration.ofDays(3650))
        }) {
      assertThatThrownBy(() -> this.service.createToken(USER_ID, form(date, TokenScope.REPO_READ)))
          .as("%s", date)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("accessTokenExpirationInPast");
    }

    verifyNoInteractions(this.tokenRepository, this.userRepository);
  }

  @Test
  @DisplayName("accepts 365 days from now, at the time of day it is sent: the day is compared")
  void acceptsTheMaximumDay() {
    this.whenUserExists();
    this.whenFlushed();
    final var date = NOW.plus(Duration.ofDays(365));

    final var created = this.service.createToken(USER_ID, form(date, TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(date);
  }

  @Test
  @DisplayName("the last microsecond of the last day is accepted, the first one after it is not")
  void theBoundaryIsMidnightUtc() {
    this.whenUserExists();
    this.whenFlushed();
    final var lastMicrosecond = Instant.parse("2027-10-06T23:59:59.999999Z");

    assertThat(
            this.service
                .createToken(USER_ID, form(lastMicrosecond, TokenScope.REPO_READ))
                .getExpirationDate())
        .isEqualTo(lastMicrosecond);

    assertThatThrownBy(
            () ->
                this.service.createToken(
                    USER_ID, form(Instant.parse("2027-10-07T00:00:00Z"), TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenExpirationTooLate");
  }

  @Test
  @DisplayName(
      "truncates the date to microseconds: the nanoseconds of the last instant are cut off")
  void truncatesToMicroseconds() {
    this.whenUserExists();
    this.whenFlushed();
    final var lastNanosecond = Instant.parse("2027-10-06T23:59:59.999999999Z");

    final var created =
        this.service.createToken(USER_ID, form(lastNanosecond, TokenScope.REPO_READ));

    // The database rounds a finer value: kept, the nanoseconds would be stored as the next day.
    assertThat(created.getExpirationDate()).isEqualTo(Instant.parse("2027-10-06T23:59:59.999999Z"));
    assertThat(this.savedToken().getExpirationDate()).isEqualTo(created.getExpirationDate());
  }

  @Test
  @DisplayName("a date a nanosecond after now is now once truncated, so it is in the past")
  void aNanosecondAfterNowIsNotTheFuture() {
    assertThatThrownBy(
            () ->
                this.service.createToken(
                    USER_ID, form(NOW.plus(Duration.ofNanos(1)), TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenExpirationInPast");
  }

  @Test
  @DisplayName("a date a microsecond after now is the future")
  void aMicrosecondAfterNowIsTheFuture() {
    this.whenUserExists();
    this.whenFlushed();

    final var created =
        this.service.createToken(
            USER_ID, form(NOW.plus(Duration.ofNanos(1_000)), TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(NOW.plus(Duration.ofNanos(1_000)));
  }

  @Test
  @DisplayName("the default date has microsecond precision, whatever the precision of the clock")
  void theDefaultDateIsInMicroseconds() {
    this.whenUserExists();
    this.whenFlushed();
    final var nanos = Instant.parse("2026-10-06T10:15:30.123456789Z");

    final var created =
        this.serviceAt(nanos).createToken(USER_ID, form(null, TokenScope.REPO_READ));

    assertThat(created.getExpirationDate()).isEqualTo(Instant.parse("2027-10-06T10:15:30.123456Z"));
  }

  @Test
  @DisplayName("refuses a date beyond the last day with accessTokenExpirationTooLate")
  void refusesADateThatIsTooFarAhead() {
    for (final var date :
        new Instant[] {
          NOW.plus(Duration.ofDays(366)),
          NOW.plus(Duration.ofDays(3650)),
          Instant.parse("9999-12-31T23:59:59Z")
        }) {
      assertThatThrownBy(() -> this.service.createToken(USER_ID, form(date, TokenScope.REPO_READ)))
          .as("%s", date)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("accessTokenExpirationTooLate");
    }

    verifyNoInteractions(this.tokenRepository, this.userRepository);
  }

  @Test
  @DisplayName("the last day is the UTC day: an offset that crosses midnight is decided by UTC")
  void theLastDayIsTheUtcDayWhateverTheOffset() {
    this.whenUserExists();
    this.whenFlushed();
    final var lastDay = LocalDate.parse("2027-10-06");
    // 23:30 on the last day at UTC-5 is 04:30 UTC on the day after it: too late.
    final var lateInTheWest =
        OffsetDateTime.of(lastDay, LocalTime.of(23, 30), ZoneOffset.ofHours(-5)).toInstant();
    // 10:00 on the last day at UTC+14 is 20:00 UTC the day before it: fine.
    final var earlyInTheEast =
        OffsetDateTime.of(lastDay, LocalTime.of(10, 0), ZoneOffset.ofHours(14)).toInstant();

    assertThatThrownBy(
            () -> this.service.createToken(USER_ID, form(lateInTheWest, TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenExpirationTooLate");
    assertThat(
            this.service
                .createToken(USER_ID, form(earlyInTheEast, TokenScope.REPO_READ))
                .getExpirationDate())
        .isEqualTo(earlyInTheEast);
  }

  @Test
  @DisplayName("the last day moves on at midnight UTC, by the clock of the server")
  void theLastDayMovesAtMidnight() {
    this.whenUserExists();
    this.whenFlushed();
    final var lastMomentOfTheDay = this.serviceAt(Instant.parse("2026-10-06T23:59:59.999Z"));
    final var firstMomentOfTheNext = this.serviceAt(Instant.parse("2026-10-07T00:00:00Z"));

    // Until midnight the last day is 2027-10-06, from midnight it is 2027-10-07.
    assertThatThrownBy(
            () ->
                lastMomentOfTheDay.createToken(
                    USER_ID, form(Instant.parse("2027-10-07T00:30:00Z"), TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenExpirationTooLate");
    assertThat(
            firstMomentOfTheNext
                .createToken(
                    USER_ID, form(Instant.parse("2027-10-07T00:30:00Z"), TokenScope.REPO_READ))
                .getExpirationDate())
        .isEqualTo(Instant.parse("2027-10-07T00:30:00Z"));
  }

  @Test
  @DisplayName("the limit is 365 days, not a calendar year: across a leap day it ends a day sooner")
  void theYearIs365Days() {
    this.whenUserExists();
    this.whenFlushed();
    // 2028-02-29 lies between, so 365 days from 2027-10-06 is 2028-10-05.
    final var service = this.serviceAt(Instant.parse("2027-10-06T10:00:00Z"));

    assertThat(
            service
                .createToken(
                    USER_ID, form(Instant.parse("2028-10-05T23:59:59Z"), TokenScope.REPO_READ))
                .getExpirationDate())
        .isEqualTo(Instant.parse("2028-10-05T23:59:59Z"));
    assertThatThrownBy(
            () ->
                service.createToken(
                    USER_ID, form(Instant.parse("2028-10-06T00:00:00Z"), TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenExpirationTooLate");
  }

  static Stream<AccessTokenForm> formsWithoutUsableScopes() {
    final var withNull = new java.util.LinkedHashSet<TokenScope>();

    withNull.add(null);

    return Stream.of(
        AccessTokenForm.builder().name("ci").scopes(Set.of()).build(),
        AccessTokenForm.builder().name("ci").scopes(null).build(),
        AccessTokenForm.builder().name("ci").scopes(withNull).build());
  }

  @ParameterizedTest
  @MethodSource("formsWithoutUsableScopes")
  @DisplayName("refuses a form with no scopes, or a null one, with validationError")
  void refusesAFormWithoutScopes(final AccessTokenForm form) {
    assertThatThrownBy(() -> this.service.createToken(USER_ID, form))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("validationError");

    verifyNoInteractions(this.tokenRepository, this.userRepository);
  }

  @Test
  @DisplayName("refuses to create a token for a user who is gone, and stores nothing")
  void refusesAUserWhoIsGone() {
    when(this.userRepository.lockUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ)))
        .isInstanceOf(UnAuthorizedException.class);

    verify(this.tokenRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("locks the user before it counts the tokens and before it inserts")
  void locksTheUserFirst() {
    this.whenUserExists();
    this.whenFlushed();
    when(this.tokenRepository.countByUserIdAndExpirationDateAfter(USER_ID, NOW)).thenReturn(3L);

    this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ));

    final var order = inOrder(this.userRepository, this.tokenRepository);

    order.verify(this.userRepository).lockUserIdForUpdate(USER_ID);
    order.verify(this.tokenRepository).countByUserIdAndExpirationDateAfter(USER_ID, NOW);
    order.verify(this.tokenRepository).saveAndFlush(any(PersonalAccessToken.class));
  }

  @Test
  @DisplayName("counts only the tokens that have not expired at the moment of the request")
  void countsOnlyTokensThatHaveNotExpired() {
    this.whenUserExists();
    this.whenFlushed();

    this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ));

    verify(this.tokenRepository).countByUserIdAndExpirationDateAfter(USER_ID, NOW);
    verify(this.tokenRepository, never()).countByUserId(any());
  }

  @Test
  @DisplayName("refuses the 51st token with accessTokenLimitReached, and allows the 50th")
  void capsTheTokensOfAUser() {
    this.whenUserExists();
    this.whenFlushed();
    when(this.tokenRepository.countByUserIdAndExpirationDateAfter(USER_ID, NOW))
        .thenReturn((long) PersonalAccessTokenService.MAX_TOKENS_PER_USER - 1)
        .thenReturn((long) PersonalAccessTokenService.MAX_TOKENS_PER_USER);

    assertThat(PersonalAccessTokenService.MAX_TOKENS_PER_USER).isEqualTo(50);
    assertThat(this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ)).getToken())
        .isNotBlank();

    assertThatThrownBy(() -> this.service.createToken(USER_ID, form(null, TokenScope.REPO_READ)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("accessTokenLimitReached");
    verify(this.tokenRepository).saveAndFlush(any());
  }

  // -------------------------------------------------------------------------------------------
  // revokeToken
  // -------------------------------------------------------------------------------------------

  private PersonalAccessToken tokenOf(final User owner) {
    final var token = new PersonalAccessToken();

    token.setId(UUID.randomUUID());
    token.setUser(owner);
    token.setName("ci");
    token.setTokenHash(TokenHash.hash("rut-secret"));
    token.setScopes(EnumSet.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ));
    token.setExpirationDate(Instant.now().plus(Duration.ofDays(30)));
    token.setCreatedAt(Instant.now());

    return token;
  }

  @Test
  @DisplayName("revokes a token of the user by deleting it")
  void revokesOwnToken() {
    final var token = this.tokenOf(this.user);
    when(this.tokenRepository.findByUserIdAndId(USER_ID, token.getId()))
        .thenReturn(Optional.of(token));

    this.service.revokeToken(USER_ID, token.getId());

    verify(this.tokenRepository).delete(token);
  }

  @Test
  @DisplayName("answers a token of another user as it answers one that does not exist")
  void doesNotRevokeAnotherUsersToken() {
    final var tokenId = UUID.randomUUID();
    when(this.tokenRepository.findByUserIdAndId(USER_ID, tokenId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.revokeToken(USER_ID, tokenId))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("accessTokenNotFound");

    verify(this.tokenRepository, never()).delete(any());
  }

  // -------------------------------------------------------------------------------------------
  // findByToken, getWhoAmI, updateLastUsedTime
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("looks a token up by the hash of its secret, never by the secret")
  void looksUpByHash() {
    final var token = this.tokenOf(this.user);
    final var secret = "rut-the-secret-of-the-token";
    when(this.tokenRepository.findByTokenHash(TokenHash.hash(secret)))
        .thenReturn(Optional.of(token));

    final var info = this.service.findByToken(secret);

    assertThat(info).isPresent();
    assertThat(info.get().id()).isEqualTo(token.getId());
    assertThat(info.get().userId()).isEqualTo(USER_ID);
    assertThat(info.get().username()).isEqualTo(USERNAME);
    assertThat(info.get().scopes()).containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ);
    verify(this.tokenRepository).findByTokenHash(TokenHash.hash(secret));
    verify(this.tokenRepository, never()).findByTokenHash(secret);
  }

  @Test
  @DisplayName("finds no token for a secret that was never issued")
  void findsNothingForAnUnknownSecret() {
    when(this.tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

    assertThat(this.service.findByToken("rut-unknown")).isEmpty();
  }

  @Test
  @DisplayName("hands an expired token back, marked as expired: the caller decides what that means")
  void returnsAnExpiredTokenAsExpired() {
    final var token = this.tokenOf(this.user);
    token.setExpirationDate(Instant.now().minusSeconds(1));
    when(this.tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

    final var info = this.service.findByToken("rut-expired");

    assertThat(info).isPresent();
    assertThat(info.get().isExpired()).isTrue();
  }

  @Test
  @DisplayName("says who a token is, what it may do and until when")
  void saysWhatTheTokenIs() {
    final var token = this.tokenOf(this.user);
    token.setLastUsedAt(Instant.now().minusSeconds(60));
    when(this.tokenRepository.findById(token.getId())).thenReturn(Optional.of(token));

    final var whoAmI = this.service.getWhoAmI(token.getId());

    assertThat(whoAmI.getUsername()).isEqualTo(USERNAME);
    assertThat(whoAmI.getName()).isEqualTo("ci");
    assertThat(whoAmI.getScopes()).containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ);
    assertThat(whoAmI.getExpirationDate()).isEqualTo(token.getExpirationDate());
    assertThat(whoAmI.getLastUsedAt()).isEqualTo(token.getLastUsedAt());
  }

  @Test
  @DisplayName("says nothing about a token that does not exist")
  void saysNothingAboutAnUnknownToken() {
    final var tokenId = UUID.randomUUID();
    when(this.tokenRepository.findById(tokenId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.getWhoAmI(tokenId))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("accessTokenNotFound");
  }

  @Test
  @DisplayName("records the time a token was last used")
  void recordsTheLastUse() {
    final var tokenId = UUID.randomUUID();

    this.service.updateLastUsedTime(tokenId);

    verify(this.tokenRepository).updateLastUsedTime(eq(tokenId), eq(NOW));
  }
}
