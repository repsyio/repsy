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
import static io.repsy.os.shared.user.AdminPasswordResetChecks.RESET_ONE_ADMIN_SQL;
import static io.repsy.os.shared.user.AdminPasswordResetChecks.loggedPasswordOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

/**
 * RPS-1026: the "Forgot admin password?" recovery in the README. It runs the very statements the
 * README gives, then starts the initializer the way the application does at startup and checks that
 * the admin can log in with the password it logs. {@link H2AdminUserInitializerIT} checks the same
 * recovery on the embedded H2 database (RPS-1099).
 *
 * <p>{@code AdminUserInitializer.run} is {@code @Transactional}, so it joins the test transaction
 * and sees the rows the test changed, and the rollback undoes everything.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AdminUserInitializer password reset")
class AdminUserInitializerIT extends AbstractIntegrationTest {

  @Autowired private AdminUserInitializer adminUserInitializer;

  private void startApplication() {
    this.adminUserInitializer.run(new DefaultApplicationArguments());
  }

  /** Makes the database see the operator's SQL and Hibernate forget what it loaded before it. */
  private void applyOperatorSql(final String sql, final Object... args) {
    this.entityManager.flush();
    this.jdbcTemplate.update(sql, args);
    this.entityManager.clear();
  }

  private User reload(final UUID id) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userRepository.findById(id).orElseThrow();
  }

  private void login(final String username, final String password, final int expectedStatus)
      throws Exception {

    this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  @DisplayName("users.hash is NOT NULL, so the recovery cannot use NULL")
  void nullHashIsRejectedBySchema() {
    assertThatThrownBy(() -> this.jdbcTemplate.update(NULL_HASH_SQL))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("an admin whose hash was emptied gets a new working password")
  void emptiedAdminGetsNewPassword(final CapturedOutput output) throws Exception {
    final var admin = this.seededAdminAsLastAdmin();
    this.applyOperatorSql(RESET_ALL_ADMINS_SQL);
    assertThat(this.reload(admin.getId()).getHash()).isEmpty();

    this.startApplication();

    final var newPassword = loggedPasswordOf(admin, output);
    final var after = this.reload(admin.getId());
    assertThat(after.getHash()).isNotEmpty();
    assertThat(PasswordHasher.matches(newPassword, after.getHash())).isTrue();

    this.login(admin.getUsername(), newPassword, 200);
    this.login(admin.getUsername(), SEEDED_ADMIN_PASSWORD, 401);
  }

  @Test
  @DisplayName("an admin that was not emptied keeps its password")
  void untouchedAdminIsLeftAlone(final CapturedOutput output) throws Exception {
    final var seeded = this.seededAdminAsLastAdmin();
    final var other = this.createUser(uniqueUsername("lost"), UserRole.ADMIN);
    final var seededHash = seeded.getHash();
    this.applyOperatorSql(RESET_ONE_ADMIN_SQL, other.getUsername());

    this.startApplication();

    final var newPassword = loggedPasswordOf(other, output);
    this.login(other.getUsername(), newPassword, 200);

    assertThat(output.getAll()).doesNotContain("reset for user " + seeded.getUsername());
    assertThat(this.reload(seeded.getId()).getHash()).isEqualTo(seededHash);
    this.login(seeded.getUsername(), SEEDED_ADMIN_PASSWORD, 200);
  }

  @Test
  @DisplayName("every admin whose hash was emptied is reset, not only the first one found")
  void everyEmptiedAdminIsReset(final CapturedOutput output) throws Exception {
    final var first = this.seededAdminAsLastAdmin();
    final var second = this.createUser(uniqueUsername("lost"), UserRole.ADMIN);
    this.applyOperatorSql(RESET_ALL_ADMINS_SQL);

    this.startApplication();

    this.login(first.getUsername(), loggedPasswordOf(first, output), 200);
    this.login(second.getUsername(), loggedPasswordOf(second, output), 200);
  }
}
