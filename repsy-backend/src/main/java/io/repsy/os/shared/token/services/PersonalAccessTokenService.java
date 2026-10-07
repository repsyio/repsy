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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.generated.model.AccessTokenCreated;
import io.repsy.os.generated.model.AccessTokenForm;
import io.repsy.os.generated.model.AccessTokenWhoAmI;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenInfo;
import io.repsy.os.shared.token.dtos.PersonalAccessTokenListItem;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.mappers.PersonalAccessTokenConverter;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The personal access tokens of a user: create, list, revoke, and the lookup by secret that
 * authenticating with one will need. It does not authenticate anything itself.
 */
@Service
@Transactional(readOnly = true)
public class PersonalAccessTokenService {

  /**
   * The most tokens a user can hold that have not expired. An expired token is not counted: it
   * stays in the list until its owner revokes it, but it never stands in the way of a new one.
   *
   * <p>This is a guard rail against runaway scripts. The count is checked while the user row is
   * locked exclusively for the insert, so two creates of one user are serialized and the second
   * counts the first one's row: the cap holds under concurrency, as in Cloud.
   */
  public static final int MAX_TOKENS_PER_USER = 50;

  /** The expiry of a token created without a date, and the furthest a date can be set. */
  private static final Duration DEFAULT_EXPIRATION_DURATION = Duration.ofDays(365);

  private final @NonNull PersonalAccessTokenRepository tokenRepository;
  private final @NonNull UserRepository userRepository;
  private final @NonNull PersonalAccessTokenConverter converter;
  private final @NonNull Clock clock;

  @Autowired
  public PersonalAccessTokenService(
      final @NonNull PersonalAccessTokenRepository tokenRepository,
      final @NonNull UserRepository userRepository,
      final @NonNull PersonalAccessTokenConverter converter) {
    this(tokenRepository, userRepository, converter, Clock.systemUTC());
  }

  /**
   * A service that reads {@code clock} instead of the system clock, so a test can put "now" on
   * either side of midnight UTC, where the last day of the expiration date changes.
   */
  PersonalAccessTokenService(
      final @NonNull PersonalAccessTokenRepository tokenRepository,
      final @NonNull UserRepository userRepository,
      final @NonNull PersonalAccessTokenConverter converter,
      final @NonNull Clock clock) {
    this.tokenRepository = tokenRepository;
    this.userRepository = userRepository;
    this.converter = converter;
    this.clock = clock;
  }

  public @NonNull Page<PersonalAccessTokenListItem> getTokens(
      final @NonNull UUID userId, final @NonNull Pageable pageable) {

    return this.tokenRepository.findAllByUserId(userId, pageable);
  }

  /**
   * The token whose secret is {@code token}, expired or not: the caller decides what an expired one
   * means ({@link PersonalAccessTokenInfo#isExpired()}).
   */
  public @NonNull Optional<PersonalAccessTokenInfo> findByToken(final @NonNull String token) {

    return this.tokenRepository.findByTokenHash(TokenHash.hash(token)).map(this.converter::toInfo);
  }

  /**
   * What a token says about itself, for the route a client calls with a token to learn what it is.
   *
   * @throws ItemNotFoundException if there is no such token
   */
  public @NonNull AccessTokenWhoAmI getWhoAmI(final @NonNull UUID tokenId) {

    final var token =
        this.tokenRepository
            .findById(tokenId)
            .orElseThrow(() -> new ItemNotFoundException("accessTokenNotFound"));

    return this.converter.toWhoAmI(this.converter.toInfo(token));
  }

  /**
   * Creates a token for {@code userId}. The answer carries the secret, which is returned once and
   * never stored: only its hash is.
   *
   * @throws BadRequestException if no scope is asked for, the date is in the past or too far ahead,
   *     or the user already holds {@link #MAX_TOKENS_PER_USER} tokens
   */
  @Transactional
  public @NonNull AccessTokenCreated createToken(
      final @NonNull UUID userId, final @NonNull AccessTokenForm form) {

    final var now = this.now();
    final var scopes = requestedScopes(form);
    final var expirationDate = resolveExpirationDate(form.getExpirationDate(), now);

    this.lockUser(userId);
    this.requireRoom(userId, now);

    final var secret = TokenFactory.personalAccessToken();
    final var token = new PersonalAccessToken();

    token.setUser(this.userRepository.getReferenceById(userId));
    token.setName(form.getName());
    token.setTokenHash(TokenHash.hash(secret));
    token.setScopes(TokenScope.withImplicit(scopes));
    token.setExpirationDate(expirationDate);

    // Flushed here, not at commit: the creation time is generated by the INSERT, and the answer
    // reports it.
    this.tokenRepository.saveAndFlush(token);

    return this.converter.toCreated(this.converter.toInfo(token), secret);
  }

  /**
   * Revokes a token of {@code userId}. A token of another user answers the same as one that does
   * not exist, so nobody learns which ids are taken.
   *
   * @throws ItemNotFoundException if the user holds no such token
   */
  @Transactional
  public void revokeToken(final @NonNull UUID userId, final @NonNull UUID tokenId) {

    final var token =
        this.tokenRepository
            .findByUserIdAndId(userId, tokenId)
            .orElseThrow(() -> new ItemNotFoundException("accessTokenNotFound"));

    this.tokenRepository.delete(token);
  }

  @Transactional
  public void updateLastUsedTime(final @NonNull UUID tokenId) {

    this.tokenRepository.updateLastUsedTime(tokenId, this.clock.instant());
  }

  /**
   * The moment of the request, truncated to microseconds, which is what the column keeps. The
   * database rounds a finer value, and that would make the answer differ from the stored date in
   * its last digits, and round the very last instant of a day into the next day.
   */
  private @NonNull Instant now() {

    return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static @NonNull Set<TokenScope> requestedScopes(final @NonNull AccessTokenForm form) {

    final var scopes = form.getScopes();

    if (scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(Objects::isNull)) {
      throw new BadRequestException("validationError");
    }

    return scopes;
  }

  /**
   * The date a token expires on: the one asked for (truncated to microseconds) if it is in the
   * future and not beyond the last day the panel offers, and 365 days from {@code now} if none was
   * asked for.
   */
  private static @NonNull Instant resolveExpirationDate(
      final @Nullable Instant requested, final @NonNull Instant now) {

    if (requested == null) {
      return now.plus(DEFAULT_EXPIRATION_DURATION);
    }

    // Truncated before it is checked, so that what is checked is what is stored: a date a
    // nanosecond after now is now once truncated, and a date in the last nanosecond of the last
    // day stays on that day instead of being rounded into the next one by the database.
    final var date = requested.truncatedTo(ChronoUnit.MICROS);

    if (!date.isAfter(now)) {
      throw new BadRequestException("accessTokenExpirationInPast");
    }

    if (isBeyondMaximumExpiration(date, now)) {
      throw new BadRequestException("accessTokenExpirationTooLate");
    }

    return date;
  }

  /**
   * Whether the date falls after the last day a token can last, which is today plus {@link
   * #DEFAULT_EXPIRATION_DURATION} in UTC. The days are compared, not the instants: a client sends
   * the chosen day at the current time of day, so an instant comparison would reject its own
   * maximum by a few milliseconds.
   */
  private static boolean isBeyondMaximumExpiration(
      final @NonNull Instant expirationDate, final @NonNull Instant now) {

    final var lastDay = now.plus(DEFAULT_EXPIRATION_DURATION).atZone(ZoneOffset.UTC).toLocalDate();

    return expirationDate.atZone(ZoneOffset.UTC).toLocalDate().isAfter(lastDay);
  }

  /**
   * Locks the user row exclusively until the transaction ends. Two creates of one user are
   * serialized by it (the second waits, then counts the first one's token), and a deletion of the
   * user that races this request either waits for the insert, or has already committed and is
   * caught here, instead of a foreign-key violation surfacing from the insert.
   */
  private void lockUser(final @NonNull UUID userId) {

    if (this.userRepository.lockUserIdForUpdate(userId).isEmpty()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }
  }

  private void requireRoom(final @NonNull UUID userId, final @NonNull Instant now) {

    if (this.tokenRepository.countByUserIdAndExpirationDateAfter(userId, now)
        >= MAX_TOKENS_PER_USER) {
      throw new BadRequestException("accessTokenLimitReached");
    }
  }
}
