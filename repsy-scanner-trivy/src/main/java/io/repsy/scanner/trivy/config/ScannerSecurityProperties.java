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
package io.repsy.scanner.trivy.config;

import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret of the scanner's API ({@code X-Scanner-Api-Key}).
 *
 * <p>It is checked when the properties are bound, so a scanner without a usable key does not start.
 * A key that is missing or blank is refused, and so is one that still holds a placeholder such as
 * {@code ${SCANNER_API_KEY}}: Spring leaves an unresolvable placeholder in the value as text, and
 * that text would otherwise be accepted as the key by anyone who sends it (RPS-1663). The message
 * never holds the value.
 *
 * @param apiKey the key a client must send
 */
@ConfigurationProperties(prefix = "scanner.security")
public record ScannerSecurityProperties(@NonNull String apiKey) {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{.*}", Pattern.DOTALL);

  public ScannerSecurityProperties {
    // null: the property is not set at all (the record is built by the binder, which may pass null)
    if (apiKey == null || apiKey.isBlank() || PLACEHOLDER.matcher(apiKey).find()) {
      throw new IllegalArgumentException(
          "scanner.security.api-key (SCANNER_API_KEY) must be set to a non-blank secret, shared"
              + " with the application as its TRIVY_SCANNER_API_KEY: it is missing, blank or an"
              + " unresolved placeholder, and a scanner without a key would accept any request");
    }
  }
}
