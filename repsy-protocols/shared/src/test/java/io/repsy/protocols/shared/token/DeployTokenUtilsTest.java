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

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeployTokenUtils")
class DeployTokenUtilsTest {

  @Test
  @DisplayName("a date in the past is expired")
  void pastIsExpired() {
    assertThat(DeployTokenUtils.isExpired(Instant.now().minus(Duration.ofMinutes(1)))).isTrue();
  }

  @Test
  @DisplayName("a date in the future is not expired")
  void futureIsNotExpired() {
    assertThat(DeployTokenUtils.isExpired(Instant.now().plus(Duration.ofMinutes(1)))).isFalse();
  }
}
