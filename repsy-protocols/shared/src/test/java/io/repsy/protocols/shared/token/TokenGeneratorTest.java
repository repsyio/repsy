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
package io.repsy.protocols.shared.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenGenerator")
class TokenGeneratorTest {

  @Test
  @DisplayName("is the prefix followed by 43 URL-safe characters (a SHA-256 digest, unpadded)")
  void format() {
    assertThat(TokenGenerator.generate("rdt-")).matches("rdt-[A-Za-z0-9_-]{43}");
    assertThat(TokenGenerator.generate("rut-")).matches("rut-[A-Za-z0-9_-]{43}");
  }

  @Test
  @DisplayName("two tokens differ")
  void tokensAreDistinct() {
    assertThat(TokenGenerator.generate("rdt-")).isNotEqualTo(TokenGenerator.generate("rdt-"));
  }
}
