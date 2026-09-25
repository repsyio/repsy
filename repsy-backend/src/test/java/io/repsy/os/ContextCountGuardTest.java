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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContextCountGuard: the limit on distinct Spring contexts of a run")
class ContextCountGuardTest {

  @Test
  @DisplayName("configurations up to the limit are accepted")
  void acceptsConfigurationsUpToTheLimit() {
    final Set<Object> seen = new HashSet<>();

    assertThat(ContextCountGuard.record(seen, "a", 2)).isNull();
    assertThat(ContextCountGuard.record(seen, "b", 2)).isNull();
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("a configuration that is already known never counts again")
  void aKnownConfigurationIsFree() {
    final Set<Object> seen = new HashSet<>(Set.of("a", "b"));

    assertThat(ContextCountGuard.record(seen, "a", 2)).isNull();
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("the first configuration over the limit is reported with the count and the limit")
  void reportsTheConfigurationOverTheLimit() {
    final Set<Object> seen = new HashSet<>(Set.of("a", "b"));

    final var message = ContextCountGuard.record(seen, "c", 2);

    assertThat(message)
        .contains("number 3")
        .contains("limit of 2")
        .startsWith("%s adds Spring context configuration");
    assertThat(message.formatted("SomeIT")).startsWith("SomeIT adds");
  }
}
