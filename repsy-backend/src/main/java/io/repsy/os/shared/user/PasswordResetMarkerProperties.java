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
package io.repsy.os.shared.user;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link PasswordResetMarkerScanner} (RPS-1107).
 *
 * @param enabled whether marker files are looked for at all
 * @param dir the directory an operator creates a marker file in, named after the user
 * @param pollInterval how often a running instance looks into {@code dir} (a marker created while
 *     the application is stopped is picked up once at startup)
 */
@ConfigurationProperties(prefix = "repsy.security.password-reset")
public record PasswordResetMarkerProperties(boolean enabled, Path dir, Duration pollInterval) {

  static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(1);

  public PasswordResetMarkerProperties {
    if (enabled
        && (dir == null || pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0)) {
      throw new IllegalArgumentException(
          "repsy.security.password-reset.dir must be set and poll-interval must be at least PT1S,"
              + " or the password reset marker must be disabled");
    }
  }
}
