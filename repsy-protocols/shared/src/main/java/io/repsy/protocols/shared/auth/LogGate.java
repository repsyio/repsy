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

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;

/**
 * Lets one log line per key through per {@link #INTERVAL} (RPS-2335). The throttles in {@code
 * observe} mode note every request of a client that would have been refused; a scanner that goes on
 * for the hour of its ban would write thousands of identical lines. The metric still counts every
 * request, only the line is rationed. The keys are bounded like the throttles' own client caches.
 */
final class LogGate {

  static final Duration INTERVAL = Duration.ofMinutes(1);

  private final Cache<String, Boolean> logged;

  LogGate(final Ticker ticker, final long maxKeys) {

    this.logged =
        CacheBuilder.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterWrite(INTERVAL)
            .ticker(ticker)
            .build();
  }

  /**
   * Whether the line for the key may be written now: the first time, and then once per interval.
   */
  boolean allows(final String key) {

    return this.logged.asMap().putIfAbsent(key, Boolean.TRUE) == null;
  }
}
