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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// RPS-1663: a scanner without a usable API key must not start. Before, application.yml resolved an
// unset SCANNER_API_KEY to the literal text "${SCANNER_API_KEY}", which a client could then send as
// the key of an open scanner.
class ScannerSecurityPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(ScannerSecurityProperties.class)
  static class PropertiesConfiguration {}

  // The shipped application.yml, with what the test adds on top.
  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfiguration.class);
  }

  @Test
  void theShippedConfigurationDoesNotStartWithoutSCANNER_API_KEY() {
    assumeThat(System.getenv("SCANNER_API_KEY")).isNull();

    runner()
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("SCANNER_API_KEY")
                  .hasMessageContaining("must be set");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n", "${SCANNER_API_KEY}", "${SCANNER_API_KEY:}", "${OTHER}"})
  void aBlankOrUnresolvedKeyStopsTheStartWithAClearMessage(final String key) {
    runner()
        .withPropertyValues("scanner.security.api-key=" + key)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("scanner.security.api-key (SCANNER_API_KEY)")
                  .hasMessageContaining("must be set to a non-blank secret");
            });
  }

  @Test
  void theMessageNeverHoldsTheValue() {
    runner()
        .withPropertyValues("scanner.security.api-key=${TOP_SECRET_NAME}")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageNotContaining("TOP_SECRET_NAME"));
  }

  @Test
  void aRealKeyStartsAndIsKeptAsItIs() {
    runner()
        .withPropertyValues("scanner.security.api-key=s3cr3t-$key")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ScannerSecurityProperties.class).apiKey())
                  .isEqualTo("s3cr3t-$key");
            });
  }

  @Test
  void aKeyThatOnlyContainsADollarSignIsNotAPlaceholder() {
    runner()
        .withPropertyValues("scanner.security.api-key=pa$$w{ord}")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
