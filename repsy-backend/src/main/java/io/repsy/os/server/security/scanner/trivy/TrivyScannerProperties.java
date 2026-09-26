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
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Trivy scanner client.
 *
 * <p>The submit retry ({@code submitMaxAttempts}, {@code submitRetryInitialDelaySeconds}, {@code
 * submitRetryMaxDelaySeconds}) applies to a submit that could not reach the scanner. The delay
 * before retry {@code n} is {@code min(initial * 4^(n-1), max)} seconds, so the defaults wait 15 s
 * and then 60 s. Keep {@link #submitRetryBudgetSeconds()} below {@code maxScanDurationSeconds}: the
 * status poller fails a scan that is still {@code PENDING} that long after it was created.
 *
 * @param submitMaxAttempts submits made in all, the first included; 1 turns the retry off
 * @param submitRetryInitialDelaySeconds wait before the first retry
 * @param submitRetryMaxDelaySeconds upper bound of the wait before any retry
 */
@ConfigurationProperties(prefix = "repsy.security.trivy")
public record TrivyScannerProperties(
    @NonNull String scannerBaseUrl,
    @NonNull String apiKey,
    long requestTimeoutSeconds,
    long pollIntervalMs,
    long maxScanDurationSeconds,
    @DefaultValue("3") int submitMaxAttempts,
    @DefaultValue("15") long submitRetryInitialDelaySeconds,
    @DefaultValue("60") long submitRetryMaxDelaySeconds) {

  public static final int MAX_SUBMIT_ATTEMPTS = 5;
  public static final long MAX_SUBMIT_RETRY_DELAY_SECONDS = 300;

  private static final int DELAY_GROWTH_FACTOR = 4;

  public TrivyScannerProperties {
    requireBetween(
        "submit-max-attempts (TRIVY_SUBMIT_MAX_ATTEMPTS)",
        submitMaxAttempts,
        1,
        MAX_SUBMIT_ATTEMPTS);
    requireBetween(
        "submit-retry-initial-delay-seconds (TRIVY_SUBMIT_RETRY_INITIAL_DELAY_SECONDS)",
        submitRetryInitialDelaySeconds,
        1,
        MAX_SUBMIT_RETRY_DELAY_SECONDS);
    requireBetween(
        "submit-retry-max-delay-seconds (TRIVY_SUBMIT_RETRY_MAX_DELAY_SECONDS)",
        submitRetryMaxDelaySeconds,
        submitRetryInitialDelaySeconds,
        MAX_SUBMIT_RETRY_DELAY_SECONDS);
  }

  private static void requireBetween(
      final @NonNull String setting, final long value, final long min, final long max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "repsy.security.trivy."
              + setting
              + " must be between "
              + min
              + " and "
              + max
              + ", but was "
              + value);
    }
  }

  /**
   * The wait before retry {@code retryNumber} (1 for the first retry).
   *
   * @param retryNumber the retry, counted from 1
   */
  public @NonNull Duration submitRetryDelay(final int retryNumber) {
    var seconds = this.submitRetryInitialDelaySeconds;

    for (var step = 1; step < retryNumber && seconds < this.submitRetryMaxDelaySeconds; step++) {
      seconds *= DELAY_GROWTH_FACTOR;
    }

    return Duration.ofSeconds(Math.min(seconds, this.submitRetryMaxDelaySeconds));
  }

  /**
   * The longest a scan can spend on submit retries: every wait plus one request timeout per attempt
   * (a connect attempt that hangs fails after at most the connect timeout, far below the request
   * timeout, so this is a safe upper bound).
   */
  public long submitRetryBudgetSeconds() {
    var total = this.submitMaxAttempts * this.requestTimeoutSeconds;

    for (var retry = 1; retry < this.submitMaxAttempts; retry++) {
      total += this.submitRetryDelay(retry).toSeconds();
    }

    return total;
  }
}
