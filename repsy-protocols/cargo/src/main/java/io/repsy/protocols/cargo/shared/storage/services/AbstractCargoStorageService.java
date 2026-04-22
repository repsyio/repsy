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
package io.repsy.protocols.cargo.shared.storage.services;

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractCargoStorageService implements CargoStorageService {

  private static final int ONE = 1;
  private static final int TWO = 2;
  private static final int THREE = 3;
  private static final String CRATES_PATH = "crates";
  private static final String CRATES_FILE_NAME_FMT = "%s-%s.crate";
  private final StorageStrategy storageStrategy;

  @Override
  public BaseUsages writeCrateAndIndex(
      final UUID repoId,
      final String repoName,
      final String crateName,
      final String versionName,
      final byte[] crateBytes,
      final String indexJsonLine)
      throws IOException {

    final var crateFileName = String.format(CRATES_FILE_NAME_FMT, crateName, versionName);
    final var cratePath = Paths.get(CRATES_PATH, crateName, crateFileName);
    final var crateStoragePath = StoragePath.of(repoId, cratePath.toString());

    final BaseUsages crateUsages;
    try (final var bis = new ByteArrayInputStream(crateBytes)) {
      crateUsages = this.storageStrategy.write(repoName, crateStoragePath, bis);
    }

    // Append Mode for Index file
    final var indexPath = this.getIndexPath(crateName);
    final var indexStoragePath = StoragePath.of(repoId, indexPath.toString());

    final var indexLine = (indexJsonLine + "\n").getBytes(StandardCharsets.UTF_8);
    final var indexUsages = this.storageStrategy.append(repoName, indexStoragePath, indexLine);

    crateUsages.setDiskUsage(crateUsages.getDiskUsage() + indexUsages.getDiskUsage());
    return crateUsages;
  }

  @Override
  public Resource getCrate(
      final UUID repoId, final String repoName, final String crateName, final String versionName) {

    final var crateFileName = String.format(CRATES_FILE_NAME_FMT, crateName, versionName);
    final var cratePath = Paths.get(CRATES_PATH, crateName, crateFileName);
    final var storagePath = StoragePath.of(repoId, cratePath.toString());

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException("crateNotFound"));
  }

  @Override
  public long deleteCrate(
      final UUID repoId, final String repoName, final String crateName, final String versionName)
      throws IOException {

    final var crateFileName = String.format(CRATES_FILE_NAME_FMT, crateName, versionName);
    final var cratePath = Paths.get(CRATES_PATH, crateName, crateFileName);
    final var storagePath = StoragePath.of(repoId, cratePath.toString());

    final var usage = this.storageStrategy.getFileUsage(storagePath, repoName);
    this.storageStrategy.delete(storagePath);

    return usage;
  }

  @Override
  public long deletePackage(final UUID repoId, final String repoName, final String crateName) {

    final var cratePath = Paths.get(CRATES_PATH, crateName);
    final var indexPath = this.getIndexPath(crateName);

    final var crateStoragePath = StoragePath.of(repoId, cratePath.toString());
    final var indexStoragePath = StoragePath.of(repoId, indexPath.toString());

    try {
      final var crateUsage = this.storageStrategy.calculatePathUsage(crateStoragePath);
      final var indexUsage = this.storageStrategy.getFileUsage(indexStoragePath, repoName);

      this.storageStrategy.deleteDirectory(crateStoragePath);
      this.storageStrategy.delete(indexStoragePath);

      return crateUsage + indexUsage;
    } catch (final IOException e) {
      throw new ErrorOccurredException("IOException Occurred! ", e);
    }
  }

  @Override
  public long rewriteIndex(
      final UUID repoId,
      final String repoName,
      final String crateName,
      final List<String> jsonLines)
      throws IOException {

    final var indexPath = this.getIndexPath(crateName);
    final var indexStoragePath = StoragePath.of(repoId, indexPath.toString());

    if (jsonLines.isEmpty()) {
      final var indexSize = this.storageStrategy.getFileUsage(indexStoragePath, repoName);
      this.storageStrategy.delete(indexStoragePath);
      return -1L * indexSize;
    }

    final var content = String.join("\n", jsonLines) + "\n";
    final var indexData = content.getBytes(StandardCharsets.UTF_8);

    try (final var bis = new ByteArrayInputStream(indexData)) {
      return this.storageStrategy.write(repoName, indexStoragePath, bis).getDiskUsage();
    }
  }

  @Override
  public void createRepo(final UUID repoId) {

    this.storageStrategy.createDirectory(repoId.toString());
  }

  @Override
  public long deleteRepo(final UUID repoUuid) {

    final var storagePath = StoragePath.of(repoUuid);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  private Path getIndexPath(final String name) {

    final var len = name.length();
    final var basePath = Paths.get("index");

    if (len == ONE) {
      return basePath.resolve("1").resolve(name);
    }

    if (len == TWO) {
      return basePath.resolve("2").resolve(name);
    }

    if (len == THREE) {
      return basePath.resolve("3").resolve(name.substring(0, 1)).resolve(name);
    }

    final var part1 = name.substring(0, 2);
    final var part2 = name.substring(2, 4);

    return basePath.resolve(part1).resolve(part2).resolve(name);
  }
}
