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
package io.repsy.protocols.shared.dtos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolErrorBody")
class ProtocolErrorBodyTest {

  @Test
  @DisplayName("withDetail() is Cargo's {\"errors\":[{\"detail\":...}]}")
  void cargoShape() {
    assertThat(ProtocolErrorBody.withDetail("crate not found").errors())
        .containsExactly(Map.of("detail", "crate not found"));
  }

  @Test
  @DisplayName("withMessage() is NuGet's {\"errors\":[{\"message\":...}]}")
  void nugetShape() {
    assertThat(ProtocolErrorBody.withMessage("Publish failed").errors())
        .containsExactly(Map.of("message", "Publish failed"));
  }

  @Test
  @DisplayName("a missing text is kept as null, as the former records did")
  void keepsNull() {
    assertThat(ProtocolErrorBody.withDetail(null).errors()).hasSize(1);
    assertThat(ProtocolErrorBody.withDetail(null).errors().getFirst())
        .containsEntry("detail", null);
  }

  @Test
  @DisplayName("two bodies with the same text are equal")
  void equality() {
    assertThat(ProtocolErrorBody.withMessage("x")).isEqualTo(ProtocolErrorBody.withMessage("x"));
  }
}
