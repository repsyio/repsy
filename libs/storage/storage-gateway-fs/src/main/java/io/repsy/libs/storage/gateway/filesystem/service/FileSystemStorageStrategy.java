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

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.dtos.TrashCleanupResult;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Async;

public class FileSystemStorageStrategy implements StorageStrategy {

  private static final String PATH_DELIMITER = "/";

  private final @NonNull Path basePath;
  private final @NonNull Path trashPath;
  private final @NonNull Duration trashRetentionPeriod;

  public FileSystemStorageStrategy(
      final @NonNull String basePath,
      final @NonNull String trashPath,
      final @NonNull Duration trashRetentionPeriod) {
    this.basePath = Path.of(basePath);
    this.trashPath = Path.of(trashPath);
    this.trashRetentionPeriod = trashRetentionPeriod;
  }

  public FileSystemStorageStrategy(
      final @NonNull String basePath, final @NonNull String trashPath) {
    this.basePath = Path.of(basePath);
    this.trashPath = Path.of(trashPath);
    this.trashRetentionPeriod = Duration.ZERO;
  }

  @Override
  @SneakyThrows
  public @NonNull Optional<Resource> get(
      final @NonNull StoragePath storagePath, final @NonNull String repoName)
      throws IsADirectoryException {
    final UrlResource urlResource = new UrlResource(this.toPhysicalPath(storagePath).toUri());

    if (!urlResource.exists()) {
      return Optional.empty();
    }

    final File file = urlResource.getFile();

    if (file.isDirectory()) {
      throw new IsADirectoryException();
    }

    return Optional.of(urlResource);
  }

  /**
   * Answers the files that were last written before {@code notModifiedSince}. A file removed while
   * the directory is walked (an upload finalized in the meantime) reads as a non-file and is left
   * out instead of failing the listing.
   */
  @Override
  @SneakyThrows
  public @NonNull List<StaleFile> listStaleFiles(
      final @NonNull StoragePath directory, final @NonNull Instant notModifiedSince) {
    final Path path = this.toPhysicalPath(directory);

    if (!Files.isDirectory(path)) {
      return List.of();
    }

    final var staleFiles = new ArrayList<StaleFile>();

    try (final Stream<Path> stream = Files.list(path)) {
      for (final Path entry : (Iterable<Path>) stream::iterator) {
        final File file = entry.toFile();
        final long lastModified = file.lastModified();
        final long size = file.length();

        if (file.isFile() && Instant.ofEpochMilli(lastModified).isBefore(notModifiedSince)) {
          staleFiles.add(new StaleFile(file.getName(), size));
        }
      }
    }

    return staleFiles;
  }

  @Override
  @SneakyThrows
  public @NonNull List<StorageItemInfo> listDirectoryContents(
      final @NonNull StoragePath storagePath) {
    final Path folderPath = this.toPhysicalPath(storagePath);
    final File folder = folderPath.toFile();

    if (!folder.isDirectory()) {
      throw new ItemNotFoundException("resourceNotFound");
    }

    final List<StorageItemInfo> itemInfos = new ArrayList<>();
    final File[] files = folder.listFiles();

    if (files == null) {
      return itemInfos;
    }

    final Set<StorageItemInfo> directoryList = new TreeSet<>();
    final Set<StorageItemInfo> fileList = new TreeSet<>();

    this.addItems(files, directoryList, fileList);

    itemInfos.addAll(directoryList);
    itemInfos.addAll(fileList);

    return itemInfos;
  }

  @SneakyThrows
  @Override
  public @NonNull List<StorageItemInfo> listStorageItems(final @NonNull StoragePath storagePath) {
    final Path path = this.toPhysicalPath(storagePath);

    try (final Stream<Path> stream =
        storagePath.getStorageKey() == null ? Files.list(path) : Files.walk(path)) {
      return stream
          .map(this::toStorageItemInfo)
          .filter(si -> !si.getPath().equals(this.trashPath.toString()))
          .toList();
    }
  }

  @SneakyThrows
  private StorageItemInfo toStorageItemInfo(final @NonNull Path path) {
    final File file = path.toFile();

    final var fileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
    final Date createdAt = Date.from(fileAttributes.creationTime().toInstant());

    return StorageItemInfo.builder()
        .name(file.getName())
        .size(file.length())
        .createdAt(createdAt)
        .directory(file.isDirectory())
        .path(path.toString())
        .build();
  }

  @Override
  @SneakyThrows
  public @NonNull BaseUsages write(
      final @NonNull String repoName,
      final @NonNull StoragePath storagePath,
      final @NonNull InputStream inputStream) {
    long existingFileLength = 0;

    final Optional<Resource> existingFile = this.get(storagePath, repoName);

    if (existingFile.isPresent()) {
      existingFileLength = existingFile.get().contentLength();
    }

    final Path physicalPath = this.toPhysicalPath(storagePath);
    final Path directory = physicalPath.getParent();

    if (!Files.exists(directory)) {
      Files.createDirectories(directory);
    }

    final long bytesWritten;
    try (final InputStream is = inputStream;
        final OutputStream os =
            Files.newOutputStream(physicalPath, CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

      bytesWritten = is.transferTo(os);
    }

    return BaseUsages.builder().diskUsage(bytesWritten - existingFileLength).build();
  }

  @Override
  @SneakyThrows
  public @NonNull BaseUsages append(
      final @NonNull String repoName, final @NonNull StoragePath storagePath, final byte[] data) {

    final Path physicalPath = this.toPhysicalPath(storagePath);
    final Path directory = physicalPath.getParent();

    if (!Files.exists(directory)) {
      Files.createDirectories(directory);
    }

    try (final var os = Files.newOutputStream(physicalPath, CREATE, APPEND)) {
      os.write(data);
    }

    return BaseUsages.builder().diskUsage(data.length).build();
  }

  @Override
  @SneakyThrows
  public @NonNull BaseUsages appendStream(
      final @NonNull String repoName,
      final @NonNull StoragePath storagePath,
      final @NonNull InputStream inputStream) {

    final Path physicalPath = this.toPhysicalPath(storagePath);
    final Path directory = physicalPath.getParent();

    if (!Files.exists(directory)) {
      Files.createDirectories(directory);
    }

    final long lengthBefore = Files.exists(physicalPath) ? Files.size(physicalPath) : 0;
    final long bytesAppended;

    try (final InputStream is = inputStream;
        final OutputStream os = Files.newOutputStream(physicalPath, CREATE, APPEND)) {

      bytesAppended = is.transferTo(os);
    } catch (final IOException | RuntimeException e) {
      this.truncate(physicalPath, lengthBefore, e);
      throw e;
    }

    return BaseUsages.ofDisk(bytesAppended);
  }

  /** Cuts a partly appended file back to its length before the append. */
  private void truncate(final Path physicalPath, final long length, final Exception cause) {
    try {
      if (length == 0) {
        Files.deleteIfExists(physicalPath);
        return;
      }

      try (final FileChannel channel = FileChannel.open(physicalPath, StandardOpenOption.WRITE)) {
        channel.truncate(length);
      }
    } catch (final IOException e) {
      cause.addSuppressed(e);
    }
  }

  @Override
  public void createDirectory(final @NonNull String name) throws IsADirectoryException {
    this.basePath.resolve(name).toFile().mkdirs();
  }

  private long calculatePathUsage(final @NonNull StoragePath paths, final long size) {
    final File file = this.toPhysicalPath(paths).toFile();

    if (file.isFile()) {
      return file.length() + size;
    }

    long totalUsage = size;

    final File[] files = file.listFiles();

    if (files == null) {
      return totalUsage;
    }

    for (final @NonNull File subFile : files) {
      final StoragePath storagePath =
          StoragePath.of(
              paths.getStorageKey(),
              paths.getRelativePath().getPath() + PATH_DELIMITER + subFile.getName());
      totalUsage = this.calculatePathUsage(storagePath, totalUsage);
    }

    return totalUsage;
  }

  @Override
  public long calculatePathUsage(final @NonNull StoragePath paths) {
    return this.calculatePathUsage(paths, 0L);
  }

  /**
   * Soft-deletes the file or directory at {@code storagePath}: it is moved into {@code
   * <trashPath>/<today>/<timestamp>/<path>}, so it stays recoverable until {@link #clearTrash()}
   * removes it for good after the retention period elapses. {@code Files.move} does not distinguish
   * a file from a directory, so a single object and a whole tree are soft-deleted the same way.
   */
  @SneakyThrows
  @Override
  public void delete(final @NonNull StoragePath storagePath) {
    final Path basePathObj = this.toPhysicalPath(storagePath);
    final String relativePath =
        String.join(
            "/",
            LocalDate.now(ZoneId.systemDefault()).toString(),
            Instant.now().toString(),
            storagePath.getPath());
    final Path trashPathObj = this.trashPath.resolve(relativePath);

    Files.createDirectories(trashPathObj.getParent());
    Files.move(basePathObj, trashPathObj);
  }

  /**
   * Deletes the date directories of the trash that are older than the retention period, for good.
   *
   * <p>{@link #delete} files a deleted item under the directory named after today's date in the
   * system time zone, and this method reads that name back in the same zone, so a directory counts
   * as old once its whole day lies before {@code now - retention}. A retention of at least one day
   * therefore never touches the directory a concurrent delete is moving into. A {@link
   * Duration#ZERO} retention does, and is only meant for tests. A directory whose name is not a
   * date is not ours and is left alone.
   *
   * <p>Runs on the application's {@code maintenanceTaskExecutor}: walking and deleting the trash
   * can take minutes and must not hold up the default {@code @Async} pool. An application that
   * enables {@code @Async} has to define a bean of that name. Spring executes this method on that
   * pool and reports an exception it throws through the returned future instead of throwing it
   * synchronously, because the return type is a future.
   */
  @SneakyThrows
  @Async("maintenanceTaskExecutor")
  @Override
  public @NonNull CompletableFuture<TrashCleanupResult> clearTrash() {
    if (!Files.exists(this.trashPath) || !Files.isDirectory(this.trashPath)) {
      return CompletableFuture.completedFuture(TrashCleanupResult.EMPTY);
    }

    final Instant threshold = Instant.now().minus(this.trashRetentionPeriod);
    TrashCleanupResult result = TrashCleanupResult.EMPTY;

    try (final Stream<Path> trashItems = Files.list(this.trashPath)) {
      final List<Path> dateDirs = trashItems.filter(Files::isDirectory).toList();

      for (final Path dateDir : dateDirs) {
        final Optional<Instant> dirInstant = toDayStart(dateDir);

        if (dirInstant.isPresent() && dirInstant.get().isBefore(threshold)) {
          result = result.plus(deleteRecursivelyWithStats(dateDir));
        }
      }
    }

    return CompletableFuture.completedFuture(result);
  }

  /**
   * Deletes {@code directory} and everything under it, for good, tallying what it removed along the
   * way. The stats are gathered while walking rather than beforehand, so they reflect exactly what
   * this call actually deleted.
   */
  @SneakyThrows
  private static @NonNull TrashCleanupResult deleteRecursivelyWithStats(final Path directory) {
    final AtomicInteger directoriesDeleted = new AtomicInteger();
    final AtomicInteger filesDeleted = new AtomicInteger();
    final AtomicLong bytesFreed = new AtomicLong();

    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public @NonNull FileVisitResult visitFile(
              final Path file, final @NonNull BasicFileAttributes attrs) throws IOException {
            bytesFreed.addAndGet(attrs.size());
            filesDeleted.incrementAndGet();
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public @NonNull FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
              throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(dir);
            directoriesDeleted.incrementAndGet();
            return FileVisitResult.CONTINUE;
          }
        });

    return new TrashCleanupResult(directoriesDeleted.get(), filesDeleted.get(), bytesFreed.get());
  }

  private static Optional<Instant> toDayStart(final Path dateDir) {
    try {
      return Optional.of(
          LocalDate.parse(dateDir.getFileName().toString())
              .atStartOfDay(ZoneId.systemDefault())
              .toInstant());
    } catch (final DateTimeParseException e) {
      return Optional.empty();
    }
  }

  @Override
  public @NonNull BaseUsages getUsages(
      final @NonNull StoragePath storagePath,
      final @NonNull String repoName,
      final long contentLength)
      throws IOException {
    final Optional<Resource> file = this.get(storagePath, repoName);

    long existingFileLength = 0;

    if (file.isPresent()) {
      existingFileLength = file.get().contentLength();
    }

    return BaseUsages.builder().diskUsage(contentLength - existingFileLength).build();
  }

  @Override
  public long getFileUsage(final @NonNull StoragePath storagePath, final @NonNull String repoName)
      throws IOException {
    final Optional<Resource> file = this.get(storagePath, repoName);

    long existingFileLength = 0;

    if (file.isPresent()) {
      existingFileLength = file.get().contentLength();
    }

    return existingFileLength;
  }

  /**
   * Renames the object to its digest. The target is content-addressed, so when it already exists
   * (the same layer pushed twice, or concurrently) it holds the same bytes: the redundant source is
   * dropped and the call succeeds, answering the bytes it freed as negative disk usage. The digest
   * comes from the client, so it has to be a plain file name: anything that could leave the
   * source's directory is refused.
   */
  @SneakyThrows
  @Override
  public @NonNull BaseUsages renameObject(
      final @NonNull StoragePath storagePath, final @NonNull String digest) {
    if (digest.isEmpty()
        || digest.equals(".")
        || digest.contains("..")
        || digest.contains("/")
        || digest.contains("\\")) {
      throw new InvalidStoragePathException("invalidStoragePath");
    }
    final Path basePathObj = this.toPhysicalPath(storagePath);
    return this.moveOrDropDuplicate(basePathObj, basePathObj.resolveSibling(digest));
  }

  private BaseUsages moveOrDropDuplicate(final Path source, final Path target) throws IOException {
    try {
      Files.move(source, target);
      return BaseUsages.ofDisk(0);
    } catch (final FileAlreadyExistsException e) {
      final var size = Files.size(source);
      Files.deleteIfExists(source);
      return BaseUsages.ofDisk(-size);
    }
  }

  private @NonNull Path toPhysicalPath(final @NonNull StoragePath storagePath) {
    final var normalized = this.basePath.normalize();
    final var resolved = normalized.resolve(storagePath.getPath()).normalize();
    if (!resolved.startsWith(normalized)) {
      throw new InvalidStoragePathException("invalidStoragePath");
    }
    return resolved;
  }

  private void addItems(
      final @NonNull File @NonNull [] files,
      final @NonNull Set<StorageItemInfo> directoryList,
      final @NonNull Set<StorageItemInfo> fileList)
      throws IOException {
    for (final @NonNull File file : files) {
      final String fileName = file.getName();

      if (file.getPath().equals(this.trashPath.toString())) {
        continue;
      }

      final var fileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);

      final StorageItemInfo itemInfo =
          StorageItemInfo.builder()
              .createdAt(Date.from(fileAttributes.creationTime().toInstant()))
              .build();

      if (Files.isDirectory(file.toPath()) && !file.getPath().equals(this.trashPath.toString())) {
        itemInfo.setName(fileName + "/");
        itemInfo.setDirectory(true);
        directoryList.add(itemInfo);
      } else {
        itemInfo.setName(fileName);
        itemInfo.setDirectory(false);
        itemInfo.setSize(fileAttributes.size());
        fileList.add(itemInfo);
      }
    }
  }
}
