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
package io.repsy.protocols.maven.shared.storage.services;

import static java.nio.charset.StandardCharsets.UTF_8;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.exceptions.RedirectToSlashEndedLocationException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenStorageService<ID> implements MavenStorageService<ID> {

  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String ERR_ITEM_NOT_FOUND = "itemNotFound";

  private final Configuration freeMarkerConfiguration;
  private final StorageStrategy storageStrategy;

  @Override
  public void createRepo(final UUID repoUuid) {

    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public BaseUsages getUsages(
      final StoragePath storagePath, final String repoName, final long contentLength)
      throws IOException {

    return this.storageStrategy.getUsages(storagePath, repoName, contentLength);
  }

  @Override
  public List<StorageItemInfo> getItems(final StoragePath storagePath) {

    final var itemInfoList = this.storageStrategy.listDirectoryContents(storagePath);

    if (!itemInfoList.isEmpty()) {
      this.addDirectoryUpLink(storagePath.getRelativePath().getPath(), itemInfoList);
    }

    return itemInfoList;
  }

  @SneakyThrows
  @Override
  public Resource getResource(final String repoName, final StoragePath storagePath) {

    try {
      return this.storageStrategy
          .get(storagePath, repoName)
          .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));

    } catch (final IsADirectoryException _) {
      if (!storagePath.getPath().endsWith("/")) {
        throw new RedirectToSlashEndedLocationException();
      }

      return this.generateDirectoryContentInHtml(repoName, storagePath);
    }
  }

  @SneakyThrows
  @Override
  public BaseUsages writeInputStreamToPath(
      final StoragePath storagePath, final InputStream inputStream, final String repoName) {

    return this.storageStrategy.write(repoName, storagePath, inputStream);
  }

  @SneakyThrows
  private Resource generateDirectoryContentInHtml(
      final String repoName, final StoragePath storagePath) {

    final var template = this.freeMarkerConfiguration.getTemplate("directory.ftl");

    final String directoryContentHtml;

    final var items = this.storageStrategy.listDirectoryContents(storagePath);

    if (!items.isEmpty()) {
      this.addDirectoryUpLink(storagePath.getRelativePath().getPath(), items);
    }

    try {
      directoryContentHtml =
          FreeMarkerTemplateUtils.processTemplateIntoString(
              template,
              Map.of(
                  "repository",
                  repoName,
                  "relativePath",
                  storagePath.getRelativePath().getPath(),
                  "items",
                  items));
    } catch (final TemplateException e) {

      throw new ErrorOccurredException(e);
    }

    return new ByteArrayResource(directoryContentHtml.getBytes(UTF_8));
  }

  private void addDirectoryUpLink(
      final String relativePath, final List<StorageItemInfo> directoryList) {

    if (!relativePath.isEmpty() && !relativePath.equals("/")) {
      directoryList.addFirst(StorageItemInfo.builder().name("../").directory(true).build());
    }
  }

  @Override
  public long deleteArtifact(final UUID repoUuid, final String groupId, final String artifactId) {

    final var artifactPath = this.getPath(groupId, artifactId);
    final var storagePath = StoragePath.of(repoUuid, artifactPath.toString());
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public long deleteArtifactVersion(
      final UUID repoUuid,
      final String groupId,
      final String artifactId,
      final String versionName) {

    final var versionPath = this.getPath(groupId, artifactId, versionName);
    final var storagePath = StoragePath.of(repoUuid, versionPath.toString());
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public long deleteGroup(final UUID repoUuid, final String groupId) {

    final var groupPaths = this.getPath(groupId);
    final var storagePath = StoragePath.of(repoUuid, groupPaths[0].toString());
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    // Let's say our package is io.repsy.test the folder structure is
    // - io
    //   - fria
    //     - ...
    //   - repsy
    //     - test
    //       - ...
    // deleting /io/repsy/test is safe

    this.storageStrategy.deleteDirectory(storagePath);

    // index started from 1, because we already deleted the first path
    for (int i = 1; i < groupPaths.length; i++) {
      final var sp = StoragePath.of(repoUuid, groupPaths[i] + "/");

      final var dirCount =
          this.storageStrategy.listDirectoryContents(sp).stream()
              .filter(StorageItemInfo::isDirectory)
              .count();

      if (dirCount == 0) {
        this.storageStrategy.deleteDirectory(sp);
      }
    }

    return usage;
  }

  @Override
  public long deleteRepo(final UUID repoUuid) {

    final var storagePath = StoragePath.of(repoUuid);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  /**
   * Read artifact metadata and remove given version. Then reset the latest and release fields both
   * in Artifact and metadata and write metadata back to file.
   */
  @Override
  public Pair<Versioning, BaseUsages> deleteVersionFromMetadata(
      final BaseRepoInfo<ID> repoInfo,
      final String groupId,
      final String artifactId,
      final String versionName)
      throws IOException, XmlPullParserException {

    final var artifactBasePath = this.getPath(groupId, artifactId);

    final var storagePath =
        StoragePath.of(
            repoInfo.getStorageKey(), artifactBasePath.resolve(METADATA_FILENAME).toString());

    final var metadataResource =
        this.storageStrategy
            .get(storagePath, repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));

    final var metadata = ArtifactUtils.readMetadata(metadataResource.getContentAsByteArray());

    if (metadata == null) {
      throw new IOException();
    }

    final var versioning = metadata.getVersioning();

    versioning.getVersions().remove(versionName);
    versioning.setLastUpdatedTimestamp(Date.from(Instant.now()));

    ArtifactUtils.setReleaseAndLatest(metadata);

    final var usages =
        this.writeMetadataAndChecksumsToFile(
            storagePath, artifactBasePath, metadata, repoInfo.getName());

    return Pair.of(versioning, usages);
  }

  private Path[] getPath(final String groupId) {

    final var paths = groupId.split("\\.", -1);
    final var groupPaths = new Path[paths.length];

    var path = Path.of(paths[0]);
    groupPaths[paths.length - 1] = path;

    for (int i = 1; i < paths.length; i++) {
      path = path.resolve(paths[i]).normalize();
      groupPaths[paths.length - 1 - i] = path;
    }

    return groupPaths;
  }

  @Override
  public Path getPath(final String groupId, final String artifactId) {

    final var paths = groupId.split("\\.", -1);

    var path = Path.of(paths[0]);

    for (int i = 1; i < paths.length; ++i) {
      path = path.resolve(paths[i]);
    }

    return path.resolve(artifactId).normalize();
  }

  @Override
  public Resource getResource(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));
  }

  /**
   * Write a Metadata object into a file. If a metadata file already exists this method overwrites
   * it
   */
  private BaseUsages writeMetadataAndChecksumsToFile(
      final StoragePath metadataStoragePath,
      final Path artifactBasePath,
      final Metadata metadata,
      final String repoName)
      throws IOException {

    final var metadataXpp3Writer = new MetadataXpp3Writer();

    try (final var byteArrayOutputStream = new ByteArrayOutputStream()) {
      metadataXpp3Writer.write(byteArrayOutputStream, metadata);

      final var metadataBytes = byteArrayOutputStream.toByteArray();

      final BaseUsages usages;

      try (final var metadataInputStream = new ByteArrayInputStream(metadataBytes)) {
        usages = this.storageStrategy.write(repoName, metadataStoragePath, metadataInputStream);
      }

      this.updateChecksumsOfMetadata(metadataStoragePath, artifactBasePath, repoName);

      return usages;
    }
  }

  private Path getPath(final String groupId, final String artifactId, final String versionName) {

    return this.getPath(groupId, artifactId).resolve(versionName).normalize();
  }

  /**
   * Calculate digest checksums of metadata file and write them into checksum files respectively.
   * Input and Output should be same so no need to return usage.
   */
  private void updateChecksumsOfMetadata(
      final StoragePath metadataStoragePath, final Path artifactBasePath, final String repoName)
      throws IOException {

    final var repoUuid = metadataStoragePath.getStorageKey();

    final var resource =
        this.storageStrategy
            .get(metadataStoragePath, repoName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ITEM_NOT_FOUND));

    final var metadataContent = resource.getContentAsString(StandardCharsets.UTF_8);

    // Hash algorithms and its extensions
    final var hashFunctions =
        Map.<String, UnaryOperator<String>>of(
            "md5", DigestUtils::md5Hex,
            "sha1", DigestUtils::sha1Hex,
            "sha256", DigestUtils::sha256Hex,
            "sha512", DigestUtils::sha512Hex);

    for (final var entry : hashFunctions.entrySet()) {
      this.writeChecksumIfExists(
          Objects.requireNonNull(repoUuid),
          artifactBasePath,
          repoName,
          metadataContent,
          entry.getKey(),
          entry.getValue());
    }
  }

  private void writeChecksumIfExists(
      final UUID repoUuid,
      final Path artifactBasePath,
      final String repoName,
      final String content,
      final String extension,
      final UnaryOperator<String> hashFunction)
      throws IOException {

    final var checksumFile = artifactBasePath.resolve(METADATA_FILENAME + "." + extension);

    final var storagePath = StoragePath.of(repoUuid, checksumFile.toString());

    final var optionalResource = this.storageStrategy.get(storagePath, repoName);

    if (optionalResource.isEmpty() || !optionalResource.get().exists()) {
      return;
    }

    final var checksumBytes = hashFunction.apply(content).getBytes(StandardCharsets.UTF_8);

    try (final var checksumInputStream = new ByteArrayInputStream(checksumBytes)) {
      this.storageStrategy.write(repoName, storagePath, checksumInputStream);
    }
  }

  @Override
  public void clearTrash() {

    this.storageStrategy.clearTrash();
  }
}
