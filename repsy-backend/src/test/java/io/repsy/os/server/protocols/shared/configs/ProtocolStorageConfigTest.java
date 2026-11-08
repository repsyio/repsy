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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy;
import io.repsy.os.server.protocols.shared.storage.FileSystemStorageBeanRegistrar;
import io.repsy.os.server.protocols.shared.storage.StorageTrashProperties;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * RPS-2580: {@code os.app.storage.backend} (env {@code STORAGE_BACKEND}) decides whether {@link
 * ProtocolStorageConfig} wires the file system strategies. The default, and the shipped {@code
 * application.yml}, keep the file system.
 */
class ProtocolStorageConfigTest {

  private static AnnotationConfigApplicationContext context(
      final Map<String, Object> overrides, final boolean withShippedYaml) throws Exception {
    final var context = new AnnotationConfigApplicationContext();
    final var sources = context.getEnvironment().getPropertySources();

    if (withShippedYaml) {
      new YamlPropertySourceLoader()
          .load("application", new ClassPathResource("application.yml"))
          .forEach(sources::addLast);
    }

    sources.addFirst(new MapPropertySource("overrides", overrides));
    context.registerBean(
        StorageTrashProperties.class, () -> new StorageTrashProperties(Duration.ofDays(7)));
    context.register(ProtocolStorageConfig.class);

    return context;
  }

  private static void assertFileSystemWiring(final AnnotationConfigApplicationContext context) {
    final var registry = context.getBean(StorageStrategyRegistry.class);

    for (final var type : RepoType.values()) {
      assertThat(registry.get(type)).isInstanceOf(FileSystemStorageStrategy.class);
      assertThat(
              context.getBean(FileSystemStorageBeanRegistrar.beanName(type), StorageStrategy.class))
          .isSameAs(registry.get(type));
    }
  }

  @Test
  @DisplayName("RPS-2580: with the shipped application.yml the file system strategies are wired")
  void shippedDefaultWiresTheFileSystem() throws Exception {
    try (var context = context(Map.of("STORAGE_BASE_PATH", "/data/repsy"), true)) {
      context.refresh();

      assertThat(context.getEnvironment().getProperty("os.app.storage.backend"))
          .isEqualTo("file-system");
      assertFileSystemWiring(context);
    }
  }

  @Test
  @DisplayName("RPS-2580: without the property (no application.yml) the file system is the default")
  void missingPropertyWiresTheFileSystem() throws Exception {
    final var overrides = new HashMap<String, Object>();
    for (final var type : RepoType.values()) {
      final var key = FileSystemStorageBeanRegistrar.keyPrefix(type);
      overrides.put(key + ".base-path", "/data/" + type.name().toLowerCase(Locale.ROOT));
      overrides.put(key + ".trash-path", "/data/trash/" + type.name().toLowerCase(Locale.ROOT));
    }

    try (var context = context(overrides, false)) {
      context.refresh();

      assertFileSystemWiring(context);
    }
  }

  @Test
  @DisplayName("RPS-2580: backend=file-system wires the file system explicitly")
  void explicitFileSystemWiresTheFileSystem() throws Exception {
    try (var context =
        context(
            Map.of("STORAGE_BASE_PATH", "/data/repsy", "os.app.storage.backend", "file-system"),
            true)) {
      context.refresh();

      assertFileSystemWiring(context);
    }
  }

  @Test
  @DisplayName("RPS-2580: any other backend wires nothing, so a plugin's registry is the only one")
  void otherBackendLeavesTheRegistryToAPlugin() throws Exception {
    final var pluginStrategy = Mockito.mock(StorageStrategy.class);
    final var pluginRegistry = new EnumMap<RepoType, StorageStrategy>(RepoType.class);
    for (final var type : RepoType.values()) {
      pluginRegistry.put(type, pluginStrategy);
    }

    try (var context =
        context(Map.of("STORAGE_BASE_PATH", "/data/repsy", "os.app.storage.backend", "s3"), true)) {
      context.registerBean(
          "pluginStorageStrategyRegistry",
          StorageStrategyRegistry.class,
          () -> new StorageStrategyRegistry(pluginRegistry));
      context.refresh();

      assertThat(context.getBeansOfType(StorageStrategyRegistry.class)).hasSize(1);
      assertThat(context.getBean(StorageStrategyRegistry.class).get(RepoType.DOCKER))
          .isSameAs(pluginStrategy);
      for (final var type : RepoType.values()) {
        assertThat(context.containsBean(FileSystemStorageBeanRegistrar.beanName(type))).isFalse();
      }
    }
  }
}
