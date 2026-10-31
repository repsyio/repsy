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
package io.repsy.protocols.shared.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link SuspiciousRequestThrottle} (RPS-2319). The fields are boxed and the compact
 * constructor defaults every one of them, like {@link AuthThrottleProperties}.
 *
 * @param mode see {@link AuthThrottleMode}; defaults to {@code enforce}
 * @param maxStrikes the strikes of one client in one window that ban it; a single request whose
 *     signals weigh this much bans at once
 * @param windowSeconds the length of the strike window
 * @param banSeconds how long a banned client is refused with 429, whatever it asks for
 * @param maxClients how many clients are tracked at once before the least recently used go
 * @param allowlist addresses or CIDR ranges ({@code 10.0.0.0/8}) that are never counted or banned
 * @param signals per family ({@link SignalFamily#key()}) switch and weight; a family not listed is
 *     on with its default weight
 */
@ConfigurationProperties(prefix = "repsy.security.probe-throttle")
public record SuspiciousRequestProperties(
    AuthThrottleMode mode,
    Integer maxStrikes,
    Long windowSeconds,
    Long banSeconds,
    Long maxClients,
    List<String> allowlist,
    Map<String, Signal> signals) {

  static final int DEFAULT_MAX_STRIKES = 10;
  static final long DEFAULT_WINDOW_SECONDS = 60;
  static final long DEFAULT_BAN_SECONDS = 3600;
  static final long DEFAULT_MAX_CLIENTS = 10_000;

  /**
   * Switch, weight and port scope of one {@link SignalFamily}; {@code null} keeps the default.
   * {@code apiPortOnly} {@code false} makes a family look at the protocol port too.
   */
  public record Signal(
      @Nullable Boolean enabled, @Nullable Integer weight, @Nullable Boolean apiPortOnly) {}

  public SuspiciousRequestProperties {
    mode = Objects.requireNonNullElse(mode, AuthThrottleMode.ENFORCE);
    maxStrikes = Objects.requireNonNullElse(maxStrikes, DEFAULT_MAX_STRIKES);
    windowSeconds = Objects.requireNonNullElse(windowSeconds, DEFAULT_WINDOW_SECONDS);
    banSeconds = Objects.requireNonNullElse(banSeconds, DEFAULT_BAN_SECONDS);
    maxClients = Objects.requireNonNullElse(maxClients, DEFAULT_MAX_CLIENTS);
    allowlist = Objects.requireNonNullElse(allowlist, List.of());
    signals = Objects.requireNonNullElse(signals, Map.of());

    requirePositiveUnlessOff(mode, maxStrikes, windowSeconds, banSeconds, maxClients);
    requireNoNegativeWeight(signals);
  }

  private static void requirePositiveUnlessOff(
      final AuthThrottleMode mode,
      final int maxStrikes,
      final long windowSeconds,
      final long banSeconds,
      final long maxClients) {

    if (mode != AuthThrottleMode.OFF
        && (maxStrikes <= 0 || windowSeconds <= 0 || banSeconds <= 0 || maxClients <= 0)) {
      throw new IllegalArgumentException(
          "repsy.security.probe-throttle.max-strikes, window-seconds, ban-seconds and max-clients"
              + " must be positive, or the mode must be off");
    }
  }

  private static void requireNoNegativeWeight(final Map<String, Signal> signals) {

    for (final var entry : signals.entrySet()) {
      final var weight = entry.getValue().weight();

      if (weight != null && weight < 0) {
        throw new IllegalArgumentException(
            "repsy.security.probe-throttle.signals."
                + entry.getKey()
                + ".weight must not be negative");
      }
    }
  }

  /** The strikes one request of the family counts; 0 when the family is switched off. */
  public int weight(final SignalFamily family) {

    final var signal = this.signals.get(family.key());

    if (signal == null) {
      return family.defaultWeight();
    }

    if (Boolean.FALSE.equals(signal.enabled())) {
      return 0;
    }

    final var weight = signal.weight();

    return weight == null ? family.defaultWeight() : weight;
  }

  /** Whether the family is only checked on the API port, by default or as the settings say. */
  public boolean apiPortOnly(final SignalFamily family) {

    final var signal = this.signals.get(family.key());

    final var apiPortOnly = signal == null ? null : signal.apiPortOnly();

    return apiPortOnly == null ? family.apiPortOnly() : apiPortOnly;
  }

  /** Settings under which nothing is tracked, refused or answered early. */
  public static SuspiciousRequestProperties disabled() {

    return new SuspiciousRequestProperties(
        AuthThrottleMode.OFF, 0, 0L, 0L, 0L, List.of(), Map.of());
  }

  /** The defaults in the given mode, with the strike limit and ban time of a test. */
  public static SuspiciousRequestProperties of(
      final AuthThrottleMode mode, final int maxStrikes, final long banSeconds) {

    return new SuspiciousRequestProperties(
        mode,
        maxStrikes,
        DEFAULT_WINDOW_SECONDS,
        banSeconds,
        DEFAULT_MAX_CLIENTS,
        List.of(),
        Map.of());
  }
}
