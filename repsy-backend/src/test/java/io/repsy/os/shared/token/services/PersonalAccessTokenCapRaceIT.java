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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.AbstractIT;
import io.repsy.os.generated.model.AccessTokenForm;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal access token creates under concurrency (RPS-1901).
 *
 * <p>{@code createToken} takes an exclusive lock on the user row before it counts the tokens that
 * have not expired and before it inserts, so two creates of one user are serialized and the second
 * counts the first one's token: at 49 tokens exactly one succeeds and the other is refused with
 * {@code accessTokenLimitReached}. With a shared lock both counted 49 and both inserted. The same
 * lock makes a deletion of the user that races a create either wait for it, or be seen by it as
 * {@code unAuthorized}; neither ends in a deadlock or a foreign-key error.
 *
 * <p>It commits its rows, so it runs without the inherited test transaction, and it deletes the
 * users it creates (the tokens go with them, the foreign key cascades).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("personal access token creates under concurrent requests (RPS-1901)")
class PersonalAccessTokenCapRaceIT extends AbstractIT {

  private static final int ROUNDS = 20;
  private static final int TIMEOUT_SECONDS = 30;

  @Autowired private PersonalAccessTokenService tokenService;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private ExecutorService executor;

  @BeforeEach
  void startExecutor() {
    this.executor = Executors.newFixedThreadPool(2);
  }

  @AfterEach
  void cleanUp() {
    this.executor.shutdownNow();

    for (final var userId : this.createdUserIds) {
      this.jdbcTemplate.update("delete from \"public\".\"users\" where \"id\" = ?", userId);
    }

    this.createdUserIds.clear();
  }

  private UUID newUserId() {
    final var id =
        this.userTxService
            .create(uniqueUsername("patrace"), UserRole.USER, VALID_PASSWORD_HASH)
            .getId();

    this.createdUserIds.add(id);

    return id;
  }

  private void seedTokens(final UUID userId, final int count) {
    for (var i = 0; i < count; i++) {
      this.jdbcTemplate.update(
          "insert into \"public\".\"personal_access_token\" (\"id\", \"user_id\", \"name\","
              + " \"token_hash\", \"scopes\", \"expiration_date\", \"created_at\")"
              + " values (?, ?, 't', ?, 'profile:read', ?, now())",
          UUID.randomUUID(),
          userId,
          UUID.randomUUID().toString().replace("-", "")
              + UUID.randomUUID().toString().replace("-", ""),
          Timestamp.from(Instant.now().plus(10, ChronoUnit.DAYS)));
    }
  }

  private long tokenCount(final UUID userId) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from \"public\".\"personal_access_token\" where \"user_id\" = ?",
        Long.class,
        userId);
  }

  private static AccessTokenForm form() {
    return AccessTokenForm.builder().name("race").scopes(Set.of(TokenScope.REPO_READ)).build();
  }

  private static String outcomeOf(final Throwable failure) {
    return switch (failure) {
      case final BadRequestException e -> e.getMessage();
      case final UnAuthorizedException ignored -> "unAuthorized";
      default -> failure.getClass().getSimpleName() + ": " + failure.getMessage();
    };
  }

  /** Runs both tasks at the same moment and returns what each of them ended with. */
  private List<String> runTogether(final Callable<Void> first, final Callable<Void> second)
      throws Exception {
    final var barrier = new CyclicBarrier(2);
    final var futures =
        List.of(
            this.executor.submit(() -> this.afterBarrier(barrier, first)),
            this.executor.submit(() -> this.afterBarrier(barrier, second)));
    final var outcomes = new ArrayList<String>();

    for (final var future : futures) {
      outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    return outcomes;
  }

  private String afterBarrier(final CyclicBarrier barrier, final Callable<Void> task)
      throws Exception {
    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    try {
      task.call();

      return "ok";
    } catch (final Exception e) {
      return outcomeOf(e);
    }
  }

  @Test
  @DisplayName("two creates at 49 tokens: exactly one succeeds, the other is refused, 50 remain")
  void theCapHoldsUnderConcurrency() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      final var userId = this.newUserId();
      this.seedTokens(userId, PersonalAccessTokenService.MAX_TOKENS_PER_USER - 1);

      final var outcomes =
          this.runTogether(
              () -> {
                this.tokenService.createToken(userId, form());
                return null;
              },
              () -> {
                this.tokenService.createToken(userId, form());
                return null;
              });

      assertThat(outcomes)
          .as("round %s", round)
          .containsExactlyInAnyOrder("ok", "accessTokenLimitReached");
      assertThat(this.tokenCount(userId))
          .as("round %s", round)
          .isEqualTo(PersonalAccessTokenService.MAX_TOKENS_PER_USER);
    }
  }

  @Test
  @DisplayName(
      "a create racing the deletion of the user: it succeeds or is refused, never a lock failure")
  void aCreateRacingADeletionNeitherDeadlocksNorFailsOnAForeignKey() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      final var userId = this.newUserId();

      final var outcomes =
          this.runTogether(
              () -> {
                this.tokenService.createToken(userId, form());
                return null;
              },
              () -> {
                this.jdbcTemplate.update(
                    "delete from \"public\".\"users\" where \"id\" = ?", userId);
                return null;
              });

      // The create is either first (and the deletion takes its token along through the foreign
      // key) or second (and finds the user gone). The deletion always succeeds.
      assertThat(outcomes).as("round %s", round).hasSize(2).contains("ok");
      assertThat(outcomes.stream().filter(o -> !"ok".equals(o)))
          .as("round %s", round)
          .allSatisfy(o -> assertThat(o).isEqualTo("unAuthorized"));
      assertThat(this.tokenCount(userId))
          .as("round %s: no token of a deleted user", round)
          .isZero();
    }
  }
}
