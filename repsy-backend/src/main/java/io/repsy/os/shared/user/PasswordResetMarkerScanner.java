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

import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resets the password of any user when an operator creates an empty file named after that user in
 * the password reset directory (RPS-1107, README "Forgot admin password?"). It is the way to
 * recover a lost password from inside the pod or container, without editing the database and
 * without a second JVM (which could not open a running H2 file anyway): {@code touch
 * /app/data/password-reset/admin}.
 *
 * <p>The directory is scanned once at startup (a marker created while the application was stopped)
 * and then every {@code poll-interval}. For every marker the scanner
 *
 * <ol>
 *   <li>removes the file <em>first</em>, and goes on only if this call removed it, so a marker that
 *       cannot be deleted is never applied twice and two instances sharing one volume cannot both
 *       reset;
 *   <li>generates a new password through {@link UserTxService#resetUserPassword}, the code behind
 *       the admin endpoint, which also revokes every session and refresh token of the user;
 *   <li>logs the new password once, at {@code WARN}, in the same words as {@link
 *       AdminUserInitializer}. Nothing else carries it.
 * </ol>
 *
 * <p>A marker that fails (typically one that cannot be deleted because the volume is read-only) is
 * logged at {@code ERROR} once, not on every poll: the scanner remembers the files that already
 * failed and logs a file again only after it has disappeared and come back.
 *
 * <p>The file content is never read, symlinks and directories are ignored (a symlink is never
 * followed), and a file whose name is not a valid username is removed with a warning. Nothing is
 * reachable over the network: it takes write access to the directory, the same trust level that can
 * already read the database credentials. Set {@code repsy.security.password-reset.enabled} to
 * {@code false} to switch it off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class PasswordResetMarkerScanner implements ApplicationRunner {

  /** The username rule of the API spec, which also keeps a marker name a plain file name. */
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9_\\-]{3,25}$");

  private static final int MAX_LOGGED_NAME_LENGTH = 60;

  private final @NonNull PasswordResetMarkerProperties properties;
  private final @NonNull UserRepository userRepository;
  private final @NonNull UserTxService userTxService;

  /** The markers that already failed and are still in the directory: logged once, not per poll. */
  private final Set<Path> failedMarkers = ConcurrentHashMap.newKeySet();

  @Override
  public void run(final @NonNull ApplicationArguments args) {
    if (!this.properties.enabled()) {
      return;
    }

    final var dir = this.properties.dir();
    try {
      Files.createDirectories(dir);
      log.info("Password reset markers are read from {}", dir);
    } catch (final IOException e) {
      log.warn("Could not create the password reset marker directory {}: {}", dir, e.toString());
    }

    this.scan(dir);
  }

  @Scheduled(
      initialDelayString = "${repsy.security.password-reset.poll-interval}",
      fixedDelayString = "${repsy.security.password-reset.poll-interval}")
  public void poll() {
    if (this.properties.enabled()) {
      this.scan(this.properties.dir());
    }
  }

  /** Applies every marker file in {@code dir}; a directory that does not exist has none. */
  public void scan(final @NonNull Path dir) {
    final var entries = this.list(dir);
    // A marker that is gone (removed by hand, or applied after all) may fail and be logged again.
    this.failedMarkers.retainAll(new HashSet<>(entries));
    for (final var entry : entries) {
      this.process(entry);
    }
  }

  private @NonNull List<Path> list(final @NonNull Path dir) {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.sorted().toList();
    } catch (final NoSuchFileException e) {
      return List.of();
    } catch (final IOException e) {
      log.warn("Could not read the password reset marker directory {}: {}", dir, e.toString());
      return List.of();
    }
  }

  private void process(final @NonNull Path entry) {
    try {
      final var attributes =
          Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        log.debug("Ignoring {} in the password reset marker directory: not a regular file", entry);
        return;
      }

      final var username = entry.getFileName().toString();
      if (!USERNAME.matcher(username).matches()) {
        Files.deleteIfExists(entry);
        log.warn(
            "Removed the password reset marker \"{}\": its name is not a valid username",
            printable(username));
        return;
      }

      // Unlink before the reset: only the call that removed the file resets the password.
      if (Files.deleteIfExists(entry)) {
        this.reset(username, entry);
      }
    } catch (final IOException | RuntimeException e) {
      this.logFailure(entry, e);
    }
  }

  private void logFailure(final @NonNull Path entry, final @NonNull Exception e) {
    if (this.failedMarkers.add(entry)) {
      log.error(
          "Could not apply the password reset marker {}, create it again to retry: {}",
          entry,
          e.toString());
    } else {
      log.debug("The password reset marker {} failed again: {}", entry, e.toString());
    }
  }

  private void reset(final @NonNull String username, final @NonNull Path marker) {
    final var user = this.userRepository.findByUsername(username);
    if (user.isEmpty()) {
      log.warn("Removed the password reset marker {}: there is no user {}", marker, username);
      return;
    }

    final var newPassword = this.userTxService.resetUserPassword(user.get().getId());

    log.warn(
        "Password of user {} has been reset by the marker file {}. New password: {}",
        username,
        marker,
        newPassword);
  }

  /** A file name is operator input: keep control characters out of the log. */
  private static @NonNull String printable(final @NonNull String name) {
    final var shortened =
        name.length() > MAX_LOGGED_NAME_LENGTH ? name.substring(0, MAX_LOGGED_NAME_LENGTH) : name;
    return shortened.replaceAll("[^\\x20-\\x7E]", "?");
  }
}
