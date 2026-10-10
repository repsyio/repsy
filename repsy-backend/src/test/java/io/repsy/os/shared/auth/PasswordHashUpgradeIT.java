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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.auth.PasswordHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-961: a user whose password hash was made with an older BCrypt work factor can log in, and the
 * hash is replaced by a current one on the way, through the panel login and through HTTP Basic
 * alike.
 *
 * <p>RPS-1615: an account of release v26.08.4 still has a salted SHA-256 hash and its salt. Its
 * owner logs in with the old password on every path that checks one (panel login, HTTP Basic, the
 * Docker token endpoint, npm login, Cargo login), and the hash becomes BCrypt with no salt while
 * the sessions stay valid.
 *
 * <p>The upgrade runs in its own transaction, and {@code UserLoginListener} is {@code @Async}, so
 * neither can see rows that are still uncommitted inside a test transaction. The class therefore
 * runs without one, commits the users it creates and deletes exactly those afterwards.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("password hash upgrade on login")
class PasswordHashUpgradeIT extends AbstractIT {

  private static final String BCRYPT_PREFIX = "{bcrypt}$2";

  /** Made once per class: a BCrypt hash costs about 100 ms, so it is not made per test. */
  private static final String NEW_PASSWORD_HASH = PasswordHasher.hash("NewPassword2@");

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
    assertThat(PasswordHasher.matches(VALID_PASSWORD, after.getHash(), null)).isTrue();
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
    assertThat(PasswordHasher.matches(VALID_PASSWORD, after.getHash(), null)).isTrue();

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

  private static final String SALT = "0123456789abcdef";

  /** A user as release v26.08.4 stored it: {@code sha256Hex(password + salt)} and the salt. */
  private User createLegacyUser(final String password) {
    final var user = this.commitUser(DigestUtils.sha256Hex(password + SALT));

    this.jdbcTemplate.update("update users set salt = ? where id = ?", SALT, user.getId());

    return this.reload(user.getId());
  }

  private void assertUpgraded(final User legacy, final String password) {
    final var after = this.reload(legacy.getId());

    assertThat(after.getHash()).startsWith(BCRYPT_PREFIX).isNotEqualTo(legacy.getHash());
    assertThat(after.getSalt()).as("BCrypt keeps its salt in the hash").isNull();
    assertThat(PasswordHasher.matches(password, after.getHash(), after.getSalt())).isTrue();
    assertThat(after.getTokenVersion())
        .as("the password did not change, so the sessions stay valid")
        .isEqualTo(legacy.getTokenVersion());
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private MockHttpServletResponse npmLogin(
      final String username, final String password, final int expectedStatus) throws Exception {
    final var response =
        this.protocol(
            put("/npm/-/user/org.couchdb.user:{name}", username)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));

    assertThat(response.getStatus()).isEqualTo(expectedStatus);

    return response;
  }

  @Test
  @DisplayName("panel login with a legacy SHA-256 hash succeeds and stores BCrypt without a salt")
  void panelLoginUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);

    this.assertUpgraded(user, VALID_PASSWORD);
    this.panelLogin(user.getUsername(), VALID_PASSWORD, 200);
    this.panelLogin(user.getUsername(), "Other1234!", 401);
  }

  @Test
  @DisplayName("HTTP Basic with a legacy SHA-256 hash succeeds and stores BCrypt without a salt")
  void basicAuthUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    this.basicRequest(user.getUsername(), VALID_PASSWORD, AUTHENTICATED);

    this.assertUpgraded(user, VALID_PASSWORD);
    this.basicRequest(user.getUsername(), VALID_PASSWORD, AUTHENTICATED);
    this.basicRequest(user.getUsername(), "Other1234!", 401);
  }

  @Test
  @DisplayName("Maven wire read with a legacy SHA-256 hash succeeds and stores BCrypt")
  void mavenWireUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);
    final var url = "/maven/com/example/lib/1.0/lib-1.0.pom";

    assertThat(this.protocol(get(url)).getStatus()).isEqualTo(401);
    assertThat(
            this.protocol(get(url).header(AUTHORIZATION, basicAuth(user.getUsername(), "Nope1!")))
                .getStatus())
        .isEqualTo(401);
    assertThat(
            this.protocol(
                    get(url).header(AUTHORIZATION, basicAuth(user.getUsername(), VALID_PASSWORD)))
                .getStatus())
        .isEqualTo(404);

    this.assertUpgraded(user, VALID_PASSWORD);
  }

  @Test
  @DisplayName("the Docker token endpoint with a legacy SHA-256 hash issues a token and upgrades")
  void dockerTokenUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);
    final var token =
        get("/v2/token")
            .param("scope", "repository:docker/image:pull")
            .header(AUTHORIZATION, basicAuth(user.getUsername(), VALID_PASSWORD));

    assertThat(this.protocol(token).getStatus()).isEqualTo(200);

    this.assertUpgraded(user, VALID_PASSWORD);
  }

  @Test
  @DisplayName("the Docker password grant with a legacy SHA-256 hash issues a token and upgrades")
  void dockerPasswordGrantUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);
    final var grant =
        post("/v2/token")
            .param("grant_type", "password")
            .param("username", user.getUsername())
            .param("password", VALID_PASSWORD)
            .param("scope", "repository:docker/image:pull");

    assertThat(this.protocol(grant).getStatus()).isEqualTo(200);

    this.assertUpgraded(user, VALID_PASSWORD);
  }

  @Test
  @DisplayName("npm login with a legacy SHA-256 hash succeeds and stores BCrypt without a salt")
  void npmLoginUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    this.npmLogin(user.getUsername(), "Other1234!", 401);
    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());

    this.npmLogin(user.getUsername(), VALID_PASSWORD, 201);

    this.assertUpgraded(user, VALID_PASSWORD);
    this.npmLogin(user.getUsername(), VALID_PASSWORD, 201);
  }

  @Test
  @DisplayName("Cargo login with a legacy SHA-256 hash succeeds and stores BCrypt without a salt")
  void cargoLoginUpgradesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);
    final var me = get("/cargo/me");

    assertThat(
            this.protocol(me.header(AUTHORIZATION, basicAuth(user.getUsername(), "Nope1!")))
                .getStatus())
        .isEqualTo(401);
    assertThat(
            this.protocol(
                    get("/cargo/me")
                        .header(AUTHORIZATION, basicAuth(user.getUsername(), VALID_PASSWORD)))
                .getStatus())
        .isEqualTo(200);

    this.assertUpgraded(user, VALID_PASSWORD);
  }

  @Test
  @DisplayName("a wrong password leaves a legacy hash and its salt as they are")
  void wrongPasswordLeavesLegacyHash() throws Exception {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    this.panelLogin(user.getUsername(), "Other1234!", 401);
    this.basicRequest(user.getUsername(), "Other1234!", 401);

    final var after = this.reload(user.getId());
    assertThat(after.getHash()).isEqualTo(user.getHash());
    assertThat(after.getSalt()).isEqualTo(SALT);
  }

  @Test
  @DisplayName("a legacy hash without its salt does not log in")
  void legacyHashWithoutSaltIsRejected() throws Exception {
    final var user = this.commitUser(DigestUtils.sha256Hex(VALID_PASSWORD + SALT));

    this.panelLogin(user.getUsername(), VALID_PASSWORD, 401);
    this.basicRequest(user.getUsername(), VALID_PASSWORD, 401);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());
  }

  @Test
  @DisplayName("a legacy password too long for BCrypt is refused by the panel and stays legacy")
  void overlongLegacyPasswordIsNotUpgraded() throws Exception {
    final var password = "Aa1!" + "x".repeat(80);
    final var user = this.createLegacyUser(password);

    this.panelLogin(user.getUsername(), password, 400);
    this.basicRequest(user.getUsername(), password, AUTHENTICATED);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(user.getHash());
  }

  @Test
  @DisplayName("a legacy upgrade that lost a race with a password change does not undo the change")
  void staleLegacyUpgradeDoesNotOverwriteANewPassword() {
    final var user = this.createLegacyUser(VALID_PASSWORD);
    final var staleView = this.userTxService.getUserById(user.getId());

    this.userTxService.updatePassword(user.getId(), NEW_PASSWORD_HASH);
    final var changed = this.reload(user.getId());

    this.userTxService.upgradePasswordHash(staleView, VALID_PASSWORD);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(changed.getHash());
    assertThat(changed.getSalt()).as("a new password carries no salt").isNull();
  }

  @Test
  @DisplayName("changing the password of a legacy account clears its salt")
  void updatePasswordClearsTheSalt() {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    this.userTxService.updatePassword(user.getId(), NEW_PASSWORD_HASH);

    assertThat(this.reload(user.getId()).getSalt()).isNull();
  }

  @Test
  @DisplayName("resetting the password of a legacy account clears its salt")
  void resetPasswordClearsTheSalt() {
    final var user = this.createLegacyUser(VALID_PASSWORD);

    final var newPassword = this.userTxService.resetUserPassword(user.getId());

    final var after = this.reload(user.getId());
    assertThat(after.getSalt()).isNull();
    assertThat(PasswordHasher.matches(newPassword, after.getHash(), after.getSalt())).isTrue();
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
    this.userTxService.updatePassword(user.getId(), NEW_PASSWORD_HASH);
    final var changed = this.reload(user.getId());

    this.userTxService.upgradePasswordHash(staleView, VALID_PASSWORD);

    assertThat(this.reload(user.getId()).getHash()).isEqualTo(changed.getHash());
    assertThat(PasswordHasher.matches("NewPassword2@", changed.getHash(), null)).isTrue();
  }

  @Test
  @DisplayName("an unknown username is rejected like a wrong password")
  void unknownUsernameIsRejected() throws Exception {
    this.panelLogin(uniqueUsername("ghost"), VALID_PASSWORD, 401);
    this.basicRequest(uniqueUsername("ghost"), VALID_PASSWORD, 401);
  }
}
