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

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The storage strategy of every repo type, looked up by {@link RepoType} instead of by a bean name.
 * It always holds one strategy per type: construction fails when one is missing, so a new {@link
 * RepoType} cannot be added without its storage.
 */
public final class StorageStrategyRegistry {
  private final Map<RepoType, StorageStrategy> strategies;

  public StorageStrategyRegistry(final Map<RepoType, StorageStrategy> strategies) {
    final var copy = new EnumMap<RepoType, StorageStrategy>(RepoType.class);
    copy.putAll(strategies);

    for (final var type : RepoType.values()) {
      if (copy.get(type) == null) {
        throw new IllegalArgumentException("No storage strategy registered for " + type);
      }
    }

    this.strategies = Map.copyOf(copy);
  }

  public StorageStrategy get(final RepoType repoType) {
    return Objects.requireNonNull(this.strategies.get(repoType));
  }

  /** All strategies, one per repo type. */
  public Map<RepoType, StorageStrategy> asMap() {
    return this.strategies;
  }
}
