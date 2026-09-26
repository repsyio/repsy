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
package io.repsy.os.shared.configs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AppHstsProperties (RPS-1514)")
class AppHstsPropertiesTest {

  @Test
  @DisplayName("is off for zero and negative values")
  void offForZeroAndNegative() {

    assertThat(new AppHstsProperties(0).enabled()).isFalse();
    assertThat(new AppHstsProperties(-1).enabled()).isFalse();
  }

  @Test
  @DisplayName("is on for a positive max-age and renders the header value")
  void onForPositive() {

    final var properties = new AppHstsProperties(600);

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.headerValue()).isEqualTo("max-age=600");
  }
}
