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
package io.repsy.os.server.security.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.scanner.trivy.TrivyScannerClientProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("Shipped Trivy scanner client defaults")
class ScannerShippedDefaultsTest {

  @Configuration
  @EnableConfigurationProperties(TrivyScannerClientProperties.class)
  static class PropertiesConfiguration {}

  @Test
  @DisplayName("the shipped defaults keep the retry budget below the maximum scan duration")
  void shippedDefaultsStayBelowTheMaximumScanDuration() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfiguration.class)
        .run(
            context -> {
              final var properties = context.getBean(TrivyScannerClientProperties.class);

              assertThat(properties.submitMaxAttempts()).isEqualTo(3);
              assertThat(properties.submitRetryBudgetSeconds())
                  .isLessThan(properties.maxScanDurationSeconds());
              assertThat(properties.supportedRepoTypes())
                  .containsExactlyInAnyOrder("MAVEN", "NPM", "PYPI", "DOCKER", "HELM");
            });
  }
}
