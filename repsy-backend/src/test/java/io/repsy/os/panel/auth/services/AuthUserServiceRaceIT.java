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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1152: a user deleted between the password/refresh-token check and the moment the new refresh
 * token is written used to surface as a raw foreign-key violation (23503), mapped by {@code
 * ErrorHandler} to a 500, instead of the clean 401 every other "user is gone" path answers.
 *
 * <p>{@code AuthUserService.login} and {@code refreshToken} now take a {@code PESSIMISTIC_READ}
 * lock on the user row ({@code UserTxService.lockUserExists}, RPS-1152) right before {@code
 * LoginInfoFactory.create} writes the new token, the same idiom {@code
 * UserRepository.lockIdsByRole} established for the last-admin guards (RPS-1101).
 *
 * <p>Each test spies on the read that resolves the user ({@code UserTxService.getUserByUsername}
 * for login, {@code getAuthenticatedUserById} for refresh) and, once that real read has succeeded
 * (so the request is genuinely past the password/token check, holding the user in memory), deletes
 * the row before the request continues. That models a deletion committed by another request in the
 * instant between the check and the lock: from here on the row is simply gone, whether this test
 * deleted it a statement earlier in the same transaction or another transaction deleted and
 * committed it first. Either way {@code lockUserExists} has to see it as gone.
 *
 * <p>Runs without the inherited test transaction ({@code Propagation.NOT_SUPPORTED}): {@code
 * AuthUserService.login}/{@code refreshToken} must be the outermost transaction for its commit (or,
 * pre-fix, its failed commit) to happen inside the request, the way it does in production. The
 * created users are cleaned up afterwards; a rejected request rolls its own transaction back,
 * including the deletion the spy performed, so the row is still there to delete.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("login/refresh vs. a user deletion racing the token write (RPS-1152)")
class AuthUserServiceRaceIT extends AbstractIntegrationTest {

  @MockitoSpyBean private UserTxService userTxServiceSpy;

  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    Mockito.reset(this.userTxServiceSpy);
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /**
   * Creates and commits a user directly through {@code UserTxService}, without the {@code
   * createUser} helper: that helper flushes outside of any transaction under {@code
   * Propagation.NOT_SUPPORTED}, since {@code UserTxService.create} already commits its own.
   */
  private UserInfo newUser(final String prefix) {
    final var user =
        this.userTxService.create(uniqueUsername(prefix), UserRole.USER, VALID_PASSWORD_HASH);
    this.createdUserIds.add(user.getId());
    return user;
  }

  @Test
  @DisplayName("login answers 401 invalidCredentials, not 500, once the user is gone")
  void loginRaceWithADeletion() throws Exception {
    final var user = this.newUser("loginrace");

    Mockito.doAnswer(
            invocation -> {
              final var result = invocation.callRealMethod();
              this.jdbcTemplate.update("delete from users where id = ?", user.getId());
              return result;
            })
        .when(this.userTxServiceSpy)
        .getUserByUsername(user.getUsername());

    expectError(
        this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"password\":\"%s\"}"
                        .formatted(user.getUsername(), VALID_PASSWORD))),
        HttpStatus.UNAUTHORIZED,
        "invalidCredentials",
        "invalidCredentials",
        "Username or password is incorrect.");
  }

  @Test
  @DisplayName("refresh answers 401 refreshTokenExpired, not 500, once the user is gone")
  void refreshRaceWithADeletion() throws Exception {
    final var user = this.newUser("refreshrace");
    final var refreshToken =
        this.jwtUtils.createRefreshToken(
            user.getId(),
            user.getUsername(),
            AuthUtils.TIMEOUT_REFRESH_TOKEN,
            Instant.now(),
            user.getTokenVersion());
    this.registerRefreshToken(refreshToken);

    Mockito.doAnswer(
            invocation -> {
              final var result = invocation.callRealMethod();
              this.jdbcTemplate.update("delete from users where id = ?", user.getId());
              return result;
            })
        .when(this.userTxServiceSpy)
        .getAuthenticatedUserById(user.getId());

    expectError(
        this.perform(
            post("/api/auth/tokens/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken))),
        HttpStatus.UNAUTHORIZED,
        "refreshTokenExpired",
        "refreshTokenExpired",
        "Refresh token expired.");
  }
}
