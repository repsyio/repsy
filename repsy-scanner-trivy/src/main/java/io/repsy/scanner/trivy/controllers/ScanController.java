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

import io.repsy.scanner.trivy.dtos.ScanOutcome;
import io.repsy.scanner.trivy.services.TrivyScanService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequiredArgsConstructor
public class ScanController {

  private final @NonNull TrivyScanService trivyScanService;

  @PostMapping(path = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public @NonNull ScanOutcome scan(
      @RequestParam(value = "file", required = false) final @Nullable MultipartFile file,
      @RequestParam("repoType") final @NotBlank String repoType,
      @RequestParam("artifactName") final @NotBlank String artifactName,
      @RequestParam("artifactVersion") final @NotBlank String artifactVersion,
      @RequestParam(value = "dockerImageReference", required = false)
          final @Nullable String dockerImageReference,
      @RequestParam(value = "registryAuthToken", required = false)
          final @Nullable String registryAuthToken,
      @RequestParam(value = "registryInsecure", required = false, defaultValue = "false")
          final boolean registryInsecure) {

    if (dockerImageReference != null) {
      return this.trivyScanService.scanDockerReference(
          dockerImageReference,
          registryAuthToken,
          registryInsecure,
          repoType,
          artifactName,
          artifactVersion);
    }

    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file must not be empty");
    }

    return this.trivyScanService.scan(file, repoType, artifactName, artifactVersion);
  }
}
