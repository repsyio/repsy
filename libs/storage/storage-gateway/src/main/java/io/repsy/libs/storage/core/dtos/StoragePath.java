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
package io.repsy.libs.storage.core.dtos;

import java.util.UUID;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Getter
public class StoragePath {
  private final @Nullable UUID storageKey;
  private final @NonNull RelativePath relativePath;
  private final String path;

  public StoragePath(final @NonNull UUID storageKey, final @NonNull RelativePath relativePath) {
    this.storageKey = storageKey;
    this.relativePath = relativePath;

    if (relativePath.getPath().isEmpty()) {
      this.path = storageKey.toString();
    } else {
      this.path = storageKey + "/" + StringUtils.removeStart(relativePath.getPath(), '/');
    }
  }

  public StoragePath(final @NonNull String directPath) {
    this.storageKey = null;
    this.relativePath = new RelativePath(directPath);
    this.path = directPath;
  }

  public StoragePath() {
    this.storageKey = null;
    this.relativePath = new RelativePath("");
    this.path = "";
  }

  public static @NonNull StoragePath of() {
    return new StoragePath();
  }

  public static @NonNull StoragePath of(final @NonNull UUID repoUuid) {
    return StoragePath.of(repoUuid, "");
  }

  public static @NonNull StoragePath of(
      final @NonNull UUID repoUuid, final @NonNull String relativePath) {
    return new StoragePath(repoUuid, new RelativePath(relativePath));
  }

  public static @NonNull StoragePath ofPath(final @NonNull String directPath) {
    return new StoragePath(directPath);
  }
}
