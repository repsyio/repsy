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

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Registers one file system {@link StorageStrategy} bean per {@link RepoType}, named by {@link
 * #beanName(RepoType)}, in place of a configuration class and a properties class per protocol.
 *
 * <p>The locations are read from {@code os.app.storage.file-system.<type>.base-path} and {@code
 * .trash-path}, where {@code <type>} is the lower-case {@link RepoType} name ({@code maven}, {@code
 * npm}, {@code golang}...). These keys, and the {@code STORAGE_BASE_PATH} placeholder behind their
 * defaults in {@code application.yml}, are a user contract and do not change.
 */
public class FileSystemStorageBeanRegistrar implements BeanRegistrar {
  static final String KEY_PREFIX = "os.app.storage.file-system.";

  private static final EnumMap<RepoType, String> BEAN_NAMES = new EnumMap<>(RepoType.class);

  static {
    BEAN_NAMES.put(RepoType.MAVEN, "osStorageStrategyMaven");
    BEAN_NAMES.put(RepoType.NPM, "osStorageStrategyNpm");
    BEAN_NAMES.put(RepoType.PYPI, "osStorageStrategyPypi");
    BEAN_NAMES.put(RepoType.DOCKER, "osStorageStrategyDocker");
    BEAN_NAMES.put(RepoType.CARGO, "osStorageStrategyCargo");
    BEAN_NAMES.put(RepoType.GOLANG, "osStorageStrategyGolang");
    BEAN_NAMES.put(RepoType.HELM, "osStorageStrategyHelm");
    BEAN_NAMES.put(RepoType.NUGET, "osStorageStrategyNuGet");
    BEAN_NAMES.put(RepoType.RUBY, "osStorageStrategyRuby");
  }

  /** The bean name of the strategy of {@code repoType}; tests spy the strategy by this name. */
  public static String beanName(final RepoType repoType) {
    return Objects.requireNonNull(BEAN_NAMES.get(repoType));
  }

  /** The configuration key prefix of {@code repoType}, without a trailing dot. */
  public static String keyPrefix(final RepoType repoType) {
    return KEY_PREFIX + repoType.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public void register(final BeanRegistry registry, final Environment env) {
    final var binder = Binder.get(env);

    for (final var repoType : RepoType.values()) {
      registry.registerBean(
          beanName(repoType),
          StorageStrategy.class,
          spec ->
              spec.supplier(
                  context -> {
                    final var key = keyPrefix(repoType);
                    final var paths =
                        binder
                            .bind(key, StoragePaths.class)
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        key + ".base-path and .trash-path are not configured"));
                    final var trash = context.bean(StorageTrashProperties.class);

                    return new FileSystemStorageStrategy(
                        paths.basePath(), paths.trashPath(), trash.trashRetention());
                  }));
    }
  }

  /** The two locations of one repo type, as the configuration names them. */
  record StoragePaths(String basePath, String trashPath) {}
}
