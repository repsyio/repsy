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
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-961: a user whose password hash was made with an older BCrypt work factor can log in, and the
 * hash is replaced by a current one on the way, through the panel login and through HTTP Basic
 * alike. Since RPS-1033 a hash without an algorithm id, such as the salted SHA-256 of RPS-961 and
 * before, no longer verifies at all.
 *
 * <p>The upgrade runs in its own transaction, and {@code UserLoginListener} is {@code @Async}, so
 * neither can see rows that are still uncommitted inside a test transaction. The class therefore
 * runs without one, commits the users it creates and deletes exactly those afterwards.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("password hash upgrade on login")
class PasswordHashUpgradeIT extends AbstractIntegrationTest {

  private static final String BCRYPT_PREFIX = "{bcrypt}$2";

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

  /** A BCrypt hash at the lowest work factor, which the current one has left behind. */
  private User createWeakUser(final String password) {
    return this.commitUser("{bcrypt}" + new BCryptPasswordEncoder(4).encode(password));
  }

  private User commitUser(final String hash) {
    final var userInfo = this.userTxService.create(uniqueUsername("legacy"), UserRole.ADMIN, hash);
    this.createdUserIds.add(userInfo.getId());

    return this.reload(userInfo.getId());
  }

  private User reload(final UUID id) {
    return this.userRepository.findById(id).orElseThrow();
  }

  private void panelLogin(final String username, final String password, final int expectedStatus)
      throws Exception {

    this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andExpect(status().is(expectedStatus));
  }

  private void basicRequest(final String username, final String password, final int expectedStatus)
      throws Exception {

    this.perform(get(PROBE_URL).header(AUTHORIZATION, basicAuth(username, password)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  @DisplayName("panel login with an outdated hash succeeds and stores a current hash")
  void panelLoginUpgradesOutdatedHash() throws Exception {
    final var user = this.createWeakUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);

    final var after = this.reload(user.getId());
    assertThat(after.getHash()).startsWith(BCRYPT_PREFIX).isNotEqualTo(user.getHash());
    assertThat(PasswordHasher.matches(VALID_PASSWORD, after.getHash())).isTrue();
    assertThat(after.getTokenVersion())
        .as("the password did not change, so the refresh tokens stay valid")
        .isEqualTo(user.getTokenVersion());
  }

  @Test
  @DisplayName("the upgraded user logs in again with the same password")
  void upgradedUserCanLogInAgain() throws Exception {
    final var user = this.createWeakUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);
    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);
    this.panelLogin(user.getUsername(), "Other1234!", 401);
  }

  @Test
  @DisplayName("the async lastLoginAt update after a login keeps the upgraded hash")
  void lastLoginUpdateKeepsTheUpgradedHash() throws Exception {
    final var user = this.createWeakUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(this.reload(user.getId()).getLastLoginAt()).isNotNull());

    assertThat(this.reload(user.getId()).getHash()).startsWith(BCRYPT_PREFIX);
  }

  @Test
  @DisplayName("HTTP Basic with an outdated hash succeeds and stores a current hash")
  void basicAuthUpgradesOutdatedHash() throws Exception {
    final var user = this.createWeakUser(VALID_PASSWORD);

    this.basicRequest(user.getUsername(), VALID_PASSWORD, AUTHENTICATED);

    final var after = this.reload(user.getId());
    assertThat(after.getHash()).startsWith(BCRYPT_PREFIX).isNotEqualTo(user.getHash());
    assertThat(PasswordHasher.matches(VALID_PASSWORD, after.getHash())).isTrue();

    this.basicRequest(user.getUsername(), VALID_PASSWORD, AUTHENTICATED);
  }

  @Test
  @DisplayName("a wrong password leaves an outdated hash as it is")
  void wrongPasswordDoesNotUpgrade() throws Exception {
    final var user = this.createWeakUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), "Other1234!", 401);
    this.basicRequest(user.getUsername(), "Other1234!", 401);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());
  }

  @Test
  @DisplayName("a current hash is not rewritten by a login")
  void bcryptHashIsLeftAlone() throws Exception {
    final var user = this.commitUser(VALID_PASSWORD_HASH);

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);
    this.basicRequest(user.getUsername(), VALID_PASSWORD, AUTHENTICATED);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());
  }

  @Test
  @DisplayName("a salted SHA-256 hash no longer logs in, whatever the password")
  void sha256HashIsRejected() throws Exception {
    final var salt = "0123456789abcdef";
    final var user = this.commitUser(DigestUtils.sha256Hex(VALID_PASSWORD + salt));

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 401);
    this.basicRequest(user.getUsername(), VALID_PASSWORD, 401);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());
  }

  @Test
  @DisplayName("an account with the empty hash of a password reset does not log in")
  void emptyHashIsRejected() throws Exception {
    final var user = this.commitUser("");

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 401);
    this.basicRequest(user.getUsername(), VALID_PASSWORD, 401);
  }

  @Test
  @DisplayName("an upgrade that lost a race with a password change does not undo the change")
  void staleUpgradeDoesNotOverwriteANewPassword() {
    final var user = this.createWeakUser(VALID_PASSWORD);
    final var staleView = this.userTxService.getUserById(user.getId());

    // The owner changes the password after the login read the row but before it wrote the upgrade.
    this.userTxService.updatePassword(user.getId(), PasswordHasher.hash("NewPassword2@"));
    final var changed = this.reload(user.getId());

    this.userTxService.upgradePasswordHash(staleView, VALID_PASSWORD);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(changed.getHash());
    assertThat(PasswordHasher.matches("NewPassword2@", changed.getHash())).isTrue();
  }

  @Test
  @DisplayName("an unknown username is rejected like a wrong password")
  void unknownUsernameIsRejected() throws Exception {
    this.panelLogin(uniqueUsername("ghost"), VALID_PASSWORD, 401);
    this.basicRequest(uniqueUsername("ghost"), VALID_PASSWORD, 401);
  }
}
