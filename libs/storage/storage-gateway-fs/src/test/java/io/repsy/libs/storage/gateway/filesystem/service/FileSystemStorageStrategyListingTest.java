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
package io.repsy.libs.storage.gateway.filesystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.ByteArrayInputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A listing of a repo directory while files are being written into it (RPS-1188): a parallel Maven
 * deploy lists the version directory after each of its uploads, while the other uploads of the same
 * deploy write their hidden temporary files and move them into place.
 */
@DisplayName("FileSystemStorageStrategy.listStorageItems")
class FileSystemStorageStrategyListingTest {

  @TempDir Path tempDir;

  UUID key;
  FileSystemStorageStrategy strategy;

  @BeforeEach
  void setUp() {
    this.strategy =
        new FileSystemStorageStrategy(
            this.tempDir.resolve("storage").toString(),
            this.tempDir.resolve("trash").toString(),
            Duration.ofDays(7));
    this.key = UUID.randomUUID();
  }

  private void write(final String path, final String content) {
    this.strategy.write(
        "repo", StoragePath.of(this.key, path), new ByteArrayInputStream(content.getBytes()));
  }

  @Test
  @DisplayName("lists the directories and files of the tree, and no temporary file")
  void listsTheTree() {
    this.write("com/acme/lib/1.0/lib-1.0.jar", "jar");
    this.write("com/acme/lib/1.0/lib-1.0.pom", "pom");

    final var items = this.strategy.listStorageItems(StoragePath.of(this.key, "com/acme/lib/1.0"));

    assertThat(items)
        .filteredOn(item -> !item.isDirectory())
        .extracting(StorageItemInfo::getName)
        .containsExactlyInAnyOrder("lib-1.0.jar", "lib-1.0.pom");
    assertThat(items).filteredOn(StorageItemInfo::isDirectory).hasSize(1);
  }

  @Test
  @DisplayName("still fails for a directory that does not exist")
  void aMissingDirectoryStillFails() {
    final var missing = StoragePath.of(this.key, "com/acme/nothing");

    assertThatThrownBy(() -> this.strategy.listStorageItems(missing))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  @DisplayName("never fails while files are written into the directory at the same time")
  void aConcurrentWriteDoesNotBreakTheListing() {
    this.write("com/acme/lib/1.0/lib-1.0.pom", "pom");
    final var stop = new AtomicBoolean();
    final var writer =
        CompletableFuture.runAsync(
            () -> {
              for (int i = 0; i < 3_000 && !stop.get(); i++) {
                this.write("com/acme/lib/1.0/lib-1.0-" + (i % 7) + ".jar", "jar " + i);
              }
            });

    try {
      for (int i = 0; i < 3_000 && !writer.isDone(); i++) {
        final var items =
            this.strategy.listStorageItems(StoragePath.of(this.key, "com/acme/lib/1.0"));

        assertThat(items).isNotEmpty();
      }
    } finally {
      stop.set(true);
      writer.join();
    }
  }
}
