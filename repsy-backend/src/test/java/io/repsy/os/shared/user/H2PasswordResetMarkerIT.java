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

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The H2 counterpart of {@link PasswordResetMarkerScannerIT} (RPS-1107): the marker file recovery
 * on the embedded database, where a second process could not open the database file and the marker
 * is the way in. Like {@link H2AdminUserInitializerIT} it checks the stored hash with {@link
 * PasswordHasher} instead of logging in through MockMvc, so that all the H2 tests share one
 * application context, and it rolls back with the rest of {@link H2IntegrationTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("PasswordResetMarkerScanner on the embedded H2 database (RPS-1107)")
class H2PasswordResetMarkerIT extends H2IntegrationTest {

  private static final String ADMIN_USERNAME = "admin";

  @TempDir private Path markerDir;

  @Autowired private UserRepository userRepository;
  @Autowired private UserTxService userTxService;
  @PersistenceContext private EntityManager entityManager;

  private PasswordResetMarkerScanner scanner() {
    return new PasswordResetMarkerScanner(
        new PasswordResetMarkerProperties(true, this.markerDir, Duration.ofSeconds(5)),
        this.userRepository,
        this.userTxService);
  }

  private String storedHashOfAdmin() {
    this.entityManager.flush();
    this.entityManager.clear();
    return this.userRepository.findByUsername(ADMIN_USERNAME).orElseThrow().getHash();
  }

  @Test
  @DisplayName("a marker gives the admin a new password that its stored hash accepts")
  void resetsTheAdmin(final CapturedOutput output) throws IOException {
    final var oldHash = this.storedHashOfAdmin();
    final var marker = Files.createFile(this.markerDir.resolve(ADMIN_USERNAME));

    this.scanner().scan(this.markerDir);

    assertThat(marker).doesNotExist();
    final var newPassword = loggedMarkerPasswordOf(ADMIN_USERNAME, output);
    final var newHash = this.storedHashOfAdmin();
    assertThat(newHash).isNotEqualTo(oldHash);
    assertThat(PasswordHasher.matches(newPassword, newHash)).isTrue();
    assertThat(PasswordHasher.matches("H2TestAdmin1!", newHash)).isFalse();
  }

  @Test
  @DisplayName("startup applies a marker that was left while the application was stopped")
  void startupAppliesAStaleMarker(final CapturedOutput output) throws IOException {
    final var marker = Files.createFile(this.markerDir.resolve(ADMIN_USERNAME));

    this.scanner().run(new DefaultApplicationArguments());

    assertThat(marker).doesNotExist();
    final var newPassword = loggedMarkerPasswordOf(ADMIN_USERNAME, output);
    assertThat(PasswordHasher.matches(newPassword, this.storedHashOfAdmin())).isTrue();
  }

  @Test
  @DisplayName("a marker for an unknown user is removed and the admin keeps its password")
  void removesAMarkerOfAnUnknownUser(final CapturedOutput output) throws IOException {
    final var hash = this.storedHashOfAdmin();
    final var marker = Files.createFile(this.markerDir.resolve("nobody-here"));

    this.scanner().scan(this.markerDir);

    assertThat(marker).doesNotExist();
    assertThat(output.getAll()).contains("there is no user nobody-here");
    assertThat(this.storedHashOfAdmin()).isEqualTo(hash);
  }
}
