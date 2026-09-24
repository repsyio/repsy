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
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.utils.NpmConstants;
import io.repsy.protocols.npm.shared.utils.NpmPackumentBuilder;
import io.repsy.protocols.npm.shared.utils.NpmTarballFacts;
import io.repsy.protocols.npm.shared.utils.NpmTarballInspector;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
@NullMarked
public abstract class AbstractNpmStorageService implements NpmStorageService {

  private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

  private final StorageStrategy storageStrategy;

  @Override
  public void deleteRepo(final UUID repoId) {
    final var storagePath = StoragePath.of(repoId);
    this.storageStrategy.delete(storagePath);
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

    this.fixTarballUrl(versionPair.getSecond(), repoName);

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
      final String repoName,
      final Supplier<NpmPackageSnapshot> snapshot)
      throws IOException, URISyntaxException {

    // A file that is gone or corrupt is rebuilt from the rows (RPS-1310), which the publish has
    // already written: the version being added is in them, without a tarball yet, and is replaced
    // by the payload's own entry below.
    final var fullMetadata =
        this.readMetadataOrRebuild(repoId, repoName, packageBasePath, snapshot);

    final var currentMetadataLength = PackageUtils.getMetadataLength(fullMetadata);

    final var distTag = PackageUtils.extractFirstDistTagFromPayload(payload);
    final var oldVersions = (Map<String, Object>) fullMetadata.get(NpmConstants.VERSIONS);
    final var newVersions = ((Map<String, Object>) payload.get(NpmConstants.VERSIONS));
    final var oldDistributionTags = (Map<String, String>) fullMetadata.get(NpmConstants.DIST_TAGS);
    final var timeField = (Map<String, String>) fullMetadata.get("time");
    final var versionPair = PackageUtils.extractVersionFromPayload(payload);

    timeField.put(versionPair.getFirst(), PackageUtils.getFormattedCurrentTime());
    timeField.put(NpmConstants.MODIFIED, PackageUtils.getFormattedCurrentTime());

    this.fixTarballUrl(versionPair.getSecond(), repoName);

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
  public byte @Nullable [] readMetadataBytes(
      final UUID repoId, final String repoName, final Path packageBasePath) throws IOException {

    return this.readMetadataBytesIfPresent(repoId, repoName, packageBasePath);
  }

  /** The stored package metadata as it is, or {@code null} when the file is gone. */
  private byte @Nullable [] readMetadataBytesIfPresent(
      final UUID repoId, final String repoName, final Path packageBasePath) throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var resource =
        this.storageStrategy.get(StoragePath.of(repoId, metadataPath.toString()), repoName);

    if (resource.isEmpty()) {
      return null;
    }

    try (final var inputStream = resource.get().getInputStream()) {
      return inputStream.readAllBytes();
    }
  }

  @Override
  public void restoreMetadataBytes(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final byte @Nullable [] metadata)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var storagePath = StoragePath.of(repoId, metadataPath.toString());

    if (metadata == null) {
      this.deleteIfPresent(storagePath, repoName);
      return;
    }

    try (final var inputStream = new ByteArrayInputStream(metadata)) {
      this.storageStrategy.write(repoName, storagePath, inputStream);
    }
  }

  @Override
  public long changeMetadata(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final Supplier<NpmPackageSnapshot> snapshot,
      final MetadataChange change)
      throws IOException {

    final var previousMetadata = this.readMetadataBytesIfPresent(repoId, repoName, packageBasePath);
    final var rebuiltSize =
        this.usableMetadata(previousMetadata, repoName, packageBasePath) == null
            ? this.writeRebuiltMetadata(repoId, repoName, packageBasePath, snapshot.get())
            : 0L;

    try {
      return rebuiltSize + change.apply();
    } catch (final IOException | RuntimeException e) {
      this.restoreAfterFailedChange(repoId, repoName, packageBasePath, previousMetadata, e);
      throw e;
    }
  }

  private long writeRebuiltMetadata(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final NpmPackageSnapshot snapshot)
      throws IOException {

    final var metadata = this.rebuildMetadata(repoId, repoName, snapshot);
    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);

    // The same atomic write as any other change of the file: nothing half-written is ever served.
    return this.writeMetadataToFile(
            repoName, metadata, StoragePath.of(repoId, metadataPath.toString()))
        .getDiskUsage();
  }

  @Override
  public Map<String, Object> readMetadataOrRebuild(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final Supplier<NpmPackageSnapshot> snapshot)
      throws IOException {

    final var stored =
        this.usableMetadata(
            this.readMetadataBytesIfPresent(repoId, repoName, packageBasePath),
            repoName,
            packageBasePath);

    return stored != null ? stored : this.rebuildMetadata(repoId, repoName, snapshot.get());
  }

  /**
   * The metadata as stored, or {@code null} when there is none to use: the file is gone ({@code
   * bytes} is {@code null}) or it is corrupt. A corrupt file is worth a warning, unlike a missing
   * one: nothing removes it but a fault, and the rows that stand in for it cannot bring back what
   * only the file held (see {@link NpmPackumentBuilder}).
   */
  private @Nullable Map<String, Object> usableMetadata(
      final byte @Nullable [] bytes, final String repoName, final Path packageBasePath) {

    if (bytes == null) {
      return null;
    }

    final var metadata = this.parseMetadata(bytes, repoName, packageBasePath);

    if (metadata == null) {
      return null;
    }

    if (!hasPackumentShape(metadata)) {
      log.warn(
          "The {} of npm package {} in repo {} has no versions, dist-tags and time: its rows are"
              + " used instead",
          NpmConstants.METADATA_FILENAME,
          packageBasePath,
          repoName);
      return null;
    }

    return metadata;
  }

  private @Nullable Map<String, Object> parseStoredMetadata(
      final byte @Nullable [] bytes, final String repoName, final Path packageBasePath) {

    return bytes == null ? null : this.parseMetadata(bytes, repoName, packageBasePath);
  }

  /**
   * The metadata as a map, or {@code null} (with a warning) when the bytes are not a JSON object.
   */
  private @Nullable Map<String, Object> parseMetadata(
      final byte[] bytes, final String repoName, final Path packageBasePath) {

    try {
      final var metadata =
          METADATA_MAPPER.readValue(bytes, new TypeReference<Map<String, Object>>() {});

      if (metadata != null) {
        return metadata;
      }
    } catch (final JacksonException e) {
      log.warn(
          "The {} of npm package {} in repo {} is corrupt ({}): its rows are used instead",
          NpmConstants.METADATA_FILENAME,
          packageBasePath,
          repoName,
          e.getOriginalMessage());
      return null;
    }

    log.warn(
        "The {} of npm package {} in repo {} is corrupt (it holds no object): its rows are used"
            + " instead",
        NpmConstants.METADATA_FILENAME,
        packageBasePath,
        repoName);

    return null;
  }

  /** What every change of the metadata relies on being there. */
  private static boolean hasPackumentShape(final Map<String, Object> metadata) {

    return metadata.get(NpmConstants.VERSIONS) instanceof Map
        && metadata.get(NpmConstants.DIST_TAGS) instanceof Map
        && metadata.get("time") instanceof Map;
  }

  /**
   * Builds the metadata of the package from its rows, reading the tarball of each version for what
   * the rows do not keep: see {@link NpmPackumentBuilder}. Writes nothing.
   */
  private Map<String, Object> rebuildMetadata(
      final UUID repoId, final String repoName, final NpmPackageSnapshot snapshot)
      throws IOException {

    final var packageBasePath = this.getPackageBasePath(snapshot.scope(), snapshot.name());
    final var tarballs = new HashMap<String, NpmTarballFacts>();

    for (final var version : snapshot.versions()) {
      this.inspectTarball(repoId, repoName, packageBasePath, snapshot.name(), version.version())
          .ifPresent(facts -> tarballs.put(version.version(), facts));
    }

    return NpmPackumentBuilder.build(
        snapshot, tarballs, this.packageUrl(repoName, snapshot), Instant.now());
  }

  private Optional<NpmTarballFacts> inspectTarball(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException {

    final var tarballPath =
        packageBasePath.resolve(PackageUtils.getTarballFilename(packageName, versionName));
    final var resource =
        this.storageStrategy.get(StoragePath.of(repoId, tarballPath.toString()), repoName);

    if (resource.isEmpty()) {
      return Optional.empty();
    }

    try (final var inputStream = resource.get().getInputStream()) {
      return Optional.of(NpmTarballInspector.inspect(inputStream));
    }
  }

  private @Nullable String packageUrl(final String repoName, final NpmPackageSnapshot snapshot) {

    final var base = this.registryBaseUrl();

    if (base == null || base.isBlank()) {
      return null;
    }

    return base.replaceAll("/+$", "")
        + "/"
        + repoName
        + "/"
        + PackageUtils.buildFullName(snapshot.scope(), snapshot.name());
  }

  /**
   * The address clients reach the registry at (scheme, host and port, without the repo), from which
   * the {@code dist.tarball} of a rebuilt version follows. The database does not keep it, and it
   * depends on how a client got here, so it is up to the deployment: {@code null} (the default)
   * leaves {@code dist.tarball} out of a rebuilt version.
   */
  protected @Nullable String registryBaseUrl() {

    return null;
  }

  @Override
  public boolean tarballExists(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName) {

    final var tarballPath =
        packageBasePath.resolve(PackageUtils.getTarballFilename(packageName, versionName));

    return this.storageStrategy
        .get(StoragePath.of(repoId, tarballPath.toString()), repoName)
        .isPresent();
  }

  @Override
  public void discardPublishedVersion(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final byte @Nullable [] previousMetadata)
      throws IOException {

    final var tarballPath =
        packageBasePath.resolve(PackageUtils.getTarballFilename(packageName, versionName));
    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var metadataStoragePath = StoragePath.of(repoId, metadataPath.toString());

    this.deleteIfPresent(StoragePath.of(repoId, tarballPath.toString()), repoName);

    if (previousMetadata == null) {
      this.deleteIfPresent(metadataStoragePath, repoName);
      return;
    }

    try (final var inputStream = new ByteArrayInputStream(previousMetadata)) {
      this.storageStrategy.write(repoName, metadataStoragePath, inputStream);
    }
  }

  private void deleteIfPresent(final StoragePath storagePath, final String repoName) {

    if (this.storageStrategy.get(storagePath, repoName).isPresent()) {
      this.storageStrategy.delete(storagePath);
    }
  }

  @Override
  public long deletePackage(final UUID repoId, final Path packageBasePath) {

    final var storagePath = StoragePath.of(repoId, packageBasePath.toString());

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);

    this.storageStrategy.delete(storagePath);

    return usage;
  }

  @Override
  public long removeVersion(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final @Nullable String newLatest,
      final Supplier<NpmPackageSnapshot> snapshot)
      throws IOException {

    return this.changeMetadata(
        repoId,
        repoName,
        packageBasePath,
        snapshot,
        () ->
            this.removeVersionAndTarball(
                repoId, repoName, packageBasePath, packageName, versionName, newLatest));
  }

  /**
   * Rewrites the metadata and removes the tarball. The tarball goes last: a removed file cannot be
   * put back, so nothing that can still fail runs after it. Whatever fails before it leaves the
   * tarball, and {@link #changeMetadata} puts the metadata back.
   */
  private long removeVersionAndTarball(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName,
      final @Nullable String newLatest)
      throws IOException {

    final var metadataGrowth =
        this.removeVersionFromMetadata(repoId, repoName, packageBasePath, versionName, newLatest);

    final var tarballSize =
        this.removeTarball(repoId, repoName, packageBasePath, packageName, versionName);

    return metadataGrowth - tarballSize;
  }

  private void restoreAfterFailedChange(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final byte @Nullable [] previousMetadata,
      final Exception cause) {

    try {
      this.restoreMetadataBytes(repoId, repoName, packageBasePath, previousMetadata);
    } catch (final IOException | RuntimeException e) {
      // The change's own failure is the one to report; the leftover is noted on it.
      cause.addSuppressed(e);
    }
  }

  private long removeVersionFromMetadata(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String versionName,
      final @Nullable String newLatest)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var storagePath = StoragePath.of(repoId, metadataPath.toString());

    final var metadata = this.getMetadata(storagePath, repoName);

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);
    final var time = (Map<String, String>) metadata.get("time");

    versions.remove(versionName);
    time.remove(versionName);

    if (newLatest != null) {
      PackageUtils.liftFieldsToTopLevel(metadata, newLatest);

      final var distTags = (Map<String, String>) metadata.get(NpmConstants.DIST_TAGS);

      distTags.put(NpmConstants.LATEST, newLatest);
    }

    PackageUtils.removeAllTagsPointingToVersion(metadata, versionName);

    return this.writeMetadataToFile(repoName, metadata, storagePath).getDiskUsage();
  }

  /** Removes the tarball, if there is one, and tells how many bytes it took. */
  private long removeTarball(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException {

    final var tarballPath =
        packageBasePath.resolve(PackageUtils.getTarballFilename(packageName, versionName));
    final var tarballStoragePath = StoragePath.of(repoId, tarballPath.toString());

    final var tarballSize = this.calculateFileUsage(tarballStoragePath, repoName);

    // A version whose tarball is already gone (an interrupted removal) can still be removed.
    this.deleteIfPresent(tarballStoragePath, repoName);

    return tarballSize;
  }

  @Override
  public long deprecateVersions(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final List<Pair<String, String>> deprecations)
      throws IOException {

    final var metadataPath = packageBasePath.resolve(NpmConstants.METADATA_FILENAME);
    final var storagePath = StoragePath.of(repoId, metadataPath.toString());

    final var metadata = this.getMetadata(storagePath, repoName);
    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);

    // Applied to the metadata as it is now, not replaced by what the client sent: the client read
    // its copy earlier, and a version published since would be dropped by that replacement.
    for (final var deprecation : deprecations) {
      final var version = (Map<String, Object>) versions.get(deprecation.getFirst());

      if (version != null) {
        version.put(NpmConstants.DEPRECATED, deprecation.getSecond());
      }
    }

    PackageUtils.updateModifiedTime(metadata);

    return this.writeMetadataToFile(repoName, metadata, storagePath).getDiskUsage();
  }

  /**
   * Rewrites the freshly published version's {@code dist.tarball}, once the repo name is known.
   *
   * <p>Delegates to {@link PackageUtils#fixTarballUrl(Map)}, which normalizes only the filename
   * after {@code /-/} and otherwise leaves the client-computed URL untouched. {@code repoName} is
   * unused on Repsy OS: the URL npm computes there already carries the correct (and only) repo
   * segment. The parameter, and this seam, exist so a subclass that serves a URL layout where the
   * repo name is not already part of the client's path (for example a multi-tenant registry) can
   * override just this method instead of reintroducing a positional splice into the shared {@link
   * PackageUtils}.
   */
  protected void fixTarballUrl(final Map<String, Object> version, final String repoName)
      throws URISyntaxException {

    PackageUtils.fixTarballUrl(version);
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
  public Map<String, Object> getMetadata(
      final UUID repoId,
      final String repoName,
      final @Nullable String scopeName,
      final String packageName,
      final boolean isAbbreviated,
      final Supplier<NpmPackageSnapshot> snapshot)
      throws IOException {

    final var packageBasePath = this.getPackageBasePath(scopeName, packageName);
    final var stored =
        this.usableMetadata(
            this.readMetadataBytesIfPresent(repoId, repoName, packageBasePath),
            repoName,
            packageBasePath);
    final var metadata =
        stored != null
            ? stored
            : this.rebuildIfPackageExists(
                repoId, repoName, snapshot, new ItemNotFoundException("itemNotFound"));

    return isAbbreviated ? this.createAbbreviatedMetadata(metadata) : metadata;
  }

  /** Rebuilds the metadata of a package the rows know, and otherwise fails as the read did. */
  private Map<String, Object> rebuildIfPackageExists(
      final UUID repoId,
      final String repoName,
      final Supplier<NpmPackageSnapshot> snapshot,
      final ItemNotFoundException missing)
      throws IOException {

    final NpmPackageSnapshot rows;

    try {
      rows = snapshot.get();
    } catch (final ItemNotFoundException _) {
      throw missing;
    }

    return this.rebuildMetadata(repoId, repoName, rows);
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
  public @Nullable String getReadmeContent(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final String versionName)
      throws IOException {

    // A metadata.json that is gone or corrupt has no readme to give, and the rows cannot bring one
    // back: a readme is not among them, so a rebuild (RPS-1300) would leave it out too, at the cost
    // of reading every tarball to build a page that shows one version. The page renders without a
    // README, like for the three cases below (RPS-1143), where one version's entry is incomplete
    // for example after a partial publish or a manual storage edit. The caller has found the
    // version in the database, so a file missing for it is what RPS-1300 handles everywhere else,
    // and no longer a broken storage worth an error (RPS-1310).
    final var metadata =
        this.parseStoredMetadata(
            this.readMetadataBytesIfPresent(repoId, repoName, packageBasePath),
            repoName,
            packageBasePath);

    if (metadata == null) {
      return null;
    }

    final var versions = (Map<String, Object>) metadata.get(NpmConstants.VERSIONS);

    if (versions == null) {
      return null;
    }

    final var version = (Map<String, Object>) versions.get(versionName);

    if (version == null) {
      return null;
    }

    final var readme = version.get("readme");

    return readme instanceof String readmeContent ? readmeContent : null;
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

    final var unused = this.storageStrategy.clearTrash();
  }
}
