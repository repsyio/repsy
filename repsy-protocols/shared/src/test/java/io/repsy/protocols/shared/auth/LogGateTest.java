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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LogGate (RPS-2335)")
class LogGateTest {

  private final AtomicLong nanos = new AtomicLong();
  private final Ticker ticker =
      new Ticker() {
        @Override
        public long read() {
          return LogGateTest.this.nanos.get();
        }
      };

  @Test
  @DisplayName("lets a key through once per interval, and keys do not share")
  void onceAMinutePerKey() {

    final var gate = new LogGate(this.ticker, 100);

    assertThat(gate.allows("a")).isTrue();
    assertThat(gate.allows("a")).isFalse();
    assertThat(gate.allows("b")).isTrue();

    this.nanos.addAndGet(TimeUnit.SECONDS.toNanos(59));
    assertThat(gate.allows("a")).isFalse();

    this.nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertThat(gate.allows("a")).isTrue();
    assertThat(gate.allows("a")).isFalse();
  }
}
