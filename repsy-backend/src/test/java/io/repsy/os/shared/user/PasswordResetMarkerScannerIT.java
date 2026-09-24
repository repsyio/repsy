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

import static io.repsy.os.shared.user.AdminPasswordResetChecks.loggedMarkerPasswordOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;

/**
 * RPS-1107: the password reset marker file of the README's "Forgot admin password?" recovery, on
 * PostgreSQL. {@link H2PasswordResetMarkerIT} checks it on the embedded H2 database.
 *
 * <p>The scanner under test is built on a {@code @TempDir} and called on the test thread, so it
 * joins the test transaction (the reset sees the users the test created and the rollback undoes it)
 * and the scheduled poll of the application's own scanner, which watches another directory, cannot
 * race the test.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("PasswordResetMarkerScanner")
class PasswordResetMarkerScannerIT extends AbstractIntegrationTest {

  @TempDir private Path markerDir;

  @Autowired private PasswordResetMarkerProperties configured;
  @Autowired private PasswordResetMarkerScanner applicationScanner;
  @Autowired private AdminUserInitializer adminUserInitializer;

  private PasswordResetMarkerScanner scanner() {
    return new PasswordResetMarkerScanner(
        new PasswordResetMarkerProperties(true, this.markerDir, Duration.ofSeconds(5)),
        this.userRepository,
        this.userTxService);
  }

  private Path marker(final String username) throws IOException {
    return Files.createFile(this.markerDir.resolve(username));
  }

  private User reload(final UUID id) {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userRepository.findById(id).orElseThrow();
  }

  private String login(final String username, final String password, final int expectedStatus)
      throws Exception {

    return this.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andExpect(status().is(expectedStatus))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("the application scans a directory of its own, after the empty-hash recovery")
  void isConfigured() {
    assertThat(this.configured.enabled()).isTrue();
    assertThat(this.configured.dir()).isEqualTo(STORAGE_ROOT.resolve("password-reset"));
    assertThat(this.configured.pollInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(
            AnnotationAwareOrderComparator.INSTANCE.compare(
                this.adminUserInitializer, this.applicationScanner))
        .isNegative();
  }

  @Test
  @DisplayName("a marker gives a user a new working password, revokes its sessions and is removed")
  void resetsAUser(final CapturedOutput output) throws Exception {
    final var user = this.createUser(uniqueUsername("lost"), UserRole.USER);
    final var refreshToken =
        JsonPath.<String>read(
            this.login(user.getUsername(), VALID_PASSWORD, 200), "$.data.refreshToken");
    final var versionBefore = user.getTokenVersion();
    final var marker = this.marker(user.getUsername());

    this.scanner().scan(this.markerDir);

    assertThat(marker).doesNotExist();
    final var newPassword = loggedMarkerPasswordOf(user.getUsername(), output);
    final var after = this.reload(user.getId());
    assertThat(PasswordHasher.matches(newPassword, after.getHash())).isTrue();
    assertThat(after.getTokenVersion()).isEqualTo(versionBefore + 1);
    this.login(user.getUsername(), newPassword, 200);
    this.login(user.getUsername(), VALID_PASSWORD, 401);

    this.perform(
            post("/api/auth/tokens/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a marker resets the admin, and the old password stops working")
  void resetsTheAdmin(final CapturedOutput output) throws Exception {
    final var admin = this.seededAdmin();
    this.marker(admin.getUsername());

    this.scanner().scan(this.markerDir);

    final var newPassword = loggedMarkerPasswordOf(admin.getUsername(), output);
    this.login(admin.getUsername(), newPassword, 200);
    this.login(admin.getUsername(), SEEDED_ADMIN_PASSWORD, 401);
  }

  @Test
  @DisplayName("only the user the marker names is reset")
  void leavesOtherUsersAlone(final CapturedOutput output) throws Exception {
    final var target = this.createUser(uniqueUsername("target"), UserRole.USER);
    final var other = this.createUser(uniqueUsername("other"), UserRole.USER);
    this.marker(target.getUsername());

    this.scanner().scan(this.markerDir);

    assertThat(output.getAll()).doesNotContain("Password of user " + other.getUsername());
    this.login(other.getUsername(), VALID_PASSWORD, 200);
    this.login(target.getUsername(), VALID_PASSWORD, 401);
  }

  @Test
  @DisplayName("a marker for an unknown user is removed with a warning and changes no row")
  void removesAMarkerOfAnUnknownUser(final CapturedOutput output) throws IOException {
    final var users = this.userRepository.count();
    final var marker = this.marker(uniqueUsername("ghost"));

    this.scanner().scan(this.markerDir);

    assertThat(marker).doesNotExist();
    assertThat(output.getAll()).contains("there is no user ").doesNotContain("New password");
    assertThat(this.userRepository.count()).isEqualTo(users);
  }

  @Test
  @DisplayName("a file that is not a valid username is removed and resets nobody")
  void removesAnInvalidName(final CapturedOutput output) throws IOException {
    final var admin = this.seededAdmin();
    final var hash = admin.getHash();
    final var marker = this.marker("Admin");

    this.scanner().scan(this.markerDir);

    assertThat(marker).doesNotExist();
    assertThat(output.getAll()).contains("not a valid username").doesNotContain("New password");
    assertThat(this.reload(admin.getId()).getHash()).isEqualTo(hash);
  }

  @Test
  @DisplayName("startup applies a marker that was left while the application was stopped")
  void startupAppliesAStaleMarker(final CapturedOutput output) throws Exception {
    final var user = this.createUser(uniqueUsername("stale"), UserRole.USER);
    final var marker = this.marker(user.getUsername());

    this.scanner().run(new DefaultApplicationArguments());

    assertThat(marker).doesNotExist();
    this.login(user.getUsername(), loggedMarkerPasswordOf(user.getUsername(), output), 200);
  }
}
