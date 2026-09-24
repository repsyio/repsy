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
package io.repsy.os.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-976: a password that contains a colon works over HTTP Basic. Only the first colon of the
 * decoded credential separates the username from the password (RFC 7617), so everything after it,
 * further colons included, is the password.
 *
 * <p>The password rules of the panel allow a colon, which is why the panel paths are covered too: a
 * user who sets such a password through the panel must be able to use it over Basic.
 *
 * <p>Runs without a test transaction and deletes the users it commits, like {@code
 * PasswordHashUpgradeIT}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("HTTP Basic with a colon in the password")
class BasicAuthColonPasswordIT extends AbstractIntegrationTest {

  private static final String COLON_PASSWORD = "Pass:Word1:tail";

  /**
   * A route that takes Basic credentials and needs an admin: {@code MANAGE} on a repo that does not
   * exist. The auth interceptor authenticates the caller and checks the role as for a private repo
   * before it answers {@code repoNotFound}, so {@link #AUTHENTICATED} (404) means "the credentials
   * were accepted" and a 401 means they were not. No repo has to exist, which keeps the class free
   * of rows other than its users.
   */
  private static final String PROBE_URL = "/api/repos/no-such-repo/settings";

  private static final int AUTHENTICATED = 404;

  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCreatedUsers() {
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private String createAdminWithPassword(final String password) {
    final var username = uniqueUsername("colon");
    final var userInfo =
        this.userTxService.create(username, UserRole.ADMIN, PasswordHasher.hash(password));
    this.createdUserIds.add(userInfo.getId());

    return username;
  }

  private void basicRequest(final String username, final String password, final int expectedStatus)
      throws Exception {

    this.perform(get(PROBE_URL).header(AUTHORIZATION, basicAuth(username, password)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  @DisplayName("authenticates with the whole password, colons included")
  void authenticatesWithAColonPassword() throws Exception {
    final var username = this.createAdminWithPassword(COLON_PASSWORD);

    this.basicRequest(username, COLON_PASSWORD, AUTHENTICATED);
  }

  @Test
  @DisplayName("rejects the part of the password before its second colon")
  void rejectsTheTruncatedPassword() throws Exception {
    final var username = this.createAdminWithPassword(COLON_PASSWORD);

    this.basicRequest(username, "Pass", 401);
    this.basicRequest(username, "Pass:Word1", 401);
  }

  @Test
  @DisplayName("rejects a colon password that differs after the second colon")
  void rejectsAWrongTail() throws Exception {
    final var username = this.createAdminWithPassword(COLON_PASSWORD);

    this.basicRequest(username, "Pass:Word1:other", 401);
  }

  @Test
  @DisplayName("panel login accepts a password with a colon")
  void panelLoginAcceptsAColonPassword() throws Exception {
    final var username = this.createAdminWithPassword(COLON_PASSWORD);

    this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"password\":\"%s\"}"
                        .formatted(username, COLON_PASSWORD)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("a password with a colon set through the panel works over Basic")
  void passwordChangedInThePanelWorksOverBasic() throws Exception {
    final var username = this.createAdminWithPassword(VALID_PASSWORD);
    final var user = this.userRepository.findByUsername(username).orElseThrow();

    this.perform(
            put("/api/profile/password")
                .header(AUTHORIZATION, this.bearerTokenFor(user.getId(), username))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(COLON_PASSWORD)))
        .andExpect(status().isOk());

    this.basicRequest(username, COLON_PASSWORD, AUTHENTICATED);
    this.basicRequest(username, VALID_PASSWORD, 401);
    assertThat(this.userRepository.findById(user.getId()).orElseThrow().getHash())
        .isNotEqualTo(user.getHash());
  }
}
