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

import io.repsy.scanner.trivy.dtos.ScanJobStatus;
import io.repsy.scanner.trivy.dtos.ScanJobStatusResponse;
import io.repsy.scanner.trivy.dtos.ScanSubmissionResponse;
import io.repsy.scanner.trivy.errors.ScanJobNotFoundException;
import io.repsy.scanner.trivy.services.TrivyScanService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  public @NonNull ScanSubmissionResponse scan(
      @RequestParam("scanId") final @NotBlank String scanId,
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
      this.trivyScanService.submitDockerScan(
          scanId,
          dockerImageReference,
          registryAuthToken,
          registryInsecure,
          repoType,
          artifactName,
          artifactVersion);

      return new ScanSubmissionResponse(scanId, ScanJobStatus.QUEUED);
    }

    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file must not be empty");
    }

    this.trivyScanService.submitScan(scanId, file, repoType, artifactName, artifactVersion);

    return new ScanSubmissionResponse(scanId, ScanJobStatus.QUEUED);
  }

  @GetMapping("/scan/{scanId}")
  public @NonNull ScanJobStatusResponse getStatus(@PathVariable final @NonNull String scanId) {
    return this.trivyScanService
        .getStatus(scanId)
        .map(ScanJobStatusResponse::from)
        .orElseThrow(() -> new ScanJobNotFoundException(scanId));
  }
}
