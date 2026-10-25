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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("SuspiciousRequestProperties")
class SuspiciousRequestPropertiesTest {

  private static SuspiciousRequestProperties bind(final Map<String, String> source) {

    return new Binder(new MapConfigurationPropertySource(source))
        .bind("repsy.security.probe-throttle", SuspiciousRequestProperties.class)
        .orElseGet(() -> new SuspiciousRequestProperties(null, null, null, null, null, null, null));
  }

  @Test
  @DisplayName("nothing set means the enforcing defaults")
  void defaults() {

    final var properties = bind(Map.of());

    assertThat(properties.mode()).isEqualTo(AuthThrottleMode.ENFORCE);
    assertThat(properties.maxStrikes()).isEqualTo(10);
    assertThat(properties.windowSeconds()).isEqualTo(60);
    assertThat(properties.banSeconds()).isEqualTo(3600);
    assertThat(properties.allowlist()).isEmpty();
    assertThat(properties.weight(SignalFamily.TRAVERSAL)).isEqualTo(5);
    assertThat(properties.weight(SignalFamily.METHOD_ABUSE)).isEqualTo(3);
  }

  @Test
  @DisplayName("a family can be weighted or switched off by its kebab-case key")
  void familiesAreConfigurable() {

    final var properties =
        bind(
            Map.of(
                "repsy.security.probe-throttle.mode", "observe",
                "repsy.security.probe-throttle.allowlist", "10.0.0.0/8,192.0.2.1",
                "repsy.security.probe-throttle.signals.secret-path.weight", "7",
                "repsy.security.probe-throttle.signals.null-byte.enabled", "false"));

    assertThat(properties.mode()).isEqualTo(AuthThrottleMode.OBSERVE);
    assertThat(properties.allowlist()).containsExactly("10.0.0.0/8", "192.0.2.1");
    assertThat(properties.weight(SignalFamily.SECRET_PATH)).isEqualTo(7);
    assertThat(properties.weight(SignalFamily.NULL_BYTE)).isZero();
    assertThat(properties.weight(SignalFamily.TRAVERSAL)).isEqualTo(5);
  }

  @Test
  @DisplayName("a non-positive limit is refused unless the mode is off")
  void nonPositiveLimitIsRefused() {

    assertThatThrownBy(() -> bind(Map.of("repsy.security.probe-throttle.max-strikes", "0")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThat(
            bind(Map.of(
                    "repsy.security.probe-throttle.mode", "off",
                    "repsy.security.probe-throttle.max-strikes", "0"))
                .mode())
        .isEqualTo(AuthThrottleMode.OFF);
  }

  @Test
  @DisplayName("a negative weight is refused")
  void negativeWeightIsRefused() {

    assertThatThrownBy(
            () -> bind(Map.of("repsy.security.probe-throttle.signals.traversal.weight", "-1")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
