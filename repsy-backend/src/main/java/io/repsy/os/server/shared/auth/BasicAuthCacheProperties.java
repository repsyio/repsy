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
 * Settings of {@link VerifiedPasswordCache}.
 *
 * @param enabled whether successful password checks are remembered at all
 * @param ttlSeconds how long a remembered check stays valid
 * @param maxEntries how many remembered checks are kept before the least recently used go
 */
@ConfigurationProperties(prefix = "repsy.security.basic-auth-cache")
public record BasicAuthCacheProperties(boolean enabled, long ttlSeconds, long maxEntries) {

  public BasicAuthCacheProperties {
    if (enabled && (ttlSeconds <= 0 || maxEntries <= 0)) {
      throw new IllegalArgumentException(
          "repsy.security.basic-auth-cache.ttl-seconds and max-entries must be positive, or the"
              + " cache must be disabled");
    }
  }

  /** Settings under which every password check runs in full. */
  public static BasicAuthCacheProperties disabled() {

    return new BasicAuthCacheProperties(false, 0, 0);
  }
}
