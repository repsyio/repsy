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
package io.repsy.protocols.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class StorageStrategyRegistryTest {

  @Test
  void returnsTheStrategyOfEachRepoType() {
    final var all = new EnumMap<RepoType, StorageStrategy>(RepoType.class);
    for (final var type : RepoType.values()) {
      all.put(type, mock(StorageStrategy.class));
    }

    final var registry = new StorageStrategyRegistry(all);

    for (final var type : RepoType.values()) {
      assertThat(registry.get(type)).isSameAs(all.get(type));
    }
    assertThat(registry.asMap()).hasSize(RepoType.values().length);
  }

  @Test
  void rejectsARegistryMissingARepoType() {
    final var partial = new EnumMap<RepoType, StorageStrategy>(RepoType.class);
    partial.put(RepoType.MAVEN, mock(StorageStrategy.class));

    assertThatThrownBy(() -> new StorageStrategyRegistry(partial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No storage strategy registered for");
  }
}
