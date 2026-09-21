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
package io.repsy.os.server.shared.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link AuthFailureThrottle}.
 *
 * @param enabled whether failed password checks are limited at all
 * @param maxFailures how many failed password checks a client may make in one window before the
 *     next password check is refused
 * @param windowSeconds the length of the window, after which a client starts again with a clean
 *     count
 * @param maxClients how many clients are tracked at once before the least recently used go
 */
@ConfigurationProperties(prefix = "repsy.security.auth-throttle")
public record AuthThrottleProperties(
    boolean enabled, int maxFailures, long windowSeconds, long maxClients) {

  public AuthThrottleProperties {
    if (enabled && (maxFailures <= 0 || windowSeconds <= 0 || maxClients <= 0)) {
      throw new IllegalArgumentException(
          "repsy.security.auth-throttle.max-failures, window-seconds and max-clients must be"
              + " positive, or the throttle must be disabled");
    }
  }

  /** Settings under which no password check is ever refused. */
  public static AuthThrottleProperties disabled() {

    return new AuthThrottleProperties(false, 0, 0, 0);
  }
}
