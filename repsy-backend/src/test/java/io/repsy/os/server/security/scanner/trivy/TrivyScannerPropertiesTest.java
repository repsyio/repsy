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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("TrivyScannerProperties")
class TrivyScannerPropertiesTest {

  private static final String PREFIX = "repsy.security.trivy";

  @Configuration
  @EnableConfigurationProperties(TrivyScannerProperties.class)
  static class PropertiesConfiguration {}

  private static TrivyScannerProperties bind(final Map<String, Object> overrides) {
    final Map<String, Object> properties = new HashMap<>();
    properties.put(PREFIX + ".scanner-base-url", "http://scanner");
    properties.put(PREFIX + ".api-key", "key");
    properties.put(PREFIX + ".request-timeout-seconds", "10");
    properties.put(PREFIX + ".poll-interval-ms", "3000");
    properties.put(PREFIX + ".max-scan-duration-seconds", "330");
    overrides.forEach((key, value) -> properties.put(PREFIX + "." + key, value));

    return new Binder(new MapConfigurationPropertySource(properties))
        .bind(PREFIX, TrivyScannerProperties.class)
        .get();
  }

  @Test
  @DisplayName("retries the submit three times in all, after 15 s and then 60 s, when unset")
  void defaultsToThreeAttempts() {
    final var properties = bind(Map.of());

    assertThat(properties.submitMaxAttempts()).isEqualTo(3);
    assertThat(properties.submitRetryDelay(1)).isEqualTo(Duration.ofSeconds(15));
    assertThat(properties.submitRetryDelay(2)).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("the delay grows fourfold and stops at the maximum")
  void delayGrowsAndIsCapped() {
    final var properties =
        new TrivyScannerProperties("http://scanner", "key", 10, 3000, 330, 5, 1, 30);

    assertThat(properties.submitRetryDelay(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(properties.submitRetryDelay(2)).isEqualTo(Duration.ofSeconds(4));
    assertThat(properties.submitRetryDelay(3)).isEqualTo(Duration.ofSeconds(16));
    assertThat(properties.submitRetryDelay(4)).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.submitRetryDelay(50)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("the retry budget adds every wait to one request timeout per attempt")
  void budgetCountsWaitsAndRequests() {
    assertThat(bind(Map.of()).submitRetryBudgetSeconds()).isEqualTo(15 + 60 + 3 * 10);
    assertThat(bind(Map.of("submit-max-attempts", "1")).submitRetryBudgetSeconds()).isEqualTo(10);
  }

  @Test
  @DisplayName("the shipped defaults keep the retry budget below the maximum scan duration")
  void shippedDefaultsStayBelowTheMaximumScanDuration() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PropertiesConfiguration.class)
        .run(
            context -> {
              final var properties = context.getBean(TrivyScannerProperties.class);

              assertThat(properties.submitMaxAttempts()).isEqualTo(3);
              assertThat(properties.submitRetryBudgetSeconds())
                  .isLessThan(properties.maxScanDurationSeconds());
            });
  }

  @Test
  @DisplayName(
      "an attempt count outside 1 to 5 stops the binding with a message naming the setting")
  void rejectsAttemptsOutsideTheBounds() {
    assertThatThrownBy(() -> bind(Map.of("submit-max-attempts", "0")))
        .isInstanceOf(BindException.class)
        .rootCause()
        .hasMessageContaining("TRIVY_SUBMIT_MAX_ATTEMPTS")
        .hasMessageContaining("was 0");
    assertThatThrownBy(() -> bind(Map.of("submit-max-attempts", "6")))
        .rootCause()
        .hasMessageContaining("was 6");
    assertThat(bind(Map.of("submit-max-attempts", "5")).submitMaxAttempts()).isEqualTo(5);
    assertThat(bind(Map.of("submit-max-attempts", "1")).submitMaxAttempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("an initial delay outside 1 to 300 seconds is rejected")
  void rejectsInitialDelayOutsideTheBounds() {
    assertThatThrownBy(() -> bind(Map.of("submit-retry-initial-delay-seconds", "0")))
        .rootCause()
        .hasMessageContaining("TRIVY_SUBMIT_RETRY_INITIAL_DELAY_SECONDS");
    assertThatThrownBy(
            () ->
                bind(
                    Map.of(
                        "submit-retry-initial-delay-seconds", "301",
                        "submit-retry-max-delay-seconds", "300")))
        .rootCause()
        .hasMessageContaining("TRIVY_SUBMIT_RETRY_INITIAL_DELAY_SECONDS");
  }

  @Test
  @DisplayName("a maximum delay below the initial delay or above 300 seconds is rejected")
  void rejectsMaxDelayOutsideTheBounds() {
    assertThatThrownBy(
            () ->
                bind(
                    Map.of(
                        "submit-retry-initial-delay-seconds", "30",
                        "submit-retry-max-delay-seconds", "29")))
        .rootCause()
        .hasMessageContaining("TRIVY_SUBMIT_RETRY_MAX_DELAY_SECONDS");
    assertThatThrownBy(() -> bind(Map.of("submit-retry-max-delay-seconds", "301")))
        .rootCause()
        .hasMessageContaining("TRIVY_SUBMIT_RETRY_MAX_DELAY_SECONDS");
  }
}
