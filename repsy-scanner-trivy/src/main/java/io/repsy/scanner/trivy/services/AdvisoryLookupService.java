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

import io.repsy.scanner.trivy.config.AdvisoryProperties;
import io.repsy.scanner.trivy.config.TrivyScannerProperties;
import io.repsy.scanner.trivy.dtos.AdvisoryLookupResponse;
import io.repsy.scanner.trivy.dtos.AdvisoryPackage;
import io.repsy.scanner.trivy.dtos.AdvisoryRequest;
import io.repsy.scanner.trivy.dtos.trivy.TrivyReport;
import io.repsy.scanner.trivy.errors.AdvisoryTimeoutException;
import io.repsy.scanner.trivy.errors.AdvisoryTooLargeException;
import io.repsy.scanner.trivy.errors.AdvisoryUnavailableException;
import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.errors.TrivyTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers "which advisories does the vulnerability database hold for these (name, version) pairs"
 * synchronously, from the database that is already on disk: it never downloads (see {@link
 * TrivyDatabaseRefreshTask}) and never touches the network.
 */
@Slf4j
@Service
public class AdvisoryLookupService {

  static final String NPM = "npm";

  private static final int MAX_NAME_LENGTH = 214;
  private static final int MAX_VERSION_LENGTH = 256;

  // What trivy prints when it cannot use the database that is on disk right now.
  private static final List<String> DATABASE_UNUSABLE_MARKERS =
      List.of(
          "--skip-db-update cannot be specified on the first run",
          "may be in use by another process");

  private final @NonNull TrivyScannerProperties trivyProperties;
  private final @NonNull AdvisoryProperties properties;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull TrivyCommandRunner runner;
  private final @NonNull TrivyDatabaseAccess access;
  private final @NonNull TrivyDatabaseMetadata metadata;
  private final @NonNull CycloneDxSbomWriter sbomWriter;
  private final @NonNull TrivyScanService scanService;

  // Lookups that run or wait for the database at once; the rest are refused.
  private final @NonNull Semaphore admission;

  public AdvisoryLookupService(
      final @NonNull TrivyScannerProperties trivyProperties,
      final @NonNull AdvisoryProperties properties,
      final @NonNull ObjectMapper objectMapper,
      final @NonNull TrivyCommandRunner runner,
      final @NonNull TrivyDatabaseAccess access,
      final @NonNull TrivyDatabaseMetadata metadata,
      final @NonNull CycloneDxSbomWriter sbomWriter,
      final @NonNull TrivyScanService scanService) {

    this.trivyProperties = trivyProperties;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.runner = runner;
    this.access = access;
    this.metadata = metadata;
    this.sbomWriter = sbomWriter;
    this.scanService = scanService;
    this.admission = new Semaphore(properties.concurrency());
  }

  public @NonNull AdvisoryLookupResponse lookup(final @NonNull AdvisoryRequest request) {
    final var packages = this.validate(request);

    if (packages.isEmpty()) {
      return new AdvisoryLookupResponse(
          this.metadata.vulnerabilityDbUpdatedAt().orElse(null),
          this.scanService.trivyVersion(),
          List.of());
    }

    if (!this.admission.tryAcquire()) {
      throw new AdvisoryUnavailableException("Too many advisory lookups at once, retry later");
    }

    try {
      return this.lookupUnderAdmission(packages);
    } finally {
      this.admission.release();
    }
  }

  private @NonNull AdvisoryLookupResponse lookupUnderAdmission(
      final @NonNull List<AdvisoryPackage> packages) {

    final var maxWait = Duration.ofSeconds(this.properties.maxWaitSeconds());

    try (var ignored =
        this.access
            .tryEnterLookup(maxWait)
            .orElseThrow(
                () ->
                    new AdvisoryUnavailableException(
                        "The vulnerability database is in use by a scan or being refreshed,"
                            + " retry later"))) {

      final var dbUpdatedAt =
          this.metadata
              .vulnerabilityDbUpdatedAt()
              .orElseThrow(
                  () ->
                      new AdvisoryUnavailableException(
                          "The vulnerability database has not been downloaded yet"));

      final var report = this.runLookup(packages);
      final var findings = TrivyFindingMapper.toFindings(report.results());

      return new AdvisoryLookupResponse(dbUpdatedAt, this.scanService.trivyVersion(), findings);
    }
  }

  private @NonNull TrivyReport runLookup(final @NonNull List<AdvisoryPackage> packages) {
    Path sbom = null;

    try {
      sbom = Files.createTempFile("advisories-", ".cdx.json");
      this.sbomWriter.writeNpm(sbom, packages);

      final var stdout = this.runner.run(this.buildCommand(sbom), this.properties.timeoutSeconds());

      return this.objectMapper.readValue(stdout, TrivyReport.class);
    } catch (final TrivyTimeoutException exception) {
      log.warn("Advisory lookup of {} packages timed out", packages.size());
      throw new AdvisoryTimeoutException(
          "The advisory lookup took longer than " + this.properties.timeoutSeconds() + "s");
    } catch (final TrivyScanException exception) {
      if (isDatabaseUnusable(exception.getMessage())) {
        log.warn("Advisory lookup could not use the database: {}", exception.getMessage());
        throw new AdvisoryUnavailableException(
            "The vulnerability database is being replaced, retry later");
      }

      log.error("Advisory lookup of {} packages failed", packages.size(), exception);
      throw new TrivyScanException("Advisory lookup failed");
    } catch (final IOException | JacksonException exception) {
      log.error("Advisory lookup of {} packages failed", packages.size(), exception);
      throw new TrivyScanException("Advisory lookup failed");
    } finally {
      deleteQuietly(sbom);
    }
  }

  private @NonNull List<String> buildCommand(final @NonNull Path sbom) {
    return List.of(
        this.trivyProperties.binaryPath(),
        "sbom",
        "--format",
        "json",
        "--quiet",
        "--cache-dir",
        this.trivyProperties.cacheDir(),
        "--offline-scan",
        "--skip-db-update",
        "--skip-java-db-update",
        sbom.toString());
  }

  // The pairs to look up, without repeats. A malformed request is refused as a whole (400): the
  // caller sends what a registry knows, so a bad pair is a bug over there.
  private @NonNull List<AdvisoryPackage> validate(final @NonNull AdvisoryRequest request) {
    if (!NPM.equals(request.ecosystem())) {
      throw new IllegalArgumentException("ecosystem must be \"" + NPM + "\"");
    }

    if (request.packages() == null) {
      throw new IllegalArgumentException("packages must be a list");
    }

    if (request.packages().size() > this.properties.maxPackages()) {
      throw new AdvisoryTooLargeException(
          "packages must not hold more than " + this.properties.maxPackages() + " entries");
    }

    final var unique = new LinkedHashSet<AdvisoryPackage>();

    for (final var pair : request.packages()) {
      if (pair == null) {
        throw new IllegalArgumentException("packages must not contain null");
      }

      requireNpmName(pair.name());
      requireText(pair.version(), "version", MAX_VERSION_LENGTH);
      unique.add(pair);
    }

    return new ArrayList<>(unique);
  }

  // An npm name is "name" or "@scope/name": one slash at most, and only in the scoped form.
  private static void requireNpmName(final String name) {
    requireText(name, "name", MAX_NAME_LENGTH);

    final var slash = name.indexOf('/');
    final var scoped = name.startsWith("@");

    if (scoped != (slash >= 0)
        || name.indexOf('/', slash + 1) >= 0
        || (scoped && (slash < 2 || slash == name.length() - 1))) {
      throw new IllegalArgumentException("name is not an npm package name: " + printable(name));
    }
  }

  private static void requireText(final String value, final String field, final int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }

    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must not be longer than " + maxLength);
    }

    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
  }

  private static String printable(final String value) {
    return value.chars().anyMatch(Character::isISOControl) ? "(control characters)" : value;
  }

  private static boolean isDatabaseUnusable(final String message) {
    return message != null && DATABASE_UNUSABLE_MARKERS.stream().anyMatch(message::contains);
  }

  private static void deleteQuietly(final Path path) {
    if (path == null) {
      return;
    }

    try {
      Files.deleteIfExists(path);
    } catch (final IOException exception) {
      log.warn("Failed to delete temporary SBOM {}", path, exception);
    }
  }
}
