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
import java.time.Instant;

/** Fake Trivy cache directories for the tests: the files Trivy leaves, with no real database. */
final class TrivyTestFiles {

  static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");
  static final Instant FAR_AHEAD = Instant.parse("2999-01-01T00:00:00Z");

  private TrivyTestFiles() {}

  static void writeVulnerabilityDb(
      final Path cacheDir, final String content, final Instant updatedAt, final Instant nextUpdate)
      throws IOException {
    writeDb(cacheDir.resolve("db"), "trivy.db", content, updatedAt, nextUpdate);
  }

  static void writeJavaDb(
      final Path cacheDir, final String content, final Instant updatedAt, final Instant nextUpdate)
      throws IOException {
    writeDb(cacheDir.resolve("java-db"), "trivy-java.db", content, updatedAt, nextUpdate);
  }

  private static void writeDb(
      final Path dir,
      final String fileName,
      final String content,
      final Instant updatedAt,
      final Instant nextUpdate)
      throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(fileName), content);
    Files.writeString(
        dir.resolve("metadata.json"),
        "{\"Version\":2,\"NextUpdate\":\""
            + nextUpdate
            + "\",\"UpdatedAt\":\""
            + updatedAt
            + "\",\"DownloadedAt\":\""
            + updatedAt
            + "\"}");
  }
}
