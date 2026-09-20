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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1025: HTTP Basic requests remember a successful password check, and what the check depended
 * on (the password, the user, the role) is still read on every request. Runs against the real
 * wiring: the properties binding, the cache bean and the database.
 *
 * <p>Runs without a test transaction and deletes the users it commits, like {@code
 * BasicAuthColonPasswordIT}. The cache bean is shared with the other classes, so the tests compare
 * its hit count with a baseline instead of asserting an absolute value.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("HTTP Basic remembers a successful password check")
class BasicAuthCacheIT extends AbstractIntegrationTest {

  /** Needs MANAGE, so the users below are admins; the Basic check itself is what is under test. */
  private static final String COUNT_URL = "/api/repos/MAVEN/count";

  private static final String NEW_PASSWORD = "NewPassword1!";

  private final List<UUID> createdUserIds = new ArrayList<>();

  @Autowired private VerifiedPasswordCache cache;

  @AfterEach
  void deleteCreatedUsers() {
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private String createAdmin() {
    final var username = uniqueUsername("cache");
    final var userInfo =
        this.userTxService.create(username, UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return username;
  }

  private void basicRequest(final String username, final String password, final int expectedStatus)
      throws Exception {

    this.perform(get(COUNT_URL).header(AUTHORIZATION, basicAuth(username, password)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  @DisplayName("the second request with the same credentials is answered from the cache")
  void secondRequestIsRemembered() throws Exception {
    final var username = this.createAdmin();
    final var hitsBefore = this.cache.hitCount();

    this.basicRequest(username, VALID_PASSWORD, 200);
    assertThat(this.cache.hitCount()).isEqualTo(hitsBefore);

    this.basicRequest(username, VALID_PASSWORD, 200);
    this.basicRequest(username, VALID_PASSWORD, 200);
    assertThat(this.cache.hitCount()).isEqualTo(hitsBefore + 2);
  }

  @Test
  @DisplayName("a wrong password is rejected after the right one was remembered")
  void wrongPasswordIsStillRejected() throws Exception {
    final var username = this.createAdmin();

    this.basicRequest(username, VALID_PASSWORD, 200);
    this.basicRequest(username, "wrong", 401);
    this.basicRequest(username, VALID_PASSWORD, 200);
  }

  @Test
  @DisplayName("the old password is rejected at once after the password was changed")
  void changedPasswordRejectsTheOldOne() throws Exception {
    final var username = this.createAdmin();
    final var user = this.userRepository.findByUsername(username).orElseThrow();

    this.basicRequest(username, VALID_PASSWORD, 200);
    this.basicRequest(username, VALID_PASSWORD, 200);

    this.perform(
            put("/api/profile/password")
                .header(AUTHORIZATION, this.bearerTokenFor(user.getId(), username))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(NEW_PASSWORD)))
        .andExpect(status().isOk());

    this.basicRequest(username, VALID_PASSWORD, 401);
    this.basicRequest(username, NEW_PASSWORD, 200);
  }

  @Test
  @DisplayName("a deleted user is rejected at once")
  void deletedUserIsRejected() throws Exception {
    final var username = this.createAdmin();
    final var user = this.userRepository.findByUsername(username).orElseThrow();

    this.basicRequest(username, VALID_PASSWORD, 200);
    this.basicRequest(username, VALID_PASSWORD, 200);

    this.userTxService.deleteUserById(user.getId());

    this.basicRequest(username, VALID_PASSWORD, 401);
  }

  @Test
  @DisplayName("a role change applies to a remembered check")
  void demotedUserLosesManageAtOnce() throws Exception {
    final var username = this.createAdmin();
    final var user = this.userRepository.findByUsername(username).orElseThrow();

    this.basicRequest(username, VALID_PASSWORD, 200);
    this.basicRequest(username, VALID_PASSWORD, 200);

    user.setRole(UserRole.USER);
    this.userRepository.save(user);

    this.basicRequest(username, VALID_PASSWORD, 401);
  }
}
