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

import io.repsy.scanner.trivy.config.AdvisoryProperties;
import io.repsy.scanner.trivy.dtos.AdvisoryLookupResponse;
import io.repsy.scanner.trivy.dtos.AdvisoryRequest;
import io.repsy.scanner.trivy.errors.AdvisoryTooLargeException;
import io.repsy.scanner.trivy.services.AdvisoryLookupService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
public class AdvisoryController {

  private final @NonNull AdvisoryLookupService advisoryLookupService;
  private final @NonNull AdvisoryProperties properties;
  private final @NonNull ObjectMapper objectMapper;

  // The body is read here, not bound by Spring, so that its size is bounded before it is parsed.
  @PostMapping(path = "/advisories", consumes = MediaType.APPLICATION_JSON_VALUE)
  public @NonNull AdvisoryLookupResponse lookup(final @NonNull HttpServletRequest request)
      throws IOException {

    final var limit = this.properties.maxBodyBytes();

    if (request.getContentLengthLong() > limit) {
      throw this.tooLarge();
    }

    final var body = request.getInputStream().readNBytes((int) limit + 1);

    if (body.length > limit) {
      throw this.tooLarge();
    }

    final AdvisoryRequest advisoryRequest;

    try {
      advisoryRequest = this.objectMapper.readValue(body, AdvisoryRequest.class);
    } catch (final JacksonException exception) {
      throw new IllegalArgumentException("The body is not a valid advisory request", exception);
    }

    if (advisoryRequest == null) {
      throw new IllegalArgumentException("The body is empty");
    }

    return this.advisoryLookupService.lookup(advisoryRequest);
  }

  private @NonNull AdvisoryTooLargeException tooLarge() {
    return new AdvisoryTooLargeException(
        "The body must not be larger than " + this.properties.maxBodyBytes() + " bytes");
  }
}
