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
package io.repsy.os.server.security.scanner.trivy;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "repsy.security.scanner", havingValue = "enabled")
public class TrivyScannerStatusClient {

  private static final String SCAN_PATH = "/scan/";
  private static final String API_KEY_HEADER = "X-Scanner-Api-Key";

  private final @NonNull TrivyScannerProperties properties;

  @Qualifier("trivyScannerWebClient")
  private final @NonNull WebClient webClient;

  public @NonNull ScanJobStatusResponse fetchStatus(final @NonNull UUID scanId) {
    try {
      final var response =
          this.webClient
              .get()
              .uri(this.properties.scannerBaseUrl() + SCAN_PATH + scanId)
              .header(API_KEY_HEADER, this.properties.apiKey())
              .retrieve()
              .bodyToMono(ScanJobStatusResponse.class)
              .block(Duration.ofSeconds(this.properties.requestTimeoutSeconds()));

      if (response == null) {
        throw new TrivyScanException("Scanner returned an empty status response");
      }

      return response;
    } catch (final WebClientResponseException exception) {
      if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
        throw new ScanJobNotFoundException(scanId);
      }

      throw new TrivyScanException(
          "Scanner status check failed: " + exception.getStatusCode(), exception);
    }
  }
}
