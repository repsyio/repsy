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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileSystemStorageStrategy.write is atomic")
class FileSystemStorageStrategyAtomicWriteTest {

  private static final String TEMP_PREFIX = ".repsy-write-";

  @TempDir Path tempDir;

  Path basePath;
  Path trashPath;
  UUID key;
  StoragePath storagePath;
  Path target;

  FileSystemStorageStrategy strategy;

  @BeforeEach
  void setUp() {
    this.basePath = this.tempDir.resolve("storage");
    this.trashPath = this.tempDir.resolve("trash");
    this.strategy =
        new FileSystemStorageStrategy(
            this.basePath.toString(), this.trashPath.toString(), Duration.ofDays(7));
    this.key = UUID.randomUUID();
    this.storagePath = StoragePath.of(this.key, "dir/file.bin");
    this.target = this.basePath.resolve(this.key + "/dir/file.bin");
  }

  /** Serves {@code head}, then blocks until released, then fails or ends as told. */
  private static final class GatedInputStream extends InputStream {
    private final ByteArrayInputStream head;
    private final CountDownLatch headRead = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final boolean failAfterRelease;

    GatedInputStream(final byte[] head, final boolean failAfterRelease) {
      this.head = new ByteArrayInputStream(head);
      this.failAfterRelease = failAfterRelease;
    }

    @Override
    public int read() throws IOException {
      final int next = this.head.read();

      if (next >= 0) {
        return next;
      }

      this.headRead.countDown();

      try {
        this.release.await(10, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }

      if (this.failAfterRelease) {
        throw new IOException("connection reset");
      }

      return -1;
    }

    @Override
    public int read(final byte[] buffer, final int off, final int len) throws IOException {
      final int next = this.read();

      if (next < 0) {
        return -1;
      }

      buffer[off] = (byte) next;
      return 1;
    }

    void awaitHeadRead() throws InterruptedException {
      assertThat(this.headRead.await(10, TimeUnit.SECONDS)).isTrue();
    }

    void release() {
      this.release.countDown();
    }
  }

  private static InputStream failingAfter(final byte[] head, final RuntimeException runtime) {
    return new InputStream() {
      private final ByteArrayInputStream in = new ByteArrayInputStream(head);

      @Override
      public int read() throws IOException {
        final int next = this.in.read();

        if (next >= 0) {
          return next;
        }

        if (runtime != null) {
          throw runtime;
        }

        throw new IOException("connection reset");
      }
    };
  }

  private void seed(final byte[] content) throws IOException {
    Files.createDirectories(this.target.getParent());
    Files.write(this.target, content);
  }

  private List<String> directoryNames() throws IOException {
    try (final Stream<Path> files = Files.list(this.target.getParent())) {
      return files.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  private static byte[] bytes(final String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a failure halfway leaves the previous bytes and no temporary file")
  void failureKeepsTheOldFile() throws Exception {
    final byte[] old = bytes("previous content");
    this.seed(old);

    assertThatThrownBy(
            () ->
                this.strategy.write("repo", this.storagePath, failingAfter(bytes("partial"), null)))
        .isInstanceOf(IOException.class)
        .hasMessage("connection reset");

    assertThat(Files.readAllBytes(this.target)).isEqualTo(old);
    assertThat(this.directoryNames()).containsExactly("file.bin");
  }

  @Test
  @DisplayName("a runtime failure halfway leaves the previous bytes and no temporary file")
  void runtimeFailureKeepsTheOldFile() throws Exception {
    final byte[] old = bytes("previous content");
    this.seed(old);

    assertThatThrownBy(
            () ->
                this.strategy.write(
                    "repo",
                    this.storagePath,
                    failingAfter(bytes("partial"), new IllegalStateException("boom"))))
        .isInstanceOf(IllegalStateException.class);

    assertThat(Files.readAllBytes(this.target)).isEqualTo(old);
    assertThat(this.directoryNames()).containsExactly("file.bin");
  }

  @Test
  @DisplayName("a failure on a new object leaves neither the object nor a temporary file")
  void failureOnANewObjectLeavesNothing() throws Exception {
    assertThatThrownBy(
            () ->
                this.strategy.write("repo", this.storagePath, failingAfter(bytes("partial"), null)))
        .isInstanceOf(IOException.class);

    assertThat(this.target).doesNotExist();
    assertThat(this.directoryNames()).isEmpty();
    assertThat(this.strategy.get(this.storagePath, "repo")).isEmpty();
  }

  @Test
  @DisplayName("a success replaces the content, leaves no temporary file and reports the delta")
  void successReplacesTheFile() throws Exception {
    final byte[] old = bytes("old");
    final byte[] updated = bytes("brand new content");
    this.seed(old);

    final var usages =
        this.strategy.write("repo", this.storagePath, new ByteArrayInputStream(updated));

    assertThat(Files.readAllBytes(this.target)).isEqualTo(updated);
    assertThat(this.directoryNames()).containsExactly("file.bin");
    assertThat(usages.getDiskUsage()).isEqualTo(updated.length - old.length);
  }

  @Test
  @DisplayName("an empty stream replaces the object with an empty file")
  void emptyStreamTruncates() throws Exception {
    this.seed(bytes("old"));

    final var usages =
        this.strategy.write("repo", this.storagePath, new ByteArrayInputStream(new byte[0]));

    assertThat(this.target).isEmptyFile();
    assertThat(usages.getDiskUsage()).isEqualTo(-3);
  }

  @Test
  @DisplayName("the previous permissions of the object are kept")
  void keepsThePermissions() throws Exception {
    this.seed(bytes("old"));
    Files.setPosixFilePermissions(this.target, PosixFilePermissions.fromString("rw-r-----"));

    this.strategy.write("repo", this.storagePath, new ByteArrayInputStream(bytes("new")));

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(this.target)))
        .isEqualTo("rw-r-----");
  }

  @Test
  @DisplayName("a symbolic link as the target is replaced, not written through")
  void replacesASymbolicLink() throws Exception {
    final Path outside = this.tempDir.resolve("outside.bin");
    Files.write(outside, bytes("outside"));
    Files.createDirectories(this.target.getParent());
    Files.createSymbolicLink(this.target, outside);

    this.strategy.write("repo", this.storagePath, new ByteArrayInputStream(bytes("new")));

    assertThat(Files.readAllBytes(outside)).isEqualTo(bytes("outside"));
    assertThat(Files.isSymbolicLink(this.target)).isFalse();
    assertThat(Files.readAllBytes(this.target)).isEqualTo(bytes("new"));
  }

  @Test
  @DisplayName(
      "a reader during a write sees the previous content, and the temporary file is hidden")
  void readersSeeTheOldFileAndTheTemporaryFileIsHidden() throws Exception {
    final byte[] old = bytes("previous content");
    this.seed(old);
    final var gated = new GatedInputStream(bytes("half of the new con"), false);

    final var write =
        CompletableFuture.supplyAsync(() -> this.strategy.write("repo", this.storagePath, gated));
    gated.awaitHeadRead();

    final List<String> names = this.directoryNames();
    assertThat(names).hasSize(2).contains("file.bin");
    final String tempName = names.stream().filter(n -> n.startsWith(TEMP_PREFIX)).findFirst().get();

    // a partial file is never visible under the target name
    assertThat(Files.readAllBytes(this.target)).isEqualTo(old);

    // the temporary file is in no listing, no usage figure and is not served
    final StoragePath directory = StoragePath.of(this.key, "dir");
    assertThat(this.strategy.listDirectoryContents(directory))
        .extracting(item -> item.getName())
        .containsExactly("file.bin");
    assertThat(this.strategy.listStorageItems(StoragePath.of(this.key)))
        .extracting(item -> item.getName())
        .containsExactlyInAnyOrder(this.key.toString(), "dir", "file.bin");
    assertThat(this.strategy.listStorageItems(directory))
        .extracting(item -> item.getName())
        .containsExactlyInAnyOrder("dir", "file.bin");
    assertThat(this.strategy.listStaleFiles(directory, Instant.now().plusSeconds(60)))
        .extracting(stale -> stale.name())
        .containsExactly("file.bin");
    assertThat(this.strategy.calculatePathUsage(StoragePath.of(this.key))).isEqualTo(old.length);
    assertThat(this.strategy.getFileUsage(this.storagePath, "repo")).isEqualTo(old.length);
    assertThat(this.strategy.get(StoragePath.of(this.key, "dir/" + tempName), "repo")).isEmpty();

    gated.release();
    final var usages = write.get(10, TimeUnit.SECONDS);

    assertThat(Files.readAllBytes(this.target)).isEqualTo(bytes("half of the new con"));
    assertThat(this.directoryNames()).containsExactly("file.bin");
    assertThat(usages.getDiskUsage()).isEqualTo("half of the new con".length() - old.length);
  }

  @Test
  @DisplayName("a failure while a reader looks leaves the previous content and cleans up")
  void failureAfterTheHeadWasWritten() throws Exception {
    final byte[] old = bytes("previous content");
    this.seed(old);
    final var gated = new GatedInputStream(bytes("partial"), true);

    final var write =
        CompletableFuture.supplyAsync(() -> this.strategy.write("repo", this.storagePath, gated));
    gated.awaitHeadRead();

    assertThat(Files.readAllBytes(this.target)).isEqualTo(old);

    gated.release();

    assertThatThrownBy(() -> write.get(10, TimeUnit.SECONDS))
        .hasRootCauseMessage("connection reset");
    assertThat(Files.readAllBytes(this.target)).isEqualTo(old);
    assertThat(this.directoryNames()).containsExactly("file.bin");
  }

  @Test
  @DisplayName("concurrent readers only ever see a complete old or a complete new file")
  void concurrentReadersNeverSeeAPartialFile() throws Exception {
    final byte[] first = new byte[512 * 1024];
    final byte[] second = new byte[768 * 1024];
    Arrays.fill(first, (byte) 'a');
    Arrays.fill(second, (byte) 'b');
    this.seed(first);

    final AtomicBoolean done = new AtomicBoolean();
    final AtomicReference<String> violation = new AtomicReference<>();

    final var reader =
        CompletableFuture.runAsync(
            () -> {
              while (!done.get() && violation.get() == null) {
                try {
                  final byte[] seen = Files.readAllBytes(this.target);
                  if (!Arrays.equals(seen, first) && !Arrays.equals(seen, second)) {
                    violation.set("read " + seen.length + " bytes");
                  }
                } catch (final IOException e) {
                  violation.set(e.toString());
                }
              }
            });

    for (int i = 0; i < 100; i++) {
      final byte[] next = i % 2 == 0 ? second : first;
      this.strategy.write("repo", this.storagePath, new ByteArrayInputStream(next));
    }

    done.set(true);
    reader.get(10, TimeUnit.SECONDS);

    assertThat(violation.get()).isNull();
    assertThat(this.directoryNames()).containsExactly("file.bin");
  }

  @Test
  @DisplayName("a reserved temporary file name is not accepted as a target")
  void refusesTheTemporaryFileName() {
    final var reserved = StoragePath.of(this.key, "dir/" + TEMP_PREFIX + "x.tmp");

    assertThatThrownBy(
            () -> this.strategy.write("repo", reserved, new ByteArrayInputStream(bytes("x"))))
        .isInstanceOf(InvalidStoragePathException.class);
  }

  @Test
  @DisplayName("clearTrash removes temporary files left by a crash, once they are a day old")
  void clearTrashRemovesOrphanedTemporaryFiles() throws Exception {
    this.seed(bytes("kept"));
    final Path dir = this.target.getParent();
    final Path stale = Files.write(dir.resolve(TEMP_PREFIX + "stale.tmp"), bytes("partial"));
    final Path fresh = Files.write(dir.resolve(TEMP_PREFIX + "fresh.tmp"), bytes("partial"));
    final Path lookalike = Files.write(dir.resolve("stale.tmp"), bytes("real"));
    final FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
    Files.setLastModifiedTime(stale, old);
    Files.setLastModifiedTime(lookalike, old);
    Files.setLastModifiedTime(this.target, old);

    this.strategy.clearTrash().get(10, TimeUnit.SECONDS);

    assertThat(stale).doesNotExist();
    assertThat(fresh).exists();
    assertThat(lookalike).exists();
    assertThat(this.target).exists();
  }
}
