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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

@Slf4j
final class WheelExtractor {

  private WheelExtractor() {}

  static boolean isWheel(final @NonNull String fileName) {
    return fileName.toLowerCase(Locale.ROOT).endsWith(".whl");
  }

  static @NonNull Path extract(final @NonNull Path archivePath) throws IOException {
    final var extractDir = Files.createTempDirectory("scan-extracted-");

    try (final var fileIn = Files.newInputStream(archivePath);
        final var zipIn = new ZipInputStream(fileIn)) {

      ZipEntry entry;
      while ((entry = zipIn.getNextEntry()) != null) {
        extractEntry(extractDir, entry, zipIn);
      }
    }

    return extractDir;
  }

  private static void extractEntry(
      final @NonNull Path extractDir,
      final @NonNull ZipEntry entry,
      final @NonNull ZipInputStream zipIn)
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

    Files.createDirectories(target.getParent());
    Files.copy(zipIn, target, StandardCopyOption.REPLACE_EXISTING);
  }
}
