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
package io.repsy.os.server.shared.token.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeployTokenHash")
class DeployTokenHashTest {

  @Test
  @DisplayName("is SHA-256 as lower-case hex: the published test vectors")
  void isSha256() {
    assertThat(DeployTokenHash.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(DeployTokenHash.hash(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("is unsalted and case sensitive, so a stored token can be looked up by its hash")
  void isDeterministic() {
    assertThat(DeployTokenHash.hash("rdt-abc")).isEqualTo(DeployTokenHash.hash("rdt-abc"));
    assertThat(DeployTokenHash.hash("rdt-abc")).isNotEqualTo(DeployTokenHash.hash("rdt-ABC"));
    assertThat(DeployTokenHash.hash("rdt-abc")).matches("[0-9a-f]{64}");
  }
}
