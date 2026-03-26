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
package io.repsy.protocols.npm.shared.storage.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.utils.NpmConstants;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Base64;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@SuppressWarnings("unchecked")
@NullMarked
public abstract class AbstractNpmStorageService implements NpmStorageService {

  private final StorageStrategy storageStrategy;

  @Override
  public long deleteRepo(final UUID repoId) {

    final var storagePath = StoragePath.of(repoId);

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public void createRepo(final UUID repoId) {
    this.storageStrategy.createDirectory(repoId.toString());
  }

  @Override
  public long removeDistributionTag(
      final UUID repoId, final String repoName, final Path packageBasePath, final String tagName)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);

    final var metadataStoragePath = StoragePath.of(repoId, metadataPath.toString());

    final var metadata = this.getMetadata(metadataStoragePath, repoName);

    final var oldMetadataLength = PackageUtils.getMetadataLength(metadata);

    final var distTags = (Map<String, String>) metadata.get(NpmConstants.DIST_TAGS);

    distTags.remove(tagName);

    this.writeMetadataToFile(repoName, metadata, metadataStoragePath);

    return PackageUtils.getMetadataLength(metadata) - oldMetadataLength;
  }

  @Override
  public BaseUsages writeMetadataToFile(
      final String repoName,
      final Map<String, Object> metadata,
      final StoragePath metadataStoragePath)
      throws IOException {

    final var mapper = new ObjectMapper();

    final var metadataBytes = mapper.writeValueAsBytes(metadata);

    try (final var byteArrayInputStream = new ByteArrayInputStream(metadataBytes)) {
      return this.storageStrategy.write(repoName, metadataStoragePath, byteArrayInputStream);
    }
  }

  @Override
  public Pair<Map<String, Object>, Long> addDistributionTag(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String tagName,
      final String versionName)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var metadataStoragePath = StoragePath.of(repoId, metadataPath.toString());

    final var metadata = this.getMetadata(metadataStoragePath, repoName);
    final var oldMetadataLength = PackageUtils.getMetadataLength(metadata);

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);

    if (!versions.containsKey(versionName)) {
      throw new BadRequestException("packageVersionNotFound");
    }

    final var distTags = (Map<String, String>) metadata.get(NpmConstants.DIST_TAGS);

    distTags.put(tagName, versionName);

    final var metadataLength = PackageUtils.getMetadataLength(metadata) - oldMetadataLength;

    return Pair.of(metadata, metadataLength);
  }

  @Override
  public Pair<Long, Long> processPackagePayload(
      final Map<String, Object> payload, final String repoName)
      throws URISyntaxException, JacksonException {

    final var versionPair = PackageUtils.extractVersionFromPayload(payload);

    PackageUtils.fixTarballUrl(versionPair.getSecond(), repoName);

    final var distributionTags = (Map<String, String>) payload.get(NpmConstants.DIST_TAGS);

    distributionTags.put(NpmConstants.LATEST, versionPair.getFirst());

    final var timeField = new LinkedHashMap<String, String>();

    timeField.put("created", PackageUtils.getFormattedCurrentTime());
    timeField.put(NpmConstants.MODIFIED, PackageUtils.getFormattedCurrentTime());
    timeField.put(versionPair.getFirst(), PackageUtils.getFormattedCurrentTime());

    payload.put("time", timeField);

    PackageUtils.liftFieldsToTopLevel(payload, versionPair.getFirst());

    return Pair.of(PackageUtils.getMetadataLength(payload), PackageUtils.getTarballLength(payload));
  }

  @Override
  public BaseUsages writeTarballAndMetadata(
      final UUID repoId,
      final String repoName,
      final Map<String, Object> metadata,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException, URISyntaxException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var metadataStoragePath = StoragePath.of(repoId, metadataPath.toString());

    final var tarballFileName = PackageUtils.getTarballFilename(packageName, versionName);
    final var tarballPath = packageBasePath.resolve(tarballFileName);

    final var data = PackageUtils.extractTarballDataFromPayload(metadata);
    final var tarballBytes = Base64.decodeBase64(data);

    PackageUtils.updateDistFields(metadata, versionName, tarballBytes);

    final BaseUsages tarballUsages;
    try (final var byteArrayInputStream = new ByteArrayInputStream(tarballBytes)) {
      tarballUsages =
          this.storageStrategy.write(
              repoName, StoragePath.of(repoId, tarballPath.toString()), byteArrayInputStream);
    }

    final var usage = this.writeMetadataToFile(repoName, metadata, metadataStoragePath);

    usage.setDiskUsage(usage.getDiskUsage() + tarballUsages.getDiskUsage());

    return usage;
  }

  @Override
  public Pair<Pair<Long, Long>, Map<String, Object>> processVersionPayload(
      final Map<String, Object> payload,
      final Path packageBasePath,
      final UUID repoId,
      final String repoName)
      throws IOException, URISyntaxException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var metadataStoragePath = StoragePath.of(repoId, metadataPath.toString());

    final var resource = this.getResource(metadataStoragePath, repoName);
    final var fullMetadata = PackageUtils.readMetadataFromResource(resource);

    final var currentMetadataLength = PackageUtils.getMetadataLength(fullMetadata);

    final var distTag = PackageUtils.extractFirstDistTagFromPayload(payload);
    final var oldVersions = (Map<String, Object>) fullMetadata.get(NpmConstants.VERSIONS);
    final var newVersions = ((Map<String, Object>) payload.get(NpmConstants.VERSIONS));
    final var oldDistributionTags = (Map<String, String>) fullMetadata.get(NpmConstants.DIST_TAGS);
    final var timeField = (Map<String, String>) fullMetadata.get("time");
    final var versionPair = PackageUtils.extractVersionFromPayload(payload);

    timeField.put(versionPair.getFirst(), PackageUtils.getFormattedCurrentTime());
    timeField.put(NpmConstants.MODIFIED, PackageUtils.getFormattedCurrentTime());

    PackageUtils.fixTarballUrl(versionPair.getSecond(), repoName);

    if (distTag.getKey().equals(NpmConstants.LATEST)) { // npm publish

      PackageUtils.liftFieldsToTopLevel(payload, versionPair.getFirst());

      newVersions.putAll(oldVersions);

      oldVersions.put(versionPair.getFirst(), versionPair.getSecond());

      payload.put(NpmConstants.VERSIONS, oldVersions);
      payload.put("time", timeField);

      final var newDistributionTags = (Map<String, String>) payload.get(NpmConstants.DIST_TAGS);
      oldDistributionTags.remove(NpmConstants.LATEST);

      newDistributionTags.putAll(oldDistributionTags);

      final var newMetadataLength = PackageUtils.getMetadataLength(payload);

      return Pair.of(
          Pair.of(
              newMetadataLength - currentMetadataLength, PackageUtils.getTarballLength(payload)),
          payload);

    } else { // npm publish --tag next

      oldVersions.put(versionPair.getFirst(), versionPair.getSecond());

      oldDistributionTags.put(distTag.getKey(), distTag.getValue());

      final var newMetadataLength = PackageUtils.getMetadataLength(fullMetadata);

      fullMetadata.put("_attachments", payload.get("_attachments"));

      return Pair.of(
          Pair.of(
              newMetadataLength - currentMetadataLength, PackageUtils.getTarballLength(payload)),
          fullMetadata);
    }
  }

  @Override
  public long deletePackage(final UUID repoId, final Path packageBasePath) {

    final var storagePath = StoragePath.of(repoId, packageBasePath.toString());

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.deleteDirectory(storagePath);

    return usage;
  }

  @Override
  public Pair<String, Long> deletePackageVersion(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException {

    var latestVersion = "";

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var storagePath = StoragePath.of(repoId, metadataPath.toString());

    final var oldMetadataLength = this.calculateFileUsage(storagePath, repoName);

    final var metadata = this.getMetadata(storagePath, repoName);

    this.storageStrategy.delete(storagePath);

    final var tarballPath =
        packageBasePath.resolve(PackageUtils.getTarballFilename(packageName, versionName));

    final var tarballStoragePath = StoragePath.of(repoId, tarballPath.toString());

    final var tarballSize = this.calculateFileUsage(tarballStoragePath, repoName);

    this.storageStrategy.delete(tarballStoragePath);

    final var total = oldMetadataLength + tarballSize;

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);
    final var time = (Map<String, String>) metadata.get("time");

    versions.remove(versionName);
    time.remove(versionName);

    final var currentLatestVersion = PackageUtils.getLatestVersion(metadata);

    if (currentLatestVersion.equals(versionName)) {
      latestVersion = PackageUtils.resolveLatestVersion(metadata);
      PackageUtils.liftFieldsToTopLevel(metadata, latestVersion);

      final var distTags = (Map<String, String>) metadata.get(NpmConstants.DIST_TAGS);

      distTags.put("latest", latestVersion);
    }

    PackageUtils.removeAllTagsPointingToVersion(metadata, versionName);

    PackageUtils.getMetadataLength(metadata);

    final var metadataUsage =
        this.writeMetadataToFile(
            repoName, metadata, StoragePath.of(repoId, metadataPath.toString()));

    return Pair.of(latestVersion, total - metadataUsage.getDiskUsage());
  }

  private Resource getResource(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));
  }

  @Override
  public Map<String, Object> getMetadata(
      final UUID repoId,
      final String repoName,
      final @Nullable String scopeName,
      final String packageName,
      final boolean isAbbreviated)
      throws IOException {

    try {
      final var path =
          this.getPackageBasePath(scopeName, packageName)
              .resolve(NpmConstants.METADATA_FILENAME)
              .normalize();

      final var storagePath = StoragePath.of(repoId, path.toString());
      final var resource = this.getResource(storagePath, repoName);

      final var fullMetadata = PackageUtils.readMetadataFromResource(resource);

      return isAbbreviated ? this.createAbbreviatedMetadata(fullMetadata) : fullMetadata;

    } catch (final NoSuchFileException _) {
      throw new ItemNotFoundException("packageNotFound");
    }
  }

  @Override
  public Map<String, Object> createAbbreviatedMetadata(final Map<String, Object> fullMetadata) {

    final var abbreviatedMetadata = new LinkedHashMap<String, Object>();

    abbreviatedMetadata.put("name", fullMetadata.get("name"));
    abbreviatedMetadata.put(NpmConstants.DIST_TAGS, fullMetadata.get(NpmConstants.DIST_TAGS));

    final var timeField = (Map<String, String>) fullMetadata.get("time");

    if (timeField != null) {
      abbreviatedMetadata.put(NpmConstants.MODIFIED, timeField.get(NpmConstants.MODIFIED));
    }

    final var abbreviatedVersions = new LinkedHashMap<String, Object>();
    final var versions = (Map<String, Object>) fullMetadata.get(NpmConstants.VERSIONS);
    final var emptyHashMap = new HashMap<String, Object>();

    if (versions != null) {
      for (final var entry : versions.entrySet()) {
        final var version = (Map<String, Object>) entry.getValue();
        final var abbreviatedVersion = new LinkedHashMap<String, Object>();

        // Required fields
        abbreviatedVersion.put("name", version.get("name"));
        abbreviatedVersion.put("version", version.get("version"));
        abbreviatedVersion.put("dist", version.get("dist"));

        // Optional fields that have no default value
        if (version.get(NpmConstants.DEPRECATED) != null) {
          abbreviatedVersion.put(NpmConstants.DEPRECATED, version.get(NpmConstants.DEPRECATED));
        }
        if (version.get(NpmConstants.HAS_SHRINKWRAP) != null) {
          abbreviatedVersion.put(
              NpmConstants.HAS_SHRINKWRAP, version.get(NpmConstants.HAS_SHRINKWRAP));
        }

        // Optional fields that have default values
        abbreviatedVersion.put("dependencies", version.getOrDefault("dependencies", emptyHashMap));
        abbreviatedVersion.put(
            "devDependencies", version.getOrDefault("devDependencies", emptyHashMap));
        abbreviatedVersion.put(
            "optionalDependencies", version.getOrDefault("optionalDependencies", emptyHashMap));
        abbreviatedVersion.put(
            "peerDependencies", version.getOrDefault("peerDependencies", emptyHashMap));
        abbreviatedVersion.put(
            "bundleDependencies", version.getOrDefault("bundleDependencies", new ArrayList<>()));
        abbreviatedVersion.put("bin", version.getOrDefault("bin", emptyHashMap));
        abbreviatedVersion.put("directories", version.getOrDefault("directories", emptyHashMap));
        abbreviatedVersion.put("engines", version.getOrDefault("engines", emptyHashMap));

        abbreviatedVersions.put(entry.getKey(), abbreviatedVersion);
      }
    }

    abbreviatedMetadata.put(NpmConstants.VERSIONS, abbreviatedVersions);
    return abbreviatedMetadata;
  }

  @Override
  public Path getPackageBasePath(final @Nullable String scopeName, final String packageName) {

    if (scopeName != null) {
      return Paths.get(scopeName, packageName).normalize();
    } else {
      return Paths.get(packageName).normalize();
    }
  }

  @Override
  public String getReadmeContent(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String versionName)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var storagePath = StoragePath.of(repoId, metadataPath.toString());

    final var metadata = this.getMetadata(storagePath, repoName);

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);
    final var version = (Map<String, Object>) versions.get(versionName);

    return (String) version.get("readme");
  }

  @Override
  public Resource getTarball(
      final UUID repoId,
      final String repoName,
      final @Nullable String scopeName,
      final String packageName,
      final String filename) {

    final var tarballPath =
        this.getPackageBasePath(scopeName, packageName).resolve(filename).normalize();

    final var storagePath = StoragePath.of(repoId, tarballPath.toString());

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));
  }

  private long calculateFileUsage(final StoragePath storagePath, final String repoName)
      throws IOException {

    return this.storageStrategy.getFileUsage(storagePath, repoName);
  }

  @Override
  public Map<String, Object> getMetadata(
      final StoragePath metadataStoragePath, final String repoName) throws IOException {

    final var resource = this.getResource(metadataStoragePath, repoName);

    return PackageUtils.readMetadataFromResource(resource);
  }

  public void clearTrash() {

    this.storageStrategy.clearTrash();
  }
}
