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

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.generated.model.AccessTokenForm;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.auth.PasswordHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The expiration date of a personal access token under H2 (RPS-1901): the service truncates it to
 * microseconds, which is what the column keeps, so the date in the answer of a create is the date
 * that is read back from the database. Without the truncation H2 rounds a finer value, like
 * PostgreSQL does, and the answer and the stored date differ in their last digits (and the last
 * nanosecond of a day is stored as the next day).
 *
 * <p>Runs inside the inherited rollback transaction; the token is read back after the persistence
 * context is cleared, so the value comes from the database.
 */
@DisplayName("PersonalAccessToken expiration date under H2: the answer is the stored date")
class H2PersonalAccessTokenIT extends H2IntegrationTest {

  private static final String PASSWORD_HASH = PasswordHasher.hash("Other1234!");

  @Autowired private PersonalAccessTokenService tokenService;
  @Autowired private PersonalAccessTokenRepository tokenRepository;
  @Autowired private UserTxService userTxService;
  @PersistenceContext private EntityManager entityManager;

  private UUID newUserId() {
    final var username = "h2pat" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    return this.userTxService.create(username, UserRole.USER, PASSWORD_HASH).getId();
  }

  private Instant storedExpirationOf(final UUID tokenId) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.tokenRepository.findById(tokenId).orElseThrow().getExpirationDate();
  }

  @Test
  @DisplayName("a date with nanoseconds is stored as the microseconds the answer carries")
  void aRequestedDateIsTheStoredDate() {
    final var userId = this.newUserId();
    final var sent =
        Instant.now()
            .plus(Duration.ofDays(30))
            .truncatedTo(ChronoUnit.SECONDS)
            .plusNanos(123_456_789);

    final var created =
        this.tokenService.createToken(
            userId,
            AccessTokenForm.builder()
                .name("ci")
                .scopes(Set.of(TokenScope.REPO_READ))
                .expirationDate(sent)
                .build());

    assertThat(created.getExpirationDate()).isEqualTo(sent.truncatedTo(ChronoUnit.MICROS));
    assertThat(this.storedExpirationOf(created.getId())).isEqualTo(created.getExpirationDate());
  }

  @Test
  @DisplayName("the default date, taken from a clock with nanoseconds, is the stored date")
  void theDefaultDateIsTheStoredDate() {
    final var userId = this.newUserId();

    final var created =
        this.tokenService.createToken(
            userId,
            AccessTokenForm.builder().name("ci").scopes(Set.of(TokenScope.REPO_READ)).build());

    assertThat(created.getExpirationDate())
        .isEqualTo(created.getExpirationDate().truncatedTo(ChronoUnit.MICROS));
    assertThat(this.storedExpirationOf(created.getId())).isEqualTo(created.getExpirationDate());
  }

  @Test
  @DisplayName(
      "the last instant of the last day stays on that day: it is not rounded into the next")
  void theLastInstantStaysOnTheLastDay() {
    final var userId = this.newUserId();
    final var lastDay =
        Instant.now().plus(Duration.ofDays(365)).atZone(java.time.ZoneOffset.UTC).toLocalDate();
    final var lastNanosecond =
        lastDay.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusNanos(1);

    final var created =
        this.tokenService.createToken(
            userId,
            AccessTokenForm.builder()
                .name("ci")
                .scopes(Set.of(TokenScope.REPO_READ))
                .expirationDate(lastNanosecond)
                .build());
    final var stored = this.storedExpirationOf(created.getId());

    assertThat(stored).isEqualTo(created.getExpirationDate());
    assertThat(stored.atZone(java.time.ZoneOffset.UTC).toLocalDate()).isEqualTo(lastDay);
  }
}
