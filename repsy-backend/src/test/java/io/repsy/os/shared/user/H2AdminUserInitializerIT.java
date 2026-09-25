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
package io.repsy.os.shared.user;

import static io.repsy.os.shared.user.AdminPasswordResetChecks.NULL_HASH_SQL;
import static io.repsy.os.shared.user.AdminPasswordResetChecks.RESET_ALL_ADMINS_SQL;
import static io.repsy.os.shared.user.AdminPasswordResetChecks.loggedPasswordOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The H2 counterpart of {@link AdminUserInitializerIT} (RPS-1099): the README's "Forgot admin
 * password?" recovery on the embedded database. It runs the very statement the README gives, then
 * starts the initializer the way the application does at startup and checks that the new password
 * it logs is the one the admin's stored hash accepts.
 *
 * <p>Like every H2 test it rolls back with the rest of {@link H2IntegrationTest}, so the shared
 * in-memory database keeps its admin. It checks the hash with {@link PasswordHasher} instead of
 * logging in through MockMvc, which the H2 tests avoid so that they all share one application
 * context.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AdminUserInitializer password reset on the embedded H2 database (RPS-1099)")
class H2AdminUserInitializerIT extends H2IntegrationTest {

  private static final String ADMIN_USERNAME = "admin";

  @Autowired private AdminUserInitializer adminUserInitializer;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  private void startApplication() {
    this.adminUserInitializer.run(new DefaultApplicationArguments());
  }

  /**
   * The operator's statement as the operator's H2 shell sees it. The README has the operator open
   * that shell with {@code DATABASE_TO_LOWER=TRUE}, which reads the unquoted {@code users} as the
   * lower-case table the migrations created. The application's database is not opened that way
   * (RPS-1385), so this quotes the identifiers, which is what that setting amounts to.
   */
  private static String asOperatorShell(final String sql) {
    return sql.replaceAll("\\busers\\b", "\"public\".\"users\"")
        .replaceAll("\\b(hash|role|username)\\b", "\"$1\"");
  }

  /** Makes the database see the operator's SQL and Hibernate forget what it loaded before it. */
  private void applyOperatorSql(final String sql) {
    this.entityManager.flush();
    this.jdbcTemplate.update(asOperatorShell(sql));
    this.entityManager.clear();
  }

  private User reload(final UUID id) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userRepository.findById(id).orElseThrow();
  }

  private User admin() {
    return this.userRepository.findByUsername(ADMIN_USERNAME).orElseThrow();
  }

  @Test
  @DisplayName("users.hash is NOT NULL, so the recovery cannot use NULL")
  void nullHashIsRejectedBySchema() {
    assertThatThrownBy(() -> this.jdbcTemplate.update(asOperatorShell(NULL_HASH_SQL)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an admin whose hash was emptied gets a new password that its hash accepts")
  void emptiedAdminGetsNewPassword(final CapturedOutput output) {
    final var admin = this.admin();
    this.applyOperatorSql(RESET_ALL_ADMINS_SQL);
    assertThat(this.reload(admin.getId()).getHash()).isEmpty();

    this.startApplication();

    final var newPassword = loggedPasswordOf(admin, output);
    final var after = this.reload(admin.getId());
    assertThat(after.getHash()).isNotEmpty();
    assertThat(PasswordHasher.matches(newPassword, after.getHash())).isTrue();
  }

  @Test
  @DisplayName("an admin that already has a password is left alone")
  void untouchedAdminIsLeftAlone(final CapturedOutput output) {
    final var admin = this.admin();
    final var hash = admin.getHash();
    assertThat(hash).isNotEmpty();

    this.startApplication();

    assertThat(output.getAll()).doesNotContain("Admin password has been reset");
    assertThat(this.reload(admin.getId()).getHash()).isEqualTo(hash);
  }

  @Test
  @DisplayName("the new password is generated once, the next startup keeps it")
  void resetHappensOnlyOnce(final CapturedOutput output) {
    final var admin = this.admin();
    this.applyOperatorSql(RESET_ALL_ADMINS_SQL);
    this.startApplication();
    final var newPassword = loggedPasswordOf(admin, output);
    final var hash = this.reload(admin.getId()).getHash();

    this.startApplication();

    assertThat(output.getAll().split("Admin password has been reset", -1))
        .as("reset log lines")
        .hasSize(2);
    assertThat(this.reload(admin.getId()).getHash()).isEqualTo(hash);
    assertThat(PasswordHasher.matches(newPassword, hash)).isTrue();
  }
}
