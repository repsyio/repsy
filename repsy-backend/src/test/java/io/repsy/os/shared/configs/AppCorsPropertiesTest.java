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

/**
 * RPS-1102: {@code allowedOriginList()} must resolve an unset {@code APP_ALLOWED_ORIGINS} to an
 * empty list, not a one-element list holding an empty origin, or {@code CorsGlobalConfiguration}
 * would silently open CORS to a blank origin.
 */
@DisplayName("AppCorsProperties")
class AppCorsPropertiesTest {

  @Test
  @DisplayName("allowedOriginList is empty when allowedOrigins is null")
  void nullIsEmpty() {
    assertThat(new AppCorsProperties(null).allowedOriginList()).isEmpty();
  }

  @Test
  @DisplayName("allowedOriginList is empty when allowedOrigins is blank")
  void blankIsEmpty() {
    assertThat(new AppCorsProperties("").allowedOriginList()).isEmpty();
    assertThat(new AppCorsProperties("   ").allowedOriginList()).isEmpty();
  }

  @Test
  @DisplayName("allowedOriginList splits, trims and drops blank entries")
  void splitsTrimsAndDropsBlanks() {
    assertThat(
            new AppCorsProperties(" https://a.example.com ,https://b.example.com,, ")
                .allowedOriginList())
        .containsExactly("https://a.example.com", "https://b.example.com");
  }
}
