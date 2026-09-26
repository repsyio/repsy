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
package io.repsy.scanner.trivy.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

/** Moves freshly downloaded Trivy databases from a staging cache directory into the live one. */
@Slf4j
final class TrivyDatabaseSwapper {

  private static final String METADATA_FILE = "metadata.json";

  // directory in the cache, and the database file that lives in it
  private static final List<String[]> DATABASES =
      List.of(new String[] {"db", "trivy.db"}, new String[] {"java-db", "trivy-java.db"});

  private TrivyDatabaseSwapper() {}

  /** An empty {@code staging} directory (what an earlier, interrupted refresh left is removed). */
  static void recreate(final @NonNull Path staging) throws IOException {
    deleteRecursively(staging);
    Files.createDirectories(staging);
  }

  /**
   * Replaces the databases of {@code live} by those of {@code staging}, the database file first and
   * its metadata after it, each by an atomic rename (a reader that has the old file open keeps it).
   * Nothing is touched unless both databases are complete, so an unfinished download changes
   * nothing. The caller holds {@link TrivyDatabaseAccess#enterRefresh()}.
   */
  static void swap(final @NonNull Path staging, final @NonNull Path live) throws IOException {
    for (final var database : DATABASES) {
      final var dir = staging.resolve(database[0]);

      if (!Files.isRegularFile(dir.resolve(database[1]))
          || !Files.isRegularFile(dir.resolve(METADATA_FILE))) {
        throw new IOException("The downloaded " + database[0] + " is incomplete");
      }
    }

    for (final var database : DATABASES) {
      final var target = live.resolve(database[0]);
      Files.createDirectories(target);

      for (final var file : List.of(database[1], METADATA_FILE)) {
        Files.move(
            staging.resolve(database[0]).resolve(file),
            target.resolve(file),
            StandardCopyOption.ATOMIC_MOVE);
      }
    }
  }

  static void deleteRecursivelyQuietly(final @NonNull Path directory) {
    try {
      deleteRecursively(directory);
    } catch (final IOException exception) {
      log.warn("Failed to delete {}", directory, exception);
    }
  }

  private static void deleteRecursively(final @NonNull Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }

    try (var paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (final IOException exception) {
                  throw new UncheckedIOException(exception);
                }
              });
    } catch (final UncheckedIOException exception) {
      throw exception.getCause();
    }
  }
}
