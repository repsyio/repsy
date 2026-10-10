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
package io.repsy.os.server.protocols.shared.configs;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.util.EnumMap;

/** A registry that serves one strategy for every repo type, for unit tests of a single service. */
public final class TestStorageRegistries {
  private TestStorageRegistries() {}

  public static StorageStrategyRegistry of(final StorageStrategy strategy) {
    final var all = new EnumMap<RepoType, StorageStrategy>(RepoType.class);
    for (final var type : RepoType.values()) {
      all.put(type, strategy);
    }

    return new StorageStrategyRegistry(all);
  }
}
