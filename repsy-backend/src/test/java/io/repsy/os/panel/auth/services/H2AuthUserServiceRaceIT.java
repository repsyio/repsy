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
package io.repsy.os.panel.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.H2IntegrationTest;
import io.repsy.os.generated.model.LoginForm;
import io.repsy.os.server.shared.auth.AuthFailureThrottle;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The H2 counterpart of {@link AuthUserServiceRaceIT} (RPS-1152): {@code UserRepository.lockUserId}
 * takes a {@code PESSIMISTIC_READ} lock, and H2 has to accept that lock mode the same way it
 * already accepts the {@code PESSIMISTIC_WRITE} lock of {@code lockIdsByRole} ({@link
 * io.repsy.os.shared.user.services.H2LastAdminConcurrencyIT}, RPS-1101). A dialect that rejected
 * the lock mode would fail every login, not just a raced one, so {@link #logsInNormally()}
 * exercises the "row present" branch and {@link #loginRaceWithADeletion()} exercises the "row gone"
 * branch of the same query.
 *
 * <p>No HTTP here: like {@code H2LastAdminConcurrencyIT}, this calls {@link AuthUserService}
 * directly so every test shares one application context. It runs inside the inherited rollback
 * transaction: a rejected login rolls its own transaction back before the surrounding test
 * transaction ever commits, so there is nothing to clean up.
 */
@DisplayName("login vs. a user deletion racing the token write, under H2 (RPS-1152)")
class H2AuthUserServiceRaceIT extends H2IntegrationTest {

  private static final String PASSWORD = "Password1!";

  @Autowired private AuthUserService authUserService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthFailureThrottle authFailureThrottle;
  @MockitoSpyBean private UserTxService userTxServiceSpy;

  @BeforeEach
  void resetThrottle() {
    this.authFailureThrottle.reset();
  }

  @AfterEach
  void resetSpy() {
    Mockito.reset(this.userTxServiceSpy);
  }

  private String uniqueUsername(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private LoginForm loginForm(final String username) {
    return new LoginForm().username(username).password(PASSWORD);
  }

  @Test
  @DisplayName("logs in normally: the lock query accepts a user row that is still there")
  void logsInNormally() {
    final var username = this.uniqueUsername("h2login");
    this.userTxServiceSpy.create(username, UserRole.USER, PasswordHasher.hash(PASSWORD));

    final var loginInfo = this.authUserService.login(this.loginForm(username));

    assertThat(loginInfo.getUsername()).isEqualTo(username);
    assertThat(loginInfo.getToken()).isNotBlank();
    assertThat(loginInfo.getRefreshToken()).isNotBlank();
  }

  @Test
  @DisplayName("answers invalidCredentials, not a lock-mode error, once the user is gone")
  void loginRaceWithADeletion() {
    final var username = this.uniqueUsername("h2race");
    final var user =
        this.userTxServiceSpy.create(username, UserRole.USER, PasswordHasher.hash(PASSWORD));

    Mockito.doAnswer(
            invocation -> {
              final var result = invocation.callRealMethod();
              this.jdbcTemplate.update(
                  "delete from \"public\".\"users\" where \"id\" = ?", user.getId());
              return result;
            })
        .when(this.userTxServiceSpy)
        .getUserByUsername(username);

    assertThatThrownBy(() -> this.authUserService.login(this.loginForm(username)))
        .isInstanceOf(UnAuthorizedException.class)
        .hasMessage("invalidCredentials");
  }
}
