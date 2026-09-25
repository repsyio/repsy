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
package io.repsy.os.shared.user.services;

import static io.repsy.os.shared.user.services.LastAdminRaces.OK;
import static io.repsy.os.shared.user.services.LastAdminRaces.outcomeOf;
import static io.repsy.os.shared.user.services.LastAdminRaces.runTogether;
import static io.repsy.os.shared.user.services.LastAdminRaces.submitAndExpectBlocked;
import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The H2 counterpart of {@link LastAdminConcurrencyIT} (RPS-1101): the embedded database has to
 * serialize the last-admin checks as PostgreSQL does. H2 rechecks the row after it waited, as
 * PostgreSQL does under READ COMMITTED, so the operation that waited sees the admins that are left.
 *
 * <p>Like the PostgreSQL test it commits its rows, so it runs without the inherited test
 * transaction, demotes the seeded admin for the length of each test, and removes what it created.
 * It has no HTTP cases: the H2 tests avoid MockMvc so that they share one application context. The
 * blocked operation waits for less than H2's lock timeout, which pooled sessions keep from the time
 * they were opened and which {@code SET DEFAULT_LOCK_TIMEOUT} does not raise for them.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("last admin guards under concurrent requests on the embedded H2 database (RPS-1101)")
class H2LastAdminConcurrencyIT extends H2IntegrationTest {

  private static final int ROUNDS = 8;
  private static final Duration STILL_BLOCKED = Duration.ofMillis(500);
  private static final String CANNOT_DEMOTE = "cannotDemoteLastAdminUser";
  private static final String CANNOT_DELETE = "cannotDeleteLastAdminUser";

  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> demotedAdminIds = new ArrayList<>();
  private ExecutorService executor;
  private TransactionTemplate holder;
  private UserInfo adminA;
  private UserInfo adminB;

  @BeforeEach
  void startWithTwoAdmins() {
    this.executor = Executors.newFixedThreadPool(2);
    this.holder = new TransactionTemplate(this.transactionManager);
    this.newAdminPair();
  }

  @AfterEach
  void cleanUp() {
    this.executor.shutdownNow();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.demotedAdminIds.forEach(
        id ->
            this.jdbcTemplate.update(
                "update \"public\".\"users\" set \"role\" = 'ADMIN' where \"id\" = ?", id));
    this.demotedAdminIds.clear();
  }

  private void newAdminPair() {
    final var admins =
        this.jdbcTemplate.queryForList(
            "select \"id\" from \"public\".\"users\" where \"role\" = 'ADMIN'", UUID.class);
    admins.stream()
        .filter(id -> !this.createdUserIds.contains(id))
        .forEach(this.demotedAdminIds::add);
    this.jdbcTemplate.update(
        "update \"public\".\"users\" set \"role\" = 'USER' where \"role\" = 'ADMIN'");

    this.adminA = this.commitUser();
    this.adminB = this.commitUser();
  }

  private UserInfo commitUser() {
    final var user =
        this.userTxService.create(
            "h2lastadmin" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
            UserRole.ADMIN,
            PasswordHasher.hash("Password1!"));
    this.createdUserIds.add(user.getId());
    return user;
  }

  private String roleOf(final UserInfo user) {
    return this.jdbcTemplate
        .queryForList(
            "select \"role\" from \"public\".\"users\" where \"id\" = ?",
            String.class,
            user.getId())
        .stream()
        .findFirst()
        .orElse("deleted");
  }

  private static String attempt(final Runnable operation) {
    try {
      operation.run();
      return OK;
    } catch (final RuntimeException e) {
      return e.getMessage();
    }
  }

  private Callable<String> demotion(final UserInfo target) {
    return () ->
        attempt(
            () ->
                this.userTxService.updateUserDetails(
                    target.getId(),
                    new UserUpdateForm()
                        .username(target.getUsername())
                        .role(io.repsy.os.generated.model.UserRole.USER)));
  }

  private Callable<String> deletion(final UserInfo target) {
    return () -> attempt(() -> this.userTxService.deleteUserById(target.getId()));
  }

  private Future<String> startBlocked(final Callable<String> operation) {
    try {
      return submitAndExpectBlocked(this.executor, operation, STILL_BLOCKED);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
      "a demotion waits for a demotion in flight and then refuses to remove the last admin")
  void demotionWaitsForADemotionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "update \"public\".\"users\" set \"role\" = 'USER' where \"id\" = ?",
                  this.adminB.getId());
              return this.startBlocked(this.demotion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DEMOTE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.roleOf(this.adminB)).isEqualTo("USER");
  }

  @Test
  @DisplayName(
      "a deletion waits for a demotion in flight and then refuses to delete the last admin")
  void deletionWaitsForADemotionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "update \"public\".\"users\" set \"role\" = 'USER' where \"id\" = ?",
                  this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DELETE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
  }

  @Test
  @DisplayName(
      "a deletion waits for a deletion in flight and then refuses to delete the last admin")
  void deletionWaitsForADeletionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "delete from \"public\".\"users\" where \"id\" = ?", this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DELETE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.roleOf(this.adminB)).isEqualTo("deleted");
  }

  @Test
  @DisplayName("a user that another request deleted meanwhile is not found, not deleted twice")
  void aUserDeletedMeanwhileIsNotFound() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "delete from \"public\".\"users\" where \"id\" = ?", this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminB));
            });

    assertThat(outcomeOf(pending)).isEqualTo("userNotFound");
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
  }

  // ---------------------------------------------------------------------------------------------
  // Two requests at once: exactly one of them is refused, and an ADMIN is left
  // ---------------------------------------------------------------------------------------------

  /**
   * H2 has answered with the same message ids as PostgreSQL every time, but the safety property is
   * that one request goes through, the other is turned down, and an ADMIN is left, so the refusal
   * itself is not pinned here.
   */
  private void assertOneRefused(final List<String> outcomes) {
    assertThat(outcomes)
        .as("exactly one of the two requests succeeds")
        .containsOnlyOnce(OK)
        .hasSize(2);
    assertThat(this.userRepository.countByRole(UserRole.ADMIN))
        .as("an ADMIN is left")
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  @DisplayName("two admins demoting each other at once: one is refused")
  void twoDemotionsAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      this.assertOneRefused(
          runTogether(this.executor, this.demotion(this.adminB), this.demotion(this.adminA)));
    }
  }

  @Test
  @DisplayName("two admins deleting each other at once: one is refused")
  void twoDeletionsAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      this.assertOneRefused(
          runTogether(this.executor, this.deletion(this.adminB), this.deletion(this.adminA)));
    }
  }

  @Test
  @DisplayName("one admin demoting the other while that one deletes the first: one is refused")
  void aDemotionAndADeletionAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      this.assertOneRefused(
          runTogether(this.executor, this.demotion(this.adminB), this.deletion(this.adminA)));
    }
  }
}
