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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * RPS-1107: the password reset marker files. The repository and the reset service are mocks, the
 * directory is a real temporary one, so what is checked is what the scanner does to the files and
 * when it calls the reset. {@link PasswordResetMarkerScannerIT} checks the reset itself.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("PasswordResetMarkerScanner")
class PasswordResetMarkerScannerTest {

  private static final String NEW_PASSWORD = "Xk3nTz9QwLpA";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final UserTxService userTxService = mock(UserTxService.class);

  @TempDir private Path dir;

  private PasswordResetMarkerScanner scanner;

  @BeforeEach
  void setUp() {
    this.scanner = this.scannerWith(true, this.dir);
  }

  private PasswordResetMarkerScanner scannerWith(final boolean enabled, final Path markerDir) {
    return new PasswordResetMarkerScanner(
        new PasswordResetMarkerProperties(enabled, markerDir, Duration.ofSeconds(5)),
        this.userRepository,
        this.userTxService);
  }

  private UUID existingUser(final String username) {
    final var user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    when(this.userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    return user.getId();
  }

  private Path marker(final String name) throws IOException {
    return Files.createFile(this.dir.resolve(name));
  }

  @Test
  @DisplayName("a marker resets the user it is named after and logs the new password once")
  void resetsTheUserAndLogsThePassword(final CapturedOutput output) throws IOException {
    final var id = this.existingUser("alice");
    when(this.userTxService.resetUserPassword(id)).thenReturn(NEW_PASSWORD);
    final var marker = this.marker("alice");

    this.scanner.scan(this.dir);

    verify(this.userTxService).resetUserPassword(id);
    assertThat(marker).doesNotExist();
    assertThat(output.getAll())
        .contains("Password of user alice has been reset by the marker file " + marker)
        .contains("New password: " + NEW_PASSWORD);
    assertThat(output.getAll().split("New password", -1)).as("log lines").hasSize(2);
  }

  @Test
  @DisplayName("the marker is removed before the password is reset")
  void removesTheMarkerBeforeTheReset() throws IOException {
    final var id = this.existingUser("alice");
    final var marker = this.marker("alice");
    when(this.userTxService.resetUserPassword(id))
        .thenAnswer(
            invocation -> {
              assertThat(marker).as("marker while the password is reset").doesNotExist();
              return NEW_PASSWORD;
            });

    this.scanner.scan(this.dir);

    verify(this.userTxService).resetUserPassword(id);
  }

  @Test
  @DisplayName("a marker is applied once, however often the directory is scanned")
  void appliesAMarkerOnce() throws IOException {
    final var id = this.existingUser("alice");
    when(this.userTxService.resetUserPassword(id)).thenReturn(NEW_PASSWORD);
    this.marker("alice");

    this.scanner.scan(this.dir);
    this.scanner.scan(this.dir);
    this.scanner.poll();

    verify(this.userTxService, times(1)).resetUserPassword(id);
  }

  @Test
  @DisplayName("a marker that cannot be removed is not applied")
  void doesNotApplyAMarkerItCannotRemove(final CapturedOutput output) throws IOException {
    this.existingUser("alice");
    final var marker = this.marker("alice");
    assumeFalse(!this.dir.toFile().setWritable(false), "cannot make the directory read-only");
    try {
      assumeFalse(Files.isWritable(this.dir), "running as a user that ignores permissions");

      this.scanner.scan(this.dir);

      verifyNoInteractions(this.userTxService);
      assertThat(marker).exists();
      assertThat(output.getAll()).contains("Could not apply the password reset marker");
    } finally {
      assertThat(this.dir.toFile().setWritable(true)).isTrue();
    }
  }

  @Test
  @DisplayName("a marker for a user that does not exist is removed and resets nothing")
  void removesAMarkerOfAnUnknownUser(final CapturedOutput output) throws IOException {
    when(this.userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
    final var marker = this.marker("ghost");

    this.scanner.scan(this.dir);

    assertThat(marker).doesNotExist();
    verifyNoInteractions(this.userTxService);
    assertThat(output.getAll()).contains("there is no user ghost").doesNotContain("New password");
  }

  @Test
  @DisplayName("a file whose name is not a valid username is removed and looks up nobody")
  void removesAnInvalidName(final CapturedOutput output) throws IOException {
    final var names =
        new String[] {"ab", "Alice", "a".repeat(26), "has space", "dot.name", "a\nb-forged"};
    for (final var name : names) {
      this.marker(name);
    }

    this.scanner.scan(this.dir);

    try (var remaining = Files.list(this.dir)) {
      assertThat(remaining).isEmpty();
    }
    verifyNoInteractions(this.userRepository, this.userTxService);
    assertThat(output.getAll()).contains("not a valid username").contains("\"a?b-forged\"");
  }

  @Test
  @DisplayName("the shortest and the longest valid username are accepted")
  void acceptsTheBoundaryLengths() throws IOException {
    final var shortName = "a_-";
    final var longName = "z".repeat(25);
    final var shortId = this.existingUser(shortName);
    final var longId = this.existingUser(longName);
    when(this.userTxService.resetUserPassword(any())).thenReturn(NEW_PASSWORD);
    this.marker(shortName);
    this.marker(longName);

    this.scanner.scan(this.dir);

    verify(this.userTxService).resetUserPassword(shortId);
    verify(this.userTxService).resetUserPassword(longId);
  }

  @Test
  @DisplayName("a symlink is never followed and never applied")
  void ignoresASymlink() throws IOException {
    this.existingUser("alice");
    final var target = Files.createFile(this.dir.resolveSibling("target-" + UUID.randomUUID()));
    final var link = this.dir.resolve("alice");
    try {
      Files.createSymbolicLink(link, target);
    } catch (final UnsupportedOperationException | IOException e) {
      assumeFalse(true, "symbolic links are not available: " + e);
    }

    try {
      this.scanner.scan(this.dir);

      verifyNoInteractions(this.userTxService);
      assertThat(Files.isSymbolicLink(link)).isTrue();
      assertThat(target).exists();
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  @DisplayName("a directory named after a user is ignored")
  void ignoresADirectory() throws IOException {
    this.existingUser("alice");
    final var directory = Files.createDirectory(this.dir.resolve("alice"));

    this.scanner.scan(this.dir);

    verifyNoInteractions(this.userTxService);
    assertThat(directory).isDirectory();
  }

  @Test
  @DisplayName("a marker that fails does not stop the others")
  void carriesOnAfterAFailure(final CapturedOutput output) throws IOException {
    final var aliceId = this.existingUser("alice");
    final var bobId = this.existingUser("bobby");
    when(this.userTxService.resetUserPassword(aliceId))
        .thenThrow(new IllegalStateException("boom"));
    when(this.userTxService.resetUserPassword(bobId)).thenReturn(NEW_PASSWORD);
    final var aliceMarker = this.marker("alice");
    final var bobMarker = this.marker("bobby");

    this.scanner.scan(this.dir);

    verify(this.userTxService).resetUserPassword(bobId);
    assertThat(aliceMarker).doesNotExist();
    assertThat(bobMarker).doesNotExist();
    assertThat(output.getAll())
        .contains("Could not apply the password reset marker")
        .contains("New password: " + NEW_PASSWORD);
  }

  @Test
  @DisplayName("a directory that does not exist has no markers and is not an error")
  void toleratesAMissingDirectory() {
    assertThatCode(() -> this.scanner.scan(this.dir.resolve("missing"))).doesNotThrowAnyException();

    verifyNoInteractions(this.userRepository, this.userTxService);
  }

  @Test
  @DisplayName("a disabled scanner leaves every marker alone, at startup and when polling")
  void doesNothingWhenDisabled() throws IOException {
    final var disabled = this.scannerWith(false, this.dir);
    final var marker = this.marker("alice");

    disabled.run(new DefaultApplicationArguments());
    disabled.poll();

    assertThat(marker).exists();
    verifyNoInteractions(this.userRepository, this.userTxService);
  }

  @Test
  @DisplayName("startup creates the directory and applies a marker left while stopped")
  void startupAppliesAStaleMarker() throws IOException {
    final var markerDir = this.dir.resolve("password-reset");
    final var id = this.existingUser("alice");
    when(this.userTxService.resetUserPassword(id)).thenReturn(NEW_PASSWORD);
    Files.createDirectories(markerDir);
    final var stale = Files.createFile(markerDir.resolve("alice"));
    final var startup = this.scannerWith(true, markerDir);

    startup.run(new DefaultApplicationArguments());

    verify(this.userTxService).resetUserPassword(id);
    assertThat(stale).doesNotExist();
    assertThat(markerDir).isDirectory();
  }

  @Test
  @DisplayName("startup creates a missing directory for the operator")
  void startupCreatesTheDirectory() {
    final var markerDir = this.dir.resolve("nested").resolve("password-reset");

    this.scannerWith(true, markerDir).run(new DefaultApplicationArguments());

    assertThat(markerDir).isDirectory();
    verify(this.userTxService, never()).resetUserPassword(any());
  }

  @Test
  @DisplayName("startup survives a directory it cannot create")
  void startupSurvivesAnUncreatableDirectory(final CapturedOutput output) throws IOException {
    final var blocker = Files.createFile(this.dir.resolve("blocker"));

    assertThatCode(
            () ->
                this.scannerWith(true, blocker.resolve("password-reset"))
                    .run(new DefaultApplicationArguments()))
        .doesNotThrowAnyException();

    assertThat(output.getAll()).contains("Could not create the password reset marker directory");
  }

  @Test
  @DisplayName("the properties reject an enabled marker without a usable directory or interval")
  void rejectsUnusableProperties() {
    assertThatThrownBy(() -> new PasswordResetMarkerProperties(true, null, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PasswordResetMarkerProperties(true, this.dir, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PasswordResetMarkerProperties(true, this.dir, Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> new PasswordResetMarkerProperties(true, this.dir, Duration.ofSeconds(1)))
        .doesNotThrowAnyException();
    assertThatCode(() -> new PasswordResetMarkerProperties(false, null, null))
        .doesNotThrowAnyException();
  }
}
