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
package io.repsy.os.server.security.shared.configs;

import io.repsy.libs.storage.core.services.StorageStrategy;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges each protocol's own {@code osStorageStrategy<Protocol>}-qualified {@link StorageStrategy}
 * bean into a single map keyed by {@code RepoType.name()} (e.g. "MAVEN"), so the scanner package
 * can resolve the right storage backend for an {@link io.repsy.core.events.ArtifactPushedEvent}
 * without depending on {@code RepoType} itself.
 */
@Configuration
public class ScannerStorageConfig {

  @Bean("storageStrategiesByRepoType")
  public @NonNull Map<String, StorageStrategy> storageStrategiesByRepoType(
      final @Qualifier("osStorageStrategyMaven") @NonNull StorageStrategy mavenStorageStrategy,
      final @Qualifier("osStorageStrategyNpm") @NonNull StorageStrategy npmStorageStrategy,
      final @Qualifier("osStorageStrategyPypi") @NonNull StorageStrategy pypiStorageStrategy,
      final @Qualifier("osStorageStrategyDocker") @NonNull StorageStrategy dockerStorageStrategy,
      final @Qualifier("osStorageStrategyCargo") @NonNull StorageStrategy cargoStorageStrategy,
      final @Qualifier("osStorageStrategyGolang") @NonNull StorageStrategy golangStorageStrategy,
      final @Qualifier("osStorageStrategyHelm") @NonNull StorageStrategy helmStorageStrategy,
      final @Qualifier("osStorageStrategyNuGet") @NonNull StorageStrategy nuGetStorageStrategy,
      final @Qualifier("osStorageStrategyRuby") @NonNull StorageStrategy rubyStorageStrategy) {

    return Map.ofEntries(
        Map.entry("MAVEN", mavenStorageStrategy),
        Map.entry("NPM", npmStorageStrategy),
        Map.entry("PYPI", pypiStorageStrategy),
        Map.entry("DOCKER", dockerStorageStrategy),
        Map.entry("CARGO", cargoStorageStrategy),
        Map.entry("GOLANG", golangStorageStrategy),
        Map.entry("HELM", helmStorageStrategy),
        Map.entry("NUGET", nuGetStorageStrategy),
        Map.entry("RUBY", rubyStorageStrategy));
  }
}
