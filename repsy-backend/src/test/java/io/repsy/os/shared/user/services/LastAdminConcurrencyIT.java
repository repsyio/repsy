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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RPS-1101: two requests that each remove one of the last two admins must not both succeed.
 *
 * <p>The guard used to count the admins and then write, so under READ COMMITTED two requests both
 * counted two and both committed, and the instance was left without an ADMIN. The admin rows are
 * now locked before they are counted.
 *
 * <p>Two kinds of test pin that. The first kind keeps a transaction open on the test thread, as a
 * request that is half way through would, and starts the operation on another thread. The database
 * lock is what the test relies on: the operation has to wait until the other transaction ends, and
 * then it has to see what that transaction did. The second kind lets two operations loose at the
 * same time behind a barrier, a few rounds in a row, and checks that exactly one of them is
 * refused.
 *
 * <p>It commits its rows, so it runs without the inherited test transaction. The seeded admin is
 * demoted with SQL for the length of each test, so the two admins created here really are the last
 * two, and it is restored afterwards. The row counts stay the same, so the leak guard is satisfied,
 * and the IT classes run one after the other.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("last admin guards under concurrent requests (RPS-1101)")
class LastAdminConcurrencyIT extends AbstractIntegrationTest {

  private static final int ROUNDS = 8;
  private static final String CANNOT_DEMOTE = "cannotDemoteLastAdminUser";
  private static final String CANNOT_DELETE = "cannotDeleteLastAdminUser";

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
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
    this.demotedAdminIds.forEach(
        id -> this.jdbcTemplate.update("update users set role = 'ADMIN' where id = ?", id));
    this.demotedAdminIds.clear();
  }

  /**
   * Leaves exactly two ADMINs in the database: everyone else that holds the role, the seeded admin
   * or a survivor of an earlier round, is demoted with SQL.
   */
  private void newAdminPair() {
    final var admins =
        this.jdbcTemplate.queryForList("select id from users where role = 'ADMIN'", UUID.class);
    admins.stream()
        .filter(id -> !this.createdUserIds.contains(id))
        .forEach(this.demotedAdminIds::add);
    this.jdbcTemplate.update("update users set role = 'USER' where role = 'ADMIN'");

    this.adminA = this.commitUser(UserRole.ADMIN);
    this.adminB = this.commitUser(UserRole.ADMIN);
  }

  private UserInfo commitUser(final UserRole role) {
    final var user =
        this.userTxService.create(
            uniqueUsername("lastadmin"), role, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(user.getId());

    return user;
  }

  private static UserUpdateForm roleForm(
      final UserInfo user, final io.repsy.os.generated.model.UserRole role) {
    return new UserUpdateForm().username(user.getUsername()).role(role);
  }

  private String roleOf(final UserInfo user) {
    return this.jdbcTemplate
        .queryForList("select role from users where id = ?", String.class, user.getId())
        .stream()
        .findFirst()
        .orElse("deleted");
  }

  private long adminCount() {
    return this.userRepository.countByRole(UserRole.ADMIN);
  }

  // ---------------------------------------------------------------------------------------------
  // Operations, reported as OK or the message id they failed with
  // ---------------------------------------------------------------------------------------------

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
                    target.getId(), roleForm(target, io.repsy.os.generated.model.UserRole.USER)));
  }

  private Callable<String> deletion(final UserInfo target) {
    return () -> attempt(() -> this.userTxService.deleteUserById(target.getId()));
  }

  /** {@code DELETE /api/profile} as {@code user}. */
  private Callable<String> selfDelete(final UserInfo user) {
    return () ->
        outcomeOfResponse(
            this.perform(
                delete("/api/profile")
                    .header(AUTHORIZATION, this.bearerTokenFor(user.getId(), user.getUsername()))));
  }

  /** {@code PUT /api/users/{id}} as {@code user}, taking the role away from that same user. */
  private Callable<String> selfDemote(final UserInfo user) {
    return () ->
        outcomeOfResponse(
            this.perform(
                put("/api/users/" + user.getId())
                    .header(AUTHORIZATION, this.bearerTokenFor(user.getId(), user.getUsername()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"%s\",\"role\":\"USER\"}".formatted(user.getUsername()))));
  }

  private static String outcomeOfResponse(
      final org.springframework.test.web.servlet.ResultActions result) throws Exception {
    final var response = result.andReturn().getResponse();
    if (response.getStatus() == 200) {
      return OK;
    }
    return JsonPath.read(response.getContentAsString(), "$.msgId");
  }

  // ---------------------------------------------------------------------------------------------
  // The service waits for the admin rows, and then reads what the other transaction left
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a demotion waits for a demotion in flight and then refuses to remove the last admin")
  void demotionWaitsForADemotionInFlight() throws Exception {
    final var demoteB = "update users set role = 'USER' where id = ?";

    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(demoteB, this.adminB.getId());
              return this.startBlocked(this.demotion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DEMOTE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.roleOf(this.adminB)).isEqualTo("USER");
    assertThat(this.adminCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName(
      "a deletion waits for a demotion in flight and then refuses to delete the last admin")
  void deletionWaitsForADemotionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "update users set role = 'USER' where id = ?", this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DELETE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.adminCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName(
      "a deletion waits for a deletion in flight and then refuses to delete the last admin")
  void deletionWaitsForADeletionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update("delete from users where id = ?", this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DELETE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.roleOf(this.adminB)).isEqualTo("deleted");
  }

  @Test
  @DisplayName("deleting the profile waits for a demotion in flight and keeps the last admin")
  void selfDeletionWaitsForADemotionInFlight() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update(
                  "update users set role = 'USER' where id = ?", this.adminB.getId());
              return this.startBlocked(this.selfDelete(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(CANNOT_DELETE);
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
    assertThat(this.adminCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("a user that another request deleted meanwhile is not found, not deleted twice")
  void aUserDeletedMeanwhileIsNotFound() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              this.jdbcTemplate.update("delete from users where id = ?", this.adminB.getId());
              return this.startBlocked(this.deletion(this.adminB));
            });

    assertThat(outcomeOf(pending)).isEqualTo("userNotFound");
    assertThat(this.roleOf(this.adminA)).isEqualTo("ADMIN");
  }

  @Test
  @DisplayName("a demotion waits for whoever holds the admin lock, then goes through if it is fine")
  void demotionWaitsForTheLockAndThenProceeds() throws Exception {
    final var pending =
        this.holder.execute(
            status -> {
              assertThat(this.userRepository.lockIdsByRole(UserRole.ADMIN)).hasSize(2);
              return this.startBlocked(this.demotion(this.adminA));
            });

    assertThat(outcomeOf(pending)).isEqualTo(OK);
    assertThat(this.roleOf(this.adminA)).isEqualTo("USER");
    assertThat(this.roleOf(this.adminB)).isEqualTo("ADMIN");
  }

  @Test
  @DisplayName("a promotion does not wait for the admin lock: it can only add an admin")
  void promotionDoesNotWait() throws Exception {
    final var plainUser = this.commitUser(UserRole.USER);
    final var form = roleForm(plainUser, io.repsy.os.generated.model.UserRole.ADMIN);

    final var outcome =
        this.holder.execute(
            status -> {
              this.userRepository.lockIdsByRole(UserRole.ADMIN);
              return this.executor.submit(
                  () ->
                      attempt(() -> this.userTxService.updateUserDetails(plainUser.getId(), form)));
            });

    assertThat(outcomeOf(outcome)).isEqualTo(OK);
    assertThat(this.roleOf(plainUser)).isEqualTo("ADMIN");
  }

  private java.util.concurrent.Future<String> startBlocked(final Callable<String> operation) {
    try {
      return submitAndExpectBlocked(this.executor, operation);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Two requests at once: exactly one of them is refused, and an ADMIN is left
  // ---------------------------------------------------------------------------------------------

  private void assertOneRefusedWith(final List<String> outcomes, final String... allowedRefusals) {
    assertThat(outcomes)
        .as("exactly one of the two requests succeeds")
        .containsOnlyOnce(OK)
        .hasSize(2);
    assertThat(outcomes.stream().filter(outcome -> !OK.equals(outcome)))
        .as("the other one is refused")
        .allSatisfy(outcome -> assertThat(outcome).isIn((Object[]) allowedRefusals));
    assertThat(this.adminCount()).as("an ADMIN is left").isGreaterThanOrEqualTo(1L);
  }

  @Test
  @DisplayName("two admins demoting each other at once: one is refused")
  void twoDemotionsAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      final var outcomes =
          runTogether(this.executor, this.demotion(this.adminB), this.demotion(this.adminA));

      this.assertOneRefusedWith(outcomes, CANNOT_DEMOTE);
    }
  }

  @Test
  @DisplayName("two admins deleting each other at once: one is refused")
  void twoDeletionsAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      final var outcomes =
          runTogether(this.executor, this.deletion(this.adminB), this.deletion(this.adminA));

      this.assertOneRefusedWith(outcomes, CANNOT_DELETE);
    }
  }

  @Test
  @DisplayName("one admin demoting the other while that one deletes the first: one is refused")
  void aDemotionAndADeletionAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      final var outcomes =
          runTogether(this.executor, this.demotion(this.adminB), this.deletion(this.adminA));

      this.assertOneRefusedWith(outcomes, CANNOT_DEMOTE, CANNOT_DELETE);
    }
  }

  @Test
  @DisplayName("an admin deleting their profile while the other one demotes themselves: one fails")
  void aSelfDeletionAndASelfDemotionAtOnce() throws Exception {
    for (var round = 0; round < ROUNDS; round++) {
      this.newAdminPair();

      final var outcomes =
          runTogether(this.executor, this.selfDelete(this.adminA), this.selfDemote(this.adminB));

      this.assertOneRefusedWith(outcomes, CANNOT_DEMOTE, CANNOT_DELETE);
    }
  }
}
