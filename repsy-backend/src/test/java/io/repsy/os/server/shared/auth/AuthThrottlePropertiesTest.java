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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthThrottleProperties")
class AuthThrottlePropertiesTest {

  @Test
  @DisplayName("nothing set means enforce with 20 failures, a 60 second window and 10000 clients")
  void defaults() {
    final var properties = new AuthThrottleProperties(null, null, null, null, null);

    assertThat(properties.mode()).isEqualTo(AuthThrottleMode.ENFORCE);
    assertThat(properties.enabled()).isNull();
    assertThat(properties.maxFailures()).isEqualTo(20);
    assertThat(properties.windowSeconds()).isEqualTo(60);
    assertThat(properties.maxClients()).isEqualTo(10_000);
  }

  @Test
  @DisplayName("the legacy enabled=false turns the throttle off whatever the mode says")
  void legacyDisabledWinsOverMode() {
    final var properties =
        new AuthThrottleProperties(AuthThrottleMode.ENFORCE, false, null, null, null);

    assertThat(properties.mode()).isEqualTo(AuthThrottleMode.OFF);
    assertThat(properties.enabled()).isFalse();
  }

  @Test
  @DisplayName("the legacy enabled=true leaves the mode alone")
  void legacyEnabledKeepsMode() {
    final var properties =
        new AuthThrottleProperties(AuthThrottleMode.OBSERVE, true, null, null, null);

    assertThat(properties.mode()).isEqualTo(AuthThrottleMode.OBSERVE);
    assertThat(properties.enabled()).isNull();
  }

  @Test
  @DisplayName("a non-positive setting is refused unless the mode is off")
  void nonPositiveSettings() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AuthThrottleProperties(null, null, 0, null, null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AuthThrottleProperties(AuthThrottleMode.OBSERVE, null, 5, -1L, 5L));
    assertThat(new AuthThrottleProperties(AuthThrottleMode.OFF, null, 0, 0L, 0L).mode())
        .isEqualTo(AuthThrottleMode.OFF);
  }

  @Test
  @DisplayName("the factory methods set the mode and keep the numbers")
  void factories() {
    assertThat(AuthThrottleProperties.disabled().mode()).isEqualTo(AuthThrottleMode.OFF);
    assertThat(AuthThrottleProperties.enforcing(3, 30, 7).mode())
        .isEqualTo(AuthThrottleMode.ENFORCE);
    assertThat(AuthThrottleProperties.observing(3, 30, 7).maxClients()).isEqualTo(7);
  }
}
