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

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stops the start of an application that enables the Trivy scanner without an API key to send it
 * (RPS-1663).
 *
 * <p>The scanner refuses a start without its own {@code SCANNER_API_KEY}, so a blank {@code
 * TRIVY_SCANNER_API_KEY} here could only ever produce scans that fail with {@code 401} after the
 * application is up, one per push. A key that still holds an unresolved placeholder such as {@code
 * ${TRIVY_SCANNER_API_KEY}} is refused as well. Nothing is checked while the scanner is disabled,
 * where the key is not used. The message never holds the value.
 */
@Component
@ConditionalOnProperty(name = "repsy.security.scanner", havingValue = "enabled")
public class TrivyScannerApiKeyCheck {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{.*}", Pattern.DOTALL);

  public TrivyScannerApiKeyCheck(final @NonNull TrivyScannerProperties properties) {
    final var apiKey = properties.apiKey();

    if (apiKey.isBlank() || PLACEHOLDER.matcher(apiKey).find()) {
      throw new IllegalStateException(
          "SECURITY_SCANNER is enabled, so repsy.security.trivy.api-key (TRIVY_SCANNER_API_KEY)"
              + " must be set to a non-blank secret, the same value as the SCANNER_API_KEY of"
              + " repsy-scanner-trivy: it is missing, blank or an unresolved placeholder. Set it,"
              + " or set SECURITY_SCANNER=disabled");
    }
  }
}
