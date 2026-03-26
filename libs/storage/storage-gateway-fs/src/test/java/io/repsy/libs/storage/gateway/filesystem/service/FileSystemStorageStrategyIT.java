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
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  @DisplayName("deleteDirectory() / delete()")
  class DeleteDirectory {

    @Test
    @DisplayName("moves the target into trashPath")
    void moveTargetToTrashWhenDeletingDirectory() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/file.txt", "data");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "file.txt");

      FileSystemStorageStrategyIT.this.strategy.deleteDirectory(sp);

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
    @DisplayName("delete() delegates to deleteDirectory() when given a file path")
    void delegateToDeleteDirectoryWhenDeleteIsCalled() throws Exception {
      final var key = UUID.randomUUID();
      FileSystemStorageStrategyIT.this.seedFile(key + "/x.txt", "x");
      final var sp = FileSystemStorageStrategyIT.this.storagePath(key, "x.txt");

      FileSystemStorageStrategyIT.this.strategy.delete(sp);

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/x.txt")).doesNotExist();
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

      FileSystemStorageStrategyIT.this.strategy.renameObject(sp, "sha256digest");

      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/original.bin"))
          .doesNotExist();
      assertThat(FileSystemStorageStrategyIT.this.basePath.resolve(key + "/sha256digest")).exists();
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
      strategy.clearTrash();
      assertThat(trashPath).doesNotExist();
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
      final var oldDate = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();
      final var newDate = LocalDate.now(ZoneOffset.UTC).toString();

      final var oldDir = FileSystemStorageStrategyIT.this.trashPath.resolve(oldDate);
      final var newDir = FileSystemStorageStrategyIT.this.trashPath.resolve(newDate);
      Files.createDirectories(oldDir);
      Files.createDirectories(newDir);

      localStrategy.clearTrash();

      assertThat(oldDir).doesNotExist();
      assertThat(newDir).exists();
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
              LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
      Files.createDirectories(yesterdayDir);

      localStrategy.clearTrash();

      assertThat(yesterdayDir).doesNotExist();
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
