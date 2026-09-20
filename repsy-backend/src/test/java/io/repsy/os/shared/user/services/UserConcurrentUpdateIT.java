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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.generated.model.UserUpdateForm;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RPS-1032: the async last-login update and a concurrent password, role or username change must not
 * undo each other.
 *
 * <p>The race is a lost update: one writer reads the row, a second one commits its change, and the
 * first then writes its stale copy back. To hit that window every time, each test lets one writer
 * take the row lock and hold it while the other writer runs on a second thread. The second writer
 * reads the row as it was and then blocks on the lock, so when the first one commits it writes
 * against a row it has already read. The test waits for that block to show up in {@code
 * pg_stat_activity} instead of sleeping.
 *
 * <p>It commits its rows, so it runs without the inherited test transaction and deletes exactly the
 * users it created.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("concurrent user updates")
class UserConcurrentUpdateIT extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 15;

  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private ExecutorService executor;

  @BeforeEach
  void startExecutor() {
    this.executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void cleanUp() {
    this.executor.shutdownNow();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private UserInfo commitUser() {
    final var user =
        this.userTxService.create(
            uniqueUsername("race"), UserRole.USER, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(user.getId());

    return user;
  }

  private User reload(final UUID id) {
    return this.userRepository.findById(id).orElseThrow();
  }

  /**
   * Runs {@code first} in a transaction that stays open until {@code second}, started on another
   * thread, is stuck waiting for the row lock {@code first} took. Then commits it.
   *
   * @return the outcome of {@code second}, which finishes once the lock is released
   */
  private Future<?> interleave(final Runnable first, final Runnable second) {
    final var holder = new TransactionTemplate(this.transactionManager);

    return holder.execute(
        status -> {
          first.run();
          // Writes the row now, so the lock is held before the second writer starts.
          this.entityManager.flush();

          final var pending = this.executor.submit(second);
          await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(this::aWriterIsWaitingOnUsers);

          return pending;
        });
  }

  private boolean aWriterIsWaitingOnUsers() {
    final var waiting =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from pg_stat_activity
            where datname = current_database()
              and pid <> pg_backend_pid()
              and wait_event_type = 'Lock'
              and query ilike '%users%'
            """,
            Integer.class);

    return waiting != null && waiting > 0;
  }

  private static void finishes(final Future<?> pending) {
    assertThatCode(() -> pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a login update in flight does not undo a password change")
  void loginUpdateKeepsANewPassword() {
    final var user = this.commitUser();
    final var newHash = PasswordHasher.hash("NewPassword2@");

    final var pending =
        this.interleave(
            () -> this.userTxService.updatePassword(user.getId(), newHash),
            () -> this.userTxService.updateLastLoginAt(user.getUsername()));
    finishes(pending);

    final var after = this.reload(user.getId());
    assertThat(after.getHash()).isEqualTo(newHash);
    assertThat(after.getTokenVersion())
        .as("the change revoked the refresh tokens, and the login update must not revive them")
        .isEqualTo(user.getTokenVersion() + 1);
    assertThat(after.getLastLoginAt()).as("the login is still recorded").isNotNull();
  }

  @Test
  @DisplayName("a login update in flight does not undo a role change")
  void loginUpdateKeepsANewRole() {
    final var user = this.commitUser();
    final var form =
        new UserUpdateForm()
            .username(user.getUsername())
            .role(io.repsy.os.generated.model.UserRole.ADMIN);

    final var pending =
        this.interleave(
            () -> this.userTxService.updateUserDetails(user.getId(), form),
            () -> this.userTxService.updateLastLoginAt(user.getUsername()));
    finishes(pending);

    final var after = this.reload(user.getId());
    assertThat(after.getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(after.getLastLoginAt()).isNotNull();
  }

  @Test
  @DisplayName("a login update in flight does not undo a username change")
  void loginUpdateKeepsANewUsername() {
    final var user = this.commitUser();
    final var newUsername = uniqueUsername("renamed");
    final var form =
        new UserUpdateForm().username(newUsername).role(io.repsy.os.generated.model.UserRole.USER);

    final var pending =
        this.interleave(
            () -> this.userTxService.updateUserDetails(user.getId(), form),
            () -> this.userTxService.updateLastLoginAt(user.getUsername()));

    // The login named the old username, which no longer exists once the rename commits.
    assertThatThrownBy(() -> pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .hasRootCauseInstanceOf(ItemNotFoundException.class);
    final var after = this.reload(user.getId());
    assertThat(after.getUsername()).isEqualTo(newUsername);
    assertThat(after.getTokenVersion()).isEqualTo(user.getTokenVersion() + 1);
  }

  @Test
  @DisplayName("a password change in flight does not erase the login it raced with")
  void passwordChangeKeepsTheLoginTime() {
    final var user = this.commitUser();
    final var newHash = PasswordHasher.hash("NewPassword2@");

    final var pending =
        this.interleave(
            () -> this.userTxService.updateLastLoginAt(user.getUsername()),
            () -> this.userTxService.updatePassword(user.getId(), newHash));
    finishes(pending);

    final var after = this.reload(user.getId());
    assertThat(after.getLastLoginAt()).isNotNull();
    assertThat(after.getHash()).isEqualTo(newHash);
    assertThat(after.getTokenVersion()).isEqualTo(user.getTokenVersion() + 1);
  }

  @Test
  @DisplayName("updateLastLoginAt for an unknown user still answers userNotFound")
  void unknownUserIsStillNotFound() {
    assertThatThrownBy(() -> this.userTxService.updateLastLoginAt(uniqueUsername("ghost")))
        .isExactlyInstanceOf(ItemNotFoundException.class)
        .hasMessage("userNotFound");
  }
}
