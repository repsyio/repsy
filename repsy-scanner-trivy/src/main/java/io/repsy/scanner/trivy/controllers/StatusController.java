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
package io.repsy.scanner.trivy.controllers;

import io.repsy.scanner.trivy.dtos.ScannerStatus;
import io.repsy.scanner.trivy.dtos.trivy.TrivyDbMetadata;
import io.repsy.scanner.trivy.services.TrivyDatabaseMetadata;
import io.repsy.scanner.trivy.services.TrivyScanService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Unlike /health this is behind the API key (ApiKeyAuthFilter): the Trivy version and the age of
// its database tell an attacker which advisories the scanner is missing.
@RestController
@RequiredArgsConstructor
public class StatusController {

  private final @NonNull TrivyScanService trivyScanService;
  private final @NonNull TrivyDatabaseMetadata databaseMetadata;

  @GetMapping("/status")
  public @NonNull ScannerStatus status() {
    final var vulnerabilityDb = this.databaseMetadata.vulnerabilityDb();
    final var javaDb = this.databaseMetadata.javaDb();

    return new ScannerStatus(
        this.trivyScanService.trivyVersion(),
        vulnerabilityDb.map(TrivyDbMetadata::updatedAt).orElse(null),
        vulnerabilityDb.map(TrivyDbMetadata::downloadedAt).orElse(null),
        javaDb.map(TrivyDbMetadata::updatedAt).orElse(null));
  }
}
