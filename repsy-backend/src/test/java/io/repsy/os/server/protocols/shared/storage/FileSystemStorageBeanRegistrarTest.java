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
package io.repsy.os.server.protocols.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.shared.configs.ProtocolStorageConfig;
import io.repsy.os.server.protocols.shared.configs.StorageTrashProperties;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the configuration contract of the per-protocol storage: the keys {@code
 * os.app.storage.file-system.<type>.base-path} / {@code .trash-path} and the {@code
 * STORAGE_BASE_PATH} variable behind their defaults in the shipped {@code application.yml}.
 */
class FileSystemStorageBeanRegistrarTest {

  private static AnnotationConfigApplicationContext context(
      final java.util.Map<String, Object> overrides) throws Exception {
    final var context = new AnnotationConfigApplicationContext();
    final var sources = context.getEnvironment().getPropertySources();
    final var yaml =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    yaml.forEach(sources::addLast);
    sources.addFirst(new org.springframework.core.env.MapPropertySource("overrides", overrides));
    context.registerBean(
        StorageTrashProperties.class, () -> new StorageTrashProperties(Duration.ofDays(7)));
    context.register(ProtocolStorageConfig.class);
    context.refresh();

    return context;
  }

  private static Path path(final StorageStrategy strategy, final String field) {
    return (Path) ReflectionTestUtils.getField(strategy, field);
  }

  @Test
  void everyRepoTypeGetsItsOwnDirectoryUnderStorageBasePath() throws Exception {
    try (var context = context(java.util.Map.of("STORAGE_BASE_PATH", "/data/repsy"))) {
      for (final var type : RepoType.values()) {
        final var slug = type.name().toLowerCase(Locale.ROOT);
        final var strategy =
            context.getBean(FileSystemStorageBeanRegistrar.beanName(type), StorageStrategy.class);

        assertThat(path(strategy, "basePath")).isEqualTo(Path.of("/data/repsy", slug));
        assertThat(path(strategy, "trashPath")).isEqualTo(Path.of("/data/repsy", slug, "trash"));
      }
    }
  }

  @Test
  void aSingleTypeCanBeRelocatedThroughItsOwnKeys() throws Exception {
    try (var context =
        context(
            java.util.Map.of(
                "STORAGE_BASE_PATH", "/data/repsy",
                "os.app.storage.file-system.golang.base-path", "/mnt/go",
                "os.app.storage.file-system.golang.trash-path", "/mnt/go-trash"))) {
      final var go =
          context.getBean(
              FileSystemStorageBeanRegistrar.beanName(RepoType.GOLANG), StorageStrategy.class);
      final var maven =
          context.getBean(
              FileSystemStorageBeanRegistrar.beanName(RepoType.MAVEN), StorageStrategy.class);

      assertThat(path(go, "basePath")).isEqualTo(Path.of("/mnt/go"));
      assertThat(path(go, "trashPath")).isEqualTo(Path.of("/mnt/go-trash"));
      assertThat(path(maven, "basePath")).isEqualTo(Path.of("/data/repsy/maven"));
    }
  }
}
