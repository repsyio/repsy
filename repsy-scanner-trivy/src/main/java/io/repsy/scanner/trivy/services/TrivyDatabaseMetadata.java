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

import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.trivy.TrivyDbMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Reads what Trivy records about its databases in the cache directory. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrivyDatabaseMetadata {

  private static final String METADATA_FILE = "metadata.json";
  private static final String VULNERABILITY_DB_DIR = "db";
  private static final String VULNERABILITY_DB_FILE = "trivy.db";
  private static final String JAVA_DB_DIR = "java-db";

  private final @NonNull TrivyScannerProperties properties;
  private final @NonNull ObjectMapper objectMapper;

  /** The vulnerability database, or empty when there is none (or it is being replaced). */
  public @NonNull Optional<TrivyDbMetadata> vulnerabilityDb() {
    final var dir = Path.of(this.properties.cacheDir()).resolve(VULNERABILITY_DB_DIR);

    if (!Files.isRegularFile(dir.resolve(VULNERABILITY_DB_FILE))) {
      return Optional.empty();
    }

    return this.read(dir.resolve(METADATA_FILE));
  }

  public @NonNull Optional<TrivyDbMetadata> javaDb() {
    return this.read(
        Path.of(this.properties.cacheDir()).resolve(JAVA_DB_DIR).resolve(METADATA_FILE));
  }

  /** The {@code UpdatedAt} of the vulnerability database: when its content was published. */
  public @NonNull Optional<Instant> vulnerabilityDbUpdatedAt() {
    return this.vulnerabilityDb().map(TrivyDbMetadata::updatedAt);
  }

  /**
   * Whether trivy would download a database now: one is missing, or its {@code NextUpdate} has
   * passed. (Trivy publishes a database every 6 hours and sets {@code NextUpdate} a day ahead, and
   * {@code --download-db-only} does nothing before that. Asking first spares a refresh that changes
   * nothing from waiting for running scans.)
   */
  public boolean isRefreshDue(final @NonNull Instant now) {
    return isDue(this.vulnerabilityDb(), now) || isDue(this.javaDb(), now);
  }

  private static boolean isDue(
      final @NonNull Optional<TrivyDbMetadata> metadata, final @NonNull Instant now) {
    return metadata.map(TrivyDbMetadata::nextUpdate).map(next -> !next.isAfter(now)).orElse(true);
  }

  private @NonNull Optional<TrivyDbMetadata> read(final @NonNull Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }

    try {
      return Optional.of(this.objectMapper.readValue(file.toFile(), TrivyDbMetadata.class));
    } catch (final JacksonException exception) {
      log.warn("Could not read {}", file, exception);
      return Optional.empty();
    }
  }
}
