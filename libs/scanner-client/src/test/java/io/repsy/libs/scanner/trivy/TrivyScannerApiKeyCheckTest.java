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
package io.repsy.libs.scanner.trivy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@DisplayName("TrivyScannerApiKeyCheck")
class TrivyScannerApiKeyCheckTest {

  @Configuration
  @EnableConfigurationProperties(TrivyScannerClientProperties.class)
  @Import(TrivyScannerApiKeyCheck.class)
  static class CheckConfiguration {}

  private static ApplicationContextRunner runner(final String scanner, final String apiKey) {
    return new ApplicationContextRunner()
        .withUserConfiguration(CheckConfiguration.class)
        .withPropertyValues(
            "repsy.security.scanner=" + scanner,
            "repsy.security.trivy.scanner-base-url=http://scanner",
            "repsy.security.trivy.api-key=" + apiKey,
            "repsy.security.trivy.request-timeout-seconds=10",
            "repsy.security.trivy.poll-interval-ms=3000",
            "repsy.security.trivy.max-scan-duration-seconds=330");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "${TRIVY_SCANNER_API_KEY}", "${TRIVY_SCANNER_API_KEY:}"})
  @DisplayName("an enabled scanner without a usable key stops the start with a clear message")
  void enabledScannerWithoutAKeyDoesNotStart(final String apiKey) {
    runner("enabled", apiKey)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("repsy.security.trivy.api-key (TRIVY_SCANNER_API_KEY)")
                  .hasMessageContaining("SCANNER_API_KEY of repsy-scanner-trivy");
            });
  }

  @Test
  @DisplayName("the message never holds the value")
  void messageNeverHoldsTheValue() {
    runner("enabled", "${SECRET_NAME}")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageNotContaining("SECRET_NAME"));
  }

  @Test
  @DisplayName("an enabled scanner with a key starts")
  void enabledScannerWithAKeyStarts() {
    runner("enabled", "s3cr3t")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TrivyScannerApiKeyCheck.class);
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "off", ""})
  @DisplayName("nothing is checked while the scanner is not enabled: the key is unused")
  void disabledScannerNeedsNoKey(final String scanner) {
    runner(scanner, "")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(TrivyScannerApiKeyCheck.class);
            });
  }
}
