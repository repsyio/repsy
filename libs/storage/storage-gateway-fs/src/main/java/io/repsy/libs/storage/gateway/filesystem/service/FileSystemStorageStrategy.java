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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.FileSystemUtils;

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
    final UrlResource urlResource =
        new UrlResource(this.basePath.resolve(storagePath.getPath()).toUri());

    if (!urlResource.exists()) {
      return Optional.empty();
    }

    final File file = urlResource.getFile();

    if (file.isDirectory()) {
      throw new IsADirectoryException();
    }

    return Optional.of(urlResource);
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
    final Path path =
        (storagePath.getStorageKey() == null)
            ? this.basePath.resolve(storagePath.getPath())
            : this.toPhysicalPath(storagePath);

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
            Files.newOutputStream(
                physicalPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

      bytesWritten = is.transferTo(os);
    }

    return BaseUsages.builder().diskUsage(bytesWritten - existingFileLength).build();
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

  @SneakyThrows
  @Override
  public void deleteDirectory(final @NonNull StoragePath storagePath) {
    final Path basePathObj = this.basePath.resolve(storagePath.getPath());
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

  @Override
  public void delete(final @NonNull StoragePath storagePath) {
    this.deleteDirectory(storagePath);
  }

  @SneakyThrows
  @Async
  @Override
  public void clearTrash() {
    if (!Files.exists(this.trashPath) || !Files.isDirectory(this.trashPath)) {
      return;
    }

    final Instant threshold = Instant.now().minus(this.trashRetentionPeriod);

    try (final Stream<Path> trashItems = Files.list(this.trashPath)) {
      final List<Path> dateDirs = trashItems.filter(Files::isDirectory).toList();

      for (final Path dateDir : dateDirs) {
        final Instant dirInstant =
            LocalDate.parse(dateDir.getFileName().toString())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        if (dirInstant.isBefore(threshold)) {
          FileSystemUtils.deleteRecursively(dateDir);
        }
      }
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

  @SneakyThrows
  @Override
  public void renameObject(final @NonNull StoragePath storagePath, final @NonNull String digest) {
    final Path basePathObj = this.basePath.resolve(storagePath.getPath());
    final Path renamedPath = basePathObj.resolveSibling(digest);
    Files.move(basePathObj, renamedPath);
  }

  private @NonNull Path toPhysicalPath(final @NonNull StoragePath storagePath) {
    return this.basePath.resolve(storagePath.getPath());
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
