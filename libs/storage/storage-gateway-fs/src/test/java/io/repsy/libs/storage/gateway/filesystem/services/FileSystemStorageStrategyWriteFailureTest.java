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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.StorageUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RPS-2104: a write that the storage itself cannot carry out fails with {@link
 * StorageUnavailableException}, which the backend answers 503 with {@code Retry-After}; a failure
 * to read the client's stream keeps its {@link IOException}. The storage is made to fail by putting
 * a plain file where the object's directory has to be, which no file system lets a write go
 * through, whatever user runs the test.
 */
@DisplayName("FileSystemStorageStrategy reports a failed storage write as unavailable storage")
class FileSystemStorageStrategyWriteFailureTest {

  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  @TempDir Path tempDir;

  private UUID key;
  private StoragePath blocked;
  private FileSystemStorageStrategy strategy;

  @BeforeEach
  void setUp() throws IOException {
    final var basePath = this.tempDir.resolve("storage");
    this.strategy =
        new FileSystemStorageStrategy(
            basePath.toString(), this.tempDir.resolve("trash").toString(), Duration.ofDays(7));
    this.key = UUID.randomUUID();
    Files.createDirectories(basePath.resolve(this.key.toString()));
    Files.writeString(basePath.resolve(this.key + "/dir"), "a file, not a directory");
    this.blocked = StoragePath.of(this.key, "dir/file.bin");
  }

  @Test
  @DisplayName("write")
  void write() {
    assertThatThrownBy(
            () -> this.strategy.write("repo", this.blocked, new ByteArrayInputStream(CONTENT)))
        .isInstanceOf(StorageUnavailableException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("append")
  void append() {
    assertThatThrownBy(() -> this.strategy.append("repo", this.blocked, CONTENT))
        .isInstanceOf(StorageUnavailableException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("appendStream")
  void appendStream() {
    assertThatThrownBy(
            () ->
                this.strategy.appendStream("repo", this.blocked, new ByteArrayInputStream(CONTENT)))
        .isInstanceOf(StorageUnavailableException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("renameObject of an object that cannot be moved")
  void renameObject() {
    final var missing = StoragePath.of(this.key, "missing-upload");

    assertThatThrownBy(() -> this.strategy.renameObject(missing, "sha256:abc"))
        .isInstanceOf(StorageUnavailableException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("a client stream that fails keeps its IOException")
  void clientReadFailureIsNotAStorageOutage() {
    final var ok = StoragePath.of(this.key, "other/file.bin");
    final InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset");
          }
        };

    assertThatThrownBy(() -> this.strategy.write("repo", ok, failing))
        .isInstanceOf(IOException.class)
        .hasMessage("connection reset");
    assertThatThrownBy(() -> this.strategy.appendStream("repo", ok, failing))
        .isInstanceOf(IOException.class)
        .hasMessage("connection reset");
    assertThat(this.tempDir.resolve("storage/" + this.key + "/other/file.bin")).doesNotExist();
  }
}
