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

package io.repsy.libs.storage.gateway.filesystem.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileSystemStorageStrategy.calculatePathUsage")
class FileSystemStorageStrategyUsageTest {

  @TempDir Path tempDir;

  FileSystemStorageStrategy strategy;

  @BeforeEach
  void setUp() {
    this.strategy =
        new FileSystemStorageStrategy(
            this.tempDir.resolve("storage").toString(),
            this.tempDir.resolve("trash").toString(),
            Duration.ofDays(7));
  }

  @Test
  @DisplayName("sums the files below a direct path that has no storage key")
  void sumsFilesBelowDirectPath() {
    this.strategy.write(
        "repo", new StoragePath("dir/a.bin"), new ByteArrayInputStream(new byte[3]));
    this.strategy.write(
        "repo", new StoragePath("dir/sub/b.bin"), new ByteArrayInputStream(new byte[4]));

    assertThat(this.strategy.calculatePathUsage(new StoragePath("dir"))).isEqualTo(7L);
  }
}
