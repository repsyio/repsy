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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.dtos.TrashCleanupResult;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("FileSystemStorageStrategy")
class FileSystemStorageStrategyIT {

  @TempDir Path tempDir;

  Path basePath;
  Path trashPath;

  FileSystemStorageStrategy strategy;

  @BeforeEach
  void setUp() {
    this.basePath = this.tempDir.resolve("storage");
    this.trashPath = this.tempDir.resolve("trash");
    this.strategy =
        new FileSystemStorageStrategy(
            this.basePath.toString(), this.trashPath.toString(), Duration.ofDays(7));
  }

  @Nested
  @DisplayName("get()")
  class Get {

    @Test
    @DisplayName("returns empty Optional when file does not exist")
    void returnEmptyOptionalWhenFileMissing() {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "missing.txt");

      final var result = FileSystemStorageStrategyIT.this.strategy.get(sp, "repo");

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns Resource when file exists")
    void returnResourceWhenFileExists() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/hello.txt", "content");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "hello.txt");

      final var result = FileSystemStorageStrategyIT.this.strategy.get(sp, "repo");

      assertThat(result).isPresent();
      assertThat(result.get().exists()).isTrue();
    }

    @Test
    @DisplayName("throws IsADirectoryException when path points to a directory")
    void throwExceptionWhenPathIsDirectory() throws Exception {
      final var key = UUID.randomUUID();
      final var dirPath = FileSystemStorageStrategyIT.this.basePath.resolve(key + "/subdir");
      Files.createDirectories(dirPath);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "subdir");

      assertThatThrownBy(() -> FileSystemStorageStrategyIT.this.strategy.get(sp, "repo"))
          .isInstanceOf(IsADirectoryException.class);
    }
  }

  @Nested
  @DisplayName("write()")
  class Write {

    @Test
    @DisplayName("creates file and returns correct inbound traffic usage for new file")
    void writeNewFileWhenFileDoesNotExist() throws Exception {
      final var key = UUID.randomUUID();
      final var content = "hello world".getBytes(StandardCharsets.UTF_8);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "data/file.txt");

      final var usages =
          FileSystemStorageStrategyIT.this.strategy.write(
              "repo", sp, new ByteArrayInputStream(content));

      final var written = FileSystemStorageStrategyIT.this.basePath.resolve(key + "/data/file.txt");

      assertThat(written).exists();
      assertThat(Files.readAllBytes(written)).isEqualTo(content);
      assertThat(usages.getDiskUsage()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("overwrites existing file and diskUsage reflects the delta")
    void overwriteFileWhenFileAlreadyExists() throws Exception {
      final var key = UUID.randomUUID();
      final var old = "old".getBytes(StandardCharsets.UTF_8);
      final var updated = "updated content".getBytes(StandardCharsets.UTF_8);
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", old);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "file.txt");

      final var usages =
          FileSystemStorageStrategyIT.this.strategy.write(
              "repo", sp, new ByteArrayInputStream(updated));

      // diskUsage = newSize - oldSize
      assertThat(usages.getDiskUsage()).isEqualTo(updated.length - old.length);
    }

    @Test
    @DisplayName("creates parent directories automatically when they do not exist")
    void createParentDirectoriesWhenTheyDoNotExist() {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "a/b/c/file.txt");

      FileSystemStorageStrategyIT.this.strategy.write(
          "repo", sp, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/a/b/c/file.txt"))
          .exists();
    }
  }

  @Nested
  @DisplayName("appendStream()")
  class AppendStream {

    private InputStream failingAfter(final byte[] head) {
      return new SequenceInputStream(
          new ByteArrayInputStream(head),
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("connection reset");
            }
          });
    }

    @Test
    @DisplayName("creates the file, and its directories, when it does not exist")
    void createsTheFile() throws Exception {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "oci/blobs/upload");

      final var usages =
          FileSystemStorageStrategyIT.this.strategy.appendStream(
              "repo", sp, new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));

      final var file = FileSystemStorageStrategyIT.this.basePath.resolve(key + "/oci/blobs/upload");
      assertThat(Files.readString(file)).isEqualTo("first");
      assertThat(usages.getDiskUsage()).isEqualTo(5);
    }

    @Test
    @DisplayName("adds each chunk after the earlier ones and reports the bytes it added")
    void appendsChunksInOrder() throws Exception {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload");
      final var strategy = FileSystemStorageStrategyIT.this.strategy;

      final var first = strategy.appendStream("repo", sp, chunk("first-"));
      final var second = strategy.appendStream("repo", sp, chunk("second-"));
      final var third = strategy.appendStream("repo", sp, chunk("third"));

      final var file = FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload");
      assertThat(Files.readString(file)).isEqualTo("first-second-third");
      assertThat(first.getDiskUsage() + second.getDiskUsage() + third.getDiskUsage())
          .isEqualTo(Files.size(file));
      assertThat(strategy.getFileUsage(sp, "repo")).isEqualTo(Files.size(file));
    }

    @Test
    @DisplayName("appends an empty chunk without changing the file")
    void appendsAnEmptyChunk() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/upload", "kept");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload");

      final var usages =
          FileSystemStorageStrategyIT.this.strategy.appendStream("repo", sp, chunk(""));

      assertThat(usages.getDiskUsage()).isZero();
      assertThat(
              Files.readString(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload")))
          .isEqualTo("kept");
    }

    @Test
    @DisplayName("leaves the file as it was when the chunk fails halfway")
    void restoresTheFileWhenTheCopyFails() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/upload", "kept");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload");
      final var head = "partial".getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(
              () ->
                  FileSystemStorageStrategyIT.this.strategy.appendStream(
                      "repo", sp, this.failingAfter(head)))
          .isInstanceOf(IOException.class)
          .hasMessage("connection reset");

      assertThat(
              Files.readString(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload")))
          .isEqualTo("kept");
    }

    @Test
    @DisplayName("leaves no file behind when the first chunk fails halfway")
    void removesTheFileWhenTheFirstChunkFails() {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload");
      final var head = "partial".getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(
              () ->
                  FileSystemStorageStrategyIT.this.strategy.appendStream(
                      "repo", sp, this.failingAfter(head)))
          .isInstanceOf(IOException.class);

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload")).doesNotExist();
    }

    private ByteArrayInputStream chunk(final String content) {
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Nested
  @DisplayName("listDirectoryContents()")
  class ListDirectoryContents {

    @Test
    @DisplayName("returns directories first, then files, both sorted alphabetically")
    void returnDirectoriesBeforeFilesWhenListingDirectory() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/beta.txt", "b");
      FileSystemStorageStrategyIT.this.seedFile(key + "/alpha.txt", "a");
      Files.createDirectories(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/zdir"));
      Files.createDirectories(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/adir"));

      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "");
      final var items = FileSystemStorageStrategyIT.this.strategy.listDirectoryContents(sp);

      // directories come first
      assertThat(items.get(0).isDirectory()).isTrue();
      assertThat(items.get(1).isDirectory()).isTrue();
      // then files
      assertThat(items.get(2).isDirectory()).isFalse();
      assertThat(items.get(3).isDirectory()).isFalse();

      // directory names have trailing slash
      assertThat(items.get(0).getName()).isEqualTo("adir/");
      assertThat(items.get(1).getName()).isEqualTo("zdir/");

      // files sorted alphabetically
      assertThat(items.get(2).getName()).isEqualTo("alpha.txt");
      assertThat(items.get(3).getName()).isEqualTo("beta.txt");
    }

    @Test
    @DisplayName("throws ItemNotFoundException when path is not a directory")
    void throwExceptionWhenPathIsNotDirectory() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", "data");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "file.txt");

      assertThatThrownBy(() -> FileSystemStorageStrategyIT.this.strategy.listDirectoryContents(sp))
          .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @DisplayName("returns empty list when directory exists but is empty")
    void returnEmptyListWhenDirectoryIsEmpty() throws Exception {
      final var key = UUID.randomUUID();
      Files.createDirectories(FileSystemStorageStrategyIT.this.basePath.resolve(key.toString()));
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "");

      final var items = FileSystemStorageStrategyIT.this.strategy.listDirectoryContents(sp);

      assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("excludes trashPath from listing")
    void excludeTrashDirectoryWhenListingContents() throws Exception {
      // Put the trashPath inside basePath so it would appear in a listing
      final var trashInsideBase = FileSystemStorageStrategyIT.this.basePath.resolve("trash");
      Files.createDirectories(trashInsideBase);
      FileSystemStorageStrategyIT.this.seedFile("someFile.txt", "x");

      // Re-create strategy with trash nested under basePath
      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              trashInsideBase.toString(),
              Duration.ofDays(1));

      final var sp =
          FileSystemStorageStrategyIT.this.directPath(
              FileSystemStorageStrategyIT.this.basePath.toString());
      final var items = localStrategy.listDirectoryContents(sp);

      assertThat(items).noneMatch(i -> i.getName().equals("trash/"));
    }
  }

  @Nested
  @DisplayName("listStorageItems()")
  class ListStorageItems {

    @Test
    @DisplayName("lists items using Files.walk when storageKey is present")
    void listItemsRecursivelyWhenStorageKeyIsPresent() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/a.txt", "a");
      FileSystemStorageStrategyIT.this.seedFile(key + "/sub/b.txt", "b");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "");

      final var items = FileSystemStorageStrategyIT.this.strategy.listStorageItems(sp);

      // Files.walk includes the root dir itself + 2 files + 1 subdir = 4 entries
      final var names = items.stream().map(StorageItemInfo::getName).toList();
      assertThat(names).contains("a.txt", "b.txt");
    }

    @Test
    @DisplayName("lists items using Files.list (non-recursive) when storageKey is null")
    void listItemsNonRecursivelyWhenStorageKeyIsNull() throws Exception {
      Files.createDirectories(FileSystemStorageStrategyIT.this.basePath);
      FileSystemStorageStrategyIT.this.seedFile("top.txt", "t");
      FileSystemStorageStrategyIT.this.seedFile("nested/deep.txt", "d");

      final var sp =
          FileSystemStorageStrategyIT.this.directPath(
              FileSystemStorageStrategyIT.this.basePath.toString());
      final var items = FileSystemStorageStrategyIT.this.strategy.listStorageItems(sp);

      final var names = items.stream().map(StorageItemInfo::getName).toList();
      // shallow: only top-level entries
      assertThat(names).contains("top.txt", "nested").doesNotContain("deep.txt");
    }

    @Test
    @DisplayName("excludes trashPath from non-recursive base-path listing results")
    void excludeTrashPathFromResults() throws Exception {
      final var trashInsideBase = FileSystemStorageStrategyIT.this.basePath.resolve("trash");
      Files.createDirectories(trashInsideBase);
      FileSystemStorageStrategyIT.this.seedFile("real.txt", "r");

      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              trashInsideBase.toString(),
              Duration.ofDays(1));

      final var sp =
          FileSystemStorageStrategyIT.this.directPath(
              FileSystemStorageStrategyIT.this.basePath.toString());
      final var items = localStrategy.listStorageItems(sp);

      assertThat(items).noneMatch(i -> i.getPath().equals(trashInsideBase.toString()));
    }
  }

  @Nested
  @DisplayName("calculatePathUsage()")
  class CalculatePathUsage {

    @Test
    @DisplayName("returns file length for a single file")
    void returnFileLengthWhenPathIsFile() throws Exception {
      final var key = UUID.randomUUID();
      final var content = "1234567890".getBytes(StandardCharsets.UTF_8); // 10 bytes
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", content);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "file.txt");

      final var usage = FileSystemStorageStrategyIT.this.strategy.calculatePathUsage(sp);

      assertThat(usage).isEqualTo(content.length);
    }

    @Test
    @DisplayName("returns sum of all file sizes in directory recursively")
    void returnTotalSizeWhenPathIsDirectory() throws Exception {
      final var key = UUID.randomUUID();
      final var a = "hello".getBytes(StandardCharsets.UTF_8); // 5 bytes
      final var b = "world!".getBytes(StandardCharsets.UTF_8); // 6 bytes
      FileSystemStorageStrategyIT.this.seedFile(key + "/a.txt", a);
      FileSystemStorageStrategyIT.this.seedFile(key + "/sub/b.txt", b);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "");

      final var usage = FileSystemStorageStrategyIT.this.strategy.calculatePathUsage(sp);

      assertThat(usage).isEqualTo(a.length + b.length);
    }

    @Test
    @DisplayName("returns 0 for empty directory")
    void returnZeroWhenDirectoryIsEmpty() throws Exception {
      final var key = UUID.randomUUID();
      Files.createDirectories(FileSystemStorageStrategyIT.this.basePath.resolve(key.toString()));
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "");

      final var usage = FileSystemStorageStrategyIT.this.strategy.calculatePathUsage(sp);

      assertThat(usage).isZero();
    }
  }

  @Nested
  @DisplayName("createDirectory()")
  class CreateDirectory {

    @Test
    @DisplayName("creates a directory under basePath")
    void createDirectoryUnderBasePath() {
      FileSystemStorageStrategyIT.this.strategy.createDirectory("myrepo");

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve("myrepo")).isDirectory();
    }

    @Test
    @DisplayName("does not throw if directory already exists")
    void notThrowWhenDirectoryAlreadyExists() {
      FileSystemStorageStrategyIT.this.strategy.createDirectory("myrepo");
      FileSystemStorageStrategyIT.this.strategy.createDirectory("myrepo");

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve("myrepo")).isDirectory();
    }
  }

  @Nested
  @DisplayName("delete()")
  class Delete {

    @Test
    @DisplayName("moves a single file into trashPath")
    void moveFileToTrashWhenDeletingFile() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", "data");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "file.txt");

      FileSystemStorageStrategyIT.this.strategy.delete(sp);

      // original file no longer exists at basePath
      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/file.txt"))
          .doesNotExist();

      // trash contains a dated sub-directory with our file
      assertThat(FileSystemStorageStrategyIT.this.trashPath).isDirectory();
      try (var stream = Files.walk(trashPath)) {
        var trashEntries =
            stream.filter(p -> p.getFileName().toString().equals("file.txt")).toList();

        assertThat(trashEntries).hasSize(1);
      }
    }

    @Test
    @DisplayName("moves a whole directory into trashPath, keeping its contents")
    void moveDirectoryToTrashWhenDeletingDirectory() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/sub/a.txt", "a");
      FileSystemStorageStrategyIT.this.seedFile(key + "/sub/b.txt", "b");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "sub");

      FileSystemStorageStrategyIT.this.strategy.delete(sp);

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/sub")).doesNotExist();
      try (var stream = Files.walk(trashPath)) {
        var trashEntries =
            stream
                .filter(
                    p ->
                        p.getFileName().toString().equals("a.txt")
                            || p.getFileName().toString().equals("b.txt"))
                .toList();

        assertThat(trashEntries).hasSize(2);
      }
    }
  }

  @Nested
  @DisplayName("renameObject()")
  class RenameObject {

    @Test
    @DisplayName("renames file to the given digest name")
    void renameFileToDigestName() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/original.bin", "bytes");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "original.bin");

      final var usages = FileSystemStorageStrategyIT.this.strategy.renameObject(sp, "sha256digest");

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/original.bin"))
          .doesNotExist();
      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/sha256digest")).exists();
      assertThat(usages.getDiskUsage()).isZero();
    }

    @Test
    @DisplayName("drops the source and keeps the existing file when the digest already exists")
    void dropSourceWhenDigestAlreadyExists() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/sha256digest", "stored");
      FileSystemStorageStrategyIT.this.seedFile(key + "/upload.bin", "duplicate upload");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload.bin");

      final var usages = FileSystemStorageStrategyIT.this.strategy.renameObject(sp, "sha256digest");

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload.bin"))
          .doesNotExist();
      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/sha256digest"))
          .hasContent("stored");
      assertThat(usages.getDiskUsage()).isEqualTo(-"duplicate upload".length());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escaped", "../../escaped", "nested/digest", "a\\b", "", ".", ".."})
    @DisplayName("rejects a digest that is not a plain sibling name and leaves the source alone")
    void rejectDigestThatIsNotASibling(final String digest) throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/upload.bin", "bytes");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "upload.bin");

      assertThatThrownBy(() -> FileSystemStorageStrategyIT.this.strategy.renameObject(sp, digest))
          .isInstanceOf(InvalidStoragePathException.class);

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/upload.bin"))
          .hasContent("bytes");
      assertThat(FileSystemStorageStrategyIT.this.tempDir.resolve("escaped")).doesNotExist();
    }
  }

  @Nested
  @DisplayName("getUsages()")
  class GetUsages {

    @Test
    @DisplayName("diskUsage = contentLength - existingFileLength for an existing file")
    void computeDiskUsageDeltaWhenFileAlreadyExists() throws Exception {
      final var key = UUID.randomUUID();
      final var existing = "existing".getBytes(StandardCharsets.UTF_8); // 8 bytes
      FileSystemStorageStrategyIT.this.seedFile(key + "/f.txt", existing);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "f.txt");

      final var usages = FileSystemStorageStrategyIT.this.strategy.getUsages(sp, "repo", 20L);

      assertThat(usages.getDiskUsage()).isEqualTo(20L - existing.length);
    }

    @Test
    @DisplayName("diskUsage equals contentLength when file does not exist yet")
    void returnFullDiskUsageWhenFileDoesNotExist() throws Exception {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "new.txt");

      final var usages = FileSystemStorageStrategyIT.this.strategy.getUsages(sp, "repo", 15L);

      assertThat(usages.getDiskUsage()).isEqualTo(15L);
    }
  }

  @Nested
  @DisplayName("getFileUsage()")
  class GetFileUsage {

    @Test
    @DisplayName("returns actual file size when file exists")
    void returnFileSizeWhenFileExists() throws Exception {
      final var key = UUID.randomUUID();
      final var content = "abcde".getBytes(StandardCharsets.UTF_8); // 5 bytes
      FileSystemStorageStrategyIT.this.seedFile(key + "/f.bin", content);
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "f.bin");

      final var size = FileSystemStorageStrategyIT.this.strategy.getFileUsage(sp, "repo");

      assertThat(size).isEqualTo(content.length);
    }

    @Test
    @DisplayName("returns 0 when file does not exist")
    void returnZeroWhenFileDoesNotExist() throws Exception {
      final var key = UUID.randomUUID();
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "ghost.bin");

      final var size = FileSystemStorageStrategyIT.this.strategy.getFileUsage(sp, "repo");

      assertThat(size).isZero();
    }
  }

  @Nested
  @DisplayName("clearTrash()")
  class ClearTrash {

    @Test
    @DisplayName("does nothing when trash directory does not exist")
    void doNothingWhenTrashDirectoryDoesNotExist() {
      // trashPath was never created — should not throw
      final var result = strategy.clearTrash().join();

      assertThat(trashPath).doesNotExist();
      assertThat(result).isEqualTo(TrashCleanupResult.EMPTY);
    }

    @Test
    @DisplayName("removes date directories older than retention period")
    void removeDirectoriesOlderThanRetentionPeriod() throws Exception {
      // Strategy with 1-day retention
      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              FileSystemStorageStrategyIT.this.trashPath.toString(),
              Duration.ofDays(1));

      // Create an old dated dir (2 days ago) and a recent one (today)
      final var oldDate = LocalDate.now(ZoneId.systemDefault()).minusDays(2).toString();
      final var newDate = LocalDate.now(ZoneId.systemDefault()).toString();

      final var oldDir = FileSystemStorageStrategyIT.this.trashPath.resolve(oldDate);
      final var newDir = FileSystemStorageStrategyIT.this.trashPath.resolve(newDate);
      Files.createDirectories(oldDir);
      Files.createDirectories(newDir);

      final var result = localStrategy.clearTrash().join();

      assertThat(oldDir).doesNotExist();
      assertThat(newDir).exists();
      // one date directory removed, no files in it, so no bytes freed
      assertThat(result.directoriesDeleted()).isEqualTo(1);
      assertThat(result.filesDeleted()).isZero();
      assertThat(result.bytesFreed()).isZero();
    }

    @Test
    @DisplayName("a directory moved into the trash while the retention is one day survives")
    void keepDirectoryMovedInWhileRetentionIsOneDay() throws Exception {
      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              FileSystemStorageStrategyIT.this.trashPath.toString(),
              Duration.ofDays(1));
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", "data");
      final var oldDir =
          Files.createDirectories(
              FileSystemStorageStrategyIT.this.trashPath.resolve(
                  LocalDate.now(ZoneId.systemDefault()).minusDays(2).toString()));

      localStrategy.delete(FileSystemStorageStrategyIT.this.storagePath(key, "file.txt"));
      localStrategy.clearTrash().join();

      assertThat(oldDir).doesNotExist();
      try (var stream = Files.walk(FileSystemStorageStrategyIT.this.trashPath)) {
        assertThat(stream.filter(p -> p.getFileName().toString().equals("file.txt")).toList())
            .hasSize(1);
      }
    }

    @Test
    @DisplayName("keeps a date directory that is still inside the retention period")
    void keepDirectoryInsideRetentionPeriod() throws Exception {
      final var insideRetention =
          Files.createDirectories(
              FileSystemStorageStrategyIT.this.trashPath.resolve(
                  LocalDate.now(ZoneId.systemDefault()).minusDays(3).toString()));
      final var outsideRetention =
          Files.createDirectories(
              FileSystemStorageStrategyIT.this.trashPath.resolve(
                  LocalDate.now(ZoneId.systemDefault()).minusDays(30).toString()));

      // the shared strategy keeps seven days
      final var result = FileSystemStorageStrategyIT.this.strategy.clearTrash().join();

      assertThat(insideRetention).exists();
      assertThat(outsideRetention).doesNotExist();
      assertThat(result.directoriesDeleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("leaves a directory whose name is not a date alone and still cleans the others")
    void leaveDirectoryThatIsNotADateAlone() throws Exception {
      final var stray =
          Files.createDirectories(FileSystemStorageStrategyIT.this.trashPath.resolve("not-a-date"));
      final var old =
          Files.createDirectories(
              FileSystemStorageStrategyIT.this.trashPath.resolve(
                  LocalDate.now(ZoneId.systemDefault()).minusDays(30).toString()));

      FileSystemStorageStrategyIT.this.strategy.clearTrash().join();

      assertThat(stray).exists();
      assertThat(old).doesNotExist();
    }

    @Test
    @DisplayName("Duration.ZERO retention deletes all dated directories including a past date")
    void deleteAllTrashDirectoriesWhenRetentionIsZero() throws Exception {
      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              FileSystemStorageStrategyIT.this.trashPath.toString());

      final var yesterdayDir =
          FileSystemStorageStrategyIT.this.trashPath.resolve(
              LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString());
      Files.createDirectories(yesterdayDir);

      localStrategy.clearTrash().join();

      assertThat(yesterdayDir).doesNotExist();
    }

    @Test
    @DisplayName("counts the files and bytes it actually removed from an old date directory")
    void countsFilesAndBytesFreedFromRemovedDirectory() throws Exception {
      final var localStrategy =
          new FileSystemStorageStrategy(
              FileSystemStorageStrategyIT.this.basePath.toString(),
              FileSystemStorageStrategyIT.this.trashPath.toString(),
              Duration.ofDays(1));
      final var oldDate = LocalDate.now(ZoneId.systemDefault()).minusDays(2).toString();
      final var oldDir = FileSystemStorageStrategyIT.this.trashPath.resolve(oldDate);
      Files.createDirectories(oldDir.resolve("nested"));
      Files.writeString(oldDir.resolve("a.txt"), "12345"); // 5 bytes
      Files.writeString(oldDir.resolve("nested/b.txt"), "1234567"); // 7 bytes

      final var result = localStrategy.clearTrash().join();

      // the date directory itself + the nested subdirectory
      assertThat(result.directoriesDeleted()).isEqualTo(2);
      assertThat(result.filesDeleted()).isEqualTo(2);
      assertThat(result.bytesFreed()).isEqualTo(12L);
      assertThat(oldDir).doesNotExist();
    }
  }

  @Nested
  @DisplayName("listStaleFiles()")
  class ListStaleFiles {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    @DisplayName("lists only the files written before the threshold, with their sizes")
    void listOnlyFilesNotModifiedSinceThreshold() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/blobs/old", "12345");
      FileSystemStorageStrategyIT.this.seedFile(key + "/blobs/fresh", "abc");
      this.setLastModified(key + "/blobs/old", NOW.minus(Duration.ofHours(30)));
      this.setLastModified(key + "/blobs/fresh", NOW.minus(Duration.ofHours(1)));

      final var result =
          FileSystemStorageStrategyIT.this.strategy.listStaleFiles(
              FileSystemStorageStrategyIT.this.storagePath(key, "blobs"),
              NOW.minus(Duration.ofHours(24)));

      assertThat(result).containsExactly(new StaleFile("old", 5L));
    }

    @Test
    @DisplayName("treats a file last written exactly at the threshold as still in progress")
    void keepFileWrittenExactlyAtThreshold() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/blobs/edge", "x");
      this.setLastModified(key + "/blobs/edge", NOW);

      final var result =
          FileSystemStorageStrategyIT.this.strategy.listStaleFiles(
              FileSystemStorageStrategyIT.this.storagePath(key, "blobs"), NOW);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("skips subdirectories and files nested below the directory")
    void skipSubdirectories() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/blobs/nested/deep.bin", "deep");
      this.setLastModified(key + "/blobs/nested/deep.bin", NOW.minus(Duration.ofDays(2)));
      this.setLastModified(key + "/blobs/nested", NOW.minus(Duration.ofDays(2)));

      final var result =
          FileSystemStorageStrategyIT.this.strategy.listStaleFiles(
              FileSystemStorageStrategyIT.this.storagePath(key, "blobs"), NOW);

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("answers an empty list when the directory does not exist")
    void answerEmptyWhenDirectoryMissing() {
      final var result =
          FileSystemStorageStrategyIT.this.strategy.listStaleFiles(
              FileSystemStorageStrategyIT.this.storagePath(UUID.randomUUID(), "blobs"), NOW);

      assertThat(result).isEmpty();
    }

    private void setLastModified(final String relativePath, final Instant instant)
        throws IOException {
      Files.setLastModifiedTime(
          FileSystemStorageStrategyIT.this.basePath.resolve(relativePath), FileTime.from(instant));
    }
  }

  // Helpers

  // Writes raw bytes to a file under basePath, creating parent dirs as needed.
  private void seedFile(final String relativePath, final byte[] content) throws IOException {
    final var target = this.basePath.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.write(target, content);
  }

  private void seedFile(final String relativePath, final String content) throws IOException {
    this.seedFile(relativePath, content.getBytes(StandardCharsets.UTF_8));
  }

  // Returns a StoragePath backed by a real UUID + relative path string.
  private StoragePath storagePath(final UUID key, final String relative) {
    return StoragePath.of(key, relative);
  }

  /** Returns a direct-path StoragePath (storageKey == null). */
  private StoragePath directPath(final String path) {
    return StoragePath.ofPath(path);
  }
}
