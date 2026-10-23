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

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link AuthFailureThrottle}.
 *
 * <p>The fields are boxed and the compact constructor defaults every one of them, so a Spring
 * context that binds the {@code repsy.security.auth-throttle} prefix to nothing at all still gets
 * the defaults instead of {@code 0}.
 *
 * @param mode see {@link AuthThrottleMode}; defaults to {@code enforce}
 * @param enabled legacy switch ({@code AUTH_THROTTLE_ENABLED}): {@code false} turns the throttle
 *     off whatever the mode says; {@code null} or {@code true} leave the mode alone
 * @param maxFailures how many failed password checks a client may make in one window before the
 *     next password check is refused (in {@code enforce}) or would be (in {@code observe})
 * @param windowSeconds the length of the window, after which a client starts again with a clean
 *     count
 * @param maxClients how many clients are tracked at once before the least recently used go
 * @param banSeconds how long a client that keeps guessing is refused outright, see {@code
 *     banAfterBlockedWindows} (RPS-2306); {@code 0} turns the ban off
 * @param banAfterBlockedWindows how many windows in a row a client must have used up its failures
 *     in before it is banned
 */
@ConfigurationProperties(prefix = "repsy.security.auth-throttle")
public record AuthThrottleProperties(
    AuthThrottleMode mode,
    @Nullable Boolean enabled,
    Integer maxFailures,
    Long windowSeconds,
    Long maxClients,
    Long banSeconds,
    Integer banAfterBlockedWindows) {

  static final int DEFAULT_MAX_FAILURES = 20;
  static final long DEFAULT_WINDOW_SECONDS = 60;
  static final long DEFAULT_MAX_CLIENTS = 10_000;
  static final long DEFAULT_BAN_SECONDS = 3600;
  static final int DEFAULT_BAN_AFTER_BLOCKED_WINDOWS = 3;

  public AuthThrottleProperties {
    mode = Boolean.FALSE.equals(enabled) ? AuthThrottleMode.OFF : mode;
    // Only an explicit false means anything; true is the same as not set.
    enabled = Boolean.FALSE.equals(enabled) ? Boolean.FALSE : null;
    mode = Objects.requireNonNullElse(mode, AuthThrottleMode.ENFORCE);
    maxFailures = Objects.requireNonNullElse(maxFailures, DEFAULT_MAX_FAILURES);
    windowSeconds = Objects.requireNonNullElse(windowSeconds, DEFAULT_WINDOW_SECONDS);
    maxClients = Objects.requireNonNullElse(maxClients, DEFAULT_MAX_CLIENTS);
    banSeconds = Objects.requireNonNullElse(banSeconds, DEFAULT_BAN_SECONDS);
    banAfterBlockedWindows =
        Objects.requireNonNullElse(banAfterBlockedWindows, DEFAULT_BAN_AFTER_BLOCKED_WINDOWS);

    requirePositiveUnlessOff(mode, maxFailures, windowSeconds, maxClients);
    requireValidBanUnlessOff(mode, banSeconds, banAfterBlockedWindows);
  }

  private static void requirePositiveUnlessOff(
      final AuthThrottleMode mode,
      final int maxFailures,
      final long windowSeconds,
      final long maxClients) {

    final var wouldTrackClients = mode != AuthThrottleMode.OFF;
    final var hasNonPositiveSetting = maxFailures <= 0 || windowSeconds <= 0 || maxClients <= 0;

    if (wouldTrackClients && hasNonPositiveSetting) {
      throw new IllegalArgumentException(
          "repsy.security.auth-throttle.max-failures, window-seconds and max-clients must be"
              + " positive, or the mode must be off");
    }
  }

  private static void requireValidBanUnlessOff(
      final AuthThrottleMode mode, final long banSeconds, final int banAfterBlockedWindows) {

    if (mode == AuthThrottleMode.OFF) {
      return;
    }

    if (banSeconds < 0 || (banSeconds > 0 && banAfterBlockedWindows <= 0)) {
      throw new IllegalArgumentException(
          "repsy.security.auth-throttle.ban-seconds must not be negative, and"
              + " ban-after-blocked-windows must be positive unless ban-seconds is 0");
    }
  }

  /** Settings under which no client is ever tracked or refused. */
  public static AuthThrottleProperties disabled() {

    return new AuthThrottleProperties(AuthThrottleMode.OFF, null, 0, 0L, 0L, 0L, 0);
  }

  /** Settings under which every check runs in full and a saturated client is refused. */
  public static AuthThrottleProperties enforcing(
      final int maxFailures, final long windowSeconds, final long maxClients) {

    return new AuthThrottleProperties(
        AuthThrottleMode.ENFORCE,
        null,
        maxFailures,
        windowSeconds,
        maxClients,
        DEFAULT_BAN_SECONDS,
        DEFAULT_BAN_AFTER_BLOCKED_WINDOWS);
  }

  /** Like {@link #enforcing(int, long, long)}, with the ban of RPS-2306 set explicitly. */
  public static AuthThrottleProperties enforcing(
      final int maxFailures,
      final long windowSeconds,
      final long maxClients,
      final long banSeconds,
      final int banAfterBlockedWindows) {

    return new AuthThrottleProperties(
        AuthThrottleMode.ENFORCE,
        null,
        maxFailures,
        windowSeconds,
        maxClients,
        banSeconds,
        banAfterBlockedWindows);
  }

  /** Settings under which a saturated client is counted and logged but let through. */
  public static AuthThrottleProperties observing(
      final int maxFailures, final long windowSeconds, final long maxClients) {

    return new AuthThrottleProperties(
        AuthThrottleMode.OBSERVE,
        null,
        maxFailures,
        windowSeconds,
        maxClients,
        DEFAULT_BAN_SECONDS,
        DEFAULT_BAN_AFTER_BLOCKED_WINDOWS);
  }
}
