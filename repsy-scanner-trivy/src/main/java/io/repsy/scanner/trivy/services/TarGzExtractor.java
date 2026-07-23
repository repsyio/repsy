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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jspecify.annotations.NonNull;

@Slf4j
final class TarGzExtractor {

  private TarGzExtractor() {}

  static boolean isTarGz(final @NonNull String fileName) {
    final var lower = fileName.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tgz") || lower.endsWith(".tar.gz");
  }

  static @NonNull Path extract(final @NonNull Path archivePath) throws IOException {
    final var extractDir = Files.createTempDirectory("scan-extracted-");

    try (final var fileIn = Files.newInputStream(archivePath);
        final var gzipIn = new GzipCompressorInputStream(fileIn);
        final var tarIn = new TarArchiveInputStream(gzipIn)) {

      TarArchiveEntry entry;
      while ((entry = tarIn.getNextEntry()) != null) {
        extractEntry(extractDir, entry, tarIn);
      }
    }

    return extractDir;
  }

  static void deleteRecursivelyQuietly(final @NonNull Path dir) {
    try (final var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(TarGzExtractor::deleteQuietly);
    } catch (final IOException exception) {
      log.warn("Failed to walk directory for cleanup: {}", dir, exception);
    }
  }

  private static void extractEntry(
      final @NonNull Path extractDir,
      final @NonNull TarArchiveEntry entry,
      final @NonNull TarArchiveInputStream tarIn)
      throws IOException {

    final var target = extractDir.resolve(entry.getName()).normalize();

    if (!target.startsWith(extractDir)) {
      log.warn("Skipping archive entry with unsafe path: {}", entry.getName());
      return;
    }

    if (entry.isDirectory()) {
      Files.createDirectories(target);
      return;
    }

    if (entry.isSymbolicLink() || entry.isLink()) {
      log.debug("Skipping symlink/hardlink archive entry: {}", entry.getName());
      return;
    }

    Files.createDirectories(target.getParent());
    Files.copy(tarIn, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void deleteQuietly(final @NonNull Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException exception) {
      log.warn("Failed to delete {}", path, exception);
    }
  }
}
