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
package io.repsy.os.panel.profile.controllers;

import static io.repsy.os.server.shared.http.BareBodyAssertions.expectCreated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The expiration date of a personal access token (RPS-1901) through the real request: none gives a
 * year, a date has to be in the future and within that year, and every other date is refused with
 * its own code.
 *
 * <p>This test does not depend on the time of day it runs at. The last day a token can last is a
 * UTC calendar day, which changes at midnight, so the dates here keep a margin of more than a day
 * from it (364 days is accepted, 367 is refused) and the past is at least a second back. The exact
 * boundary, to the nanosecond, the offsets and the midnight itself are pinned in {@code
 * PersonalAccessTokenServiceTest}, which runs on a fixed clock.
 */
@DisplayName("PersonalAccessToken expiration date")
class PatExpiryValidationIT extends AbstractIntegrationTest {

  private static final String BASE = "/api/profile/access-tokens";
  private static final String IN_PAST_TEXT =
      "The expiration date of an access token has to be in the future.";
  private static final String TOO_LATE_TEXT = "An access token can expire in 365 days at most.";

  @Autowired private PersonalAccessTokenRepository tokenRepository;

  private User alice;
  private String bearer;

  @BeforeEach
  void setUp() {
    this.alice = this.createUser(uniqueUsername("expiry"), UserRole.USER);
    this.bearer = this.bearerTokenFor(this.alice);
  }

  private ResultActions createWithBody(final String json) throws Exception {
    return this.perform(
        post(BASE)
            .with(apiPort())
            .header(AUTHORIZATION, this.bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions createWithDate(final String expirationDate) throws Exception {
    final var date = expirationDate == null ? "null" : "\"" + expirationDate + "\"";

    return this.createWithBody(
        "{\"name\":\"ci\",\"scopes\":[\"repo:read\"],\"expirationDate\":%s}".formatted(date));
  }

  private ResultActions createWithDate(final Instant expirationDate) throws Exception {
    return this.createWithDate(expirationDate.truncatedTo(ChronoUnit.MILLIS).toString());
  }

  private void expectInPast(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.BAD_REQUEST,
        "accessTokenExpirationInPast",
        "accessTokenExpirationInPast",
        IN_PAST_TEXT);
    assertThat(this.tokenRepository.countByUserId(this.alice.getId())).isZero();
  }

  private void expectTooLate(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.BAD_REQUEST,
        "accessTokenExpirationTooLate",
        "accessTokenExpirationTooLate",
        TOO_LATE_TEXT);
    assertThat(this.tokenRepository.countByUserId(this.alice.getId())).isZero();
  }

  private static Instant expirationOf(final String body) {
    return Instant.parse(JsonPath.read(body, "$.expirationDate"));
  }

  // ---------------------------------------------------------------------------------------------
  // No date, and a date that is fine
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a token created without a date expires in 365 days")
  void noDateIsAYear() throws Exception {
    final var before = Instant.now();

    final var body =
        expectCreated(this.createWithBody("{\"name\":\"ci\",\"scopes\":[\"repo:read\"]}"));

    assertThat(expirationOf(body))
        .isBetween(before.plus(Duration.ofDays(365)), Instant.now().plus(Duration.ofDays(365)));
  }

  @Test
  @DisplayName("a null date is the same as no date")
  void aNullDateIsNoDate() throws Exception {
    final var before = Instant.now();

    final var body = expectCreated(this.createWithDate((String) null));

    assertThat(expirationOf(body))
        .isBetween(before.plus(Duration.ofDays(365)), Instant.now().plus(Duration.ofDays(365)));
  }

  @Test
  @DisplayName("a date in the future and within the year is kept as it was sent")
  void keepsAFutureDate() throws Exception {
    final var date = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MILLIS);

    final var body = expectCreated(this.createWithDate(date));

    assertThat(expirationOf(body)).isEqualTo(date);
  }

  @Test
  @DisplayName("a date with nanoseconds is stored, and answered, truncated to microseconds")
  void storesAndAnswersMicroseconds() throws Exception {
    final var date = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    final var sent = date.plusNanos(123_456_789);

    final var body = expectCreated(this.createWithDate(sent.toString()));

    assertThat(expirationOf(body)).isEqualTo(date.plusNanos(123_456_000));
    // Read back from the database, not from the persistence context.
    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(
            this.tokenRepository
                .findById(java.util.UUID.fromString(JsonPath.<String>read(body, "$.id")))
                .orElseThrow()
                .getExpirationDate())
        .isEqualTo(expirationOf(body));
  }

  @Test
  @DisplayName("a date a minute ahead is accepted: it only has to be in the future")
  void acceptsTheNearFuture() throws Exception {
    expectCreated(this.createWithDate(Instant.now().plus(Duration.ofMinutes(1))));
  }

  @Test
  @DisplayName("a date 364 days ahead is accepted, whatever the time of day")
  void acceptsTheLastDaysOfTheYear() throws Exception {
    final var date = Instant.now().plus(Duration.ofDays(364)).truncatedTo(ChronoUnit.MILLIS);

    final var body = expectCreated(this.createWithDate(date));

    assertThat(expirationOf(body)).isEqualTo(date);
  }

  // ---------------------------------------------------------------------------------------------
  // The upper limit
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a date 367 days ahead is refused with accessTokenExpirationTooLate")
  void refusesADayTooFar() throws Exception {
    this.expectTooLate(this.createWithDate(Instant.now().plus(Duration.ofDays(367))));
  }

  @Test
  @DisplayName("years ahead is refused with accessTokenExpirationTooLate")
  void refusesYearsAhead() throws Exception {
    this.expectTooLate(this.createWithDate(Instant.now().plus(Duration.ofDays(3650))));
    this.expectTooLate(this.createWithDate("9999-12-31T23:59:59Z"));
  }

  // ---------------------------------------------------------------------------------------------
  // The lower limit: the future
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a date in the past is refused with accessTokenExpirationInPast")
  void refusesThePast() throws Exception {
    this.expectInPast(this.createWithDate(Instant.now().minus(Duration.ofDays(1))));
    this.expectInPast(this.createWithDate(Instant.now().minusSeconds(1)));
    this.expectInPast(this.createWithDate("1970-01-01T00:00:00Z"));
    this.expectInPast(this.createWithDate(Instant.now().minus(Duration.ofDays(3650))));
  }

  @Test
  @DisplayName("the past and the far future are told apart: two codes, two texts")
  void thePastAndTheFutureHaveTheirOwnCodes() throws Exception {
    final var past = this.createWithDate(Instant.now().minus(Duration.ofDays(1)));
    final var future = this.createWithDate(Instant.now().plus(Duration.ofDays(400)));

    assertThat(past.andReturn().getResponse().getContentAsString())
        .contains("accessTokenExpirationInPast")
        .doesNotContain("accessTokenExpirationTooLate");
    assertThat(future.andReturn().getResponse().getContentAsString())
        .contains("accessTokenExpirationTooLate")
        .doesNotContain("accessTokenExpirationInPast");
  }

  @Test
  @DisplayName("a refused date creates no token and leaves no secret behind")
  void aRefusedDateStoresNothing() throws Exception {
    this.createWithDate(Instant.now().minus(Duration.ofDays(1)));
    this.createWithDate(Instant.now().plus(Duration.ofDays(400)));

    assertThat(this.tokenRepository.countByUserId(this.alice.getId())).isZero();
  }
}
