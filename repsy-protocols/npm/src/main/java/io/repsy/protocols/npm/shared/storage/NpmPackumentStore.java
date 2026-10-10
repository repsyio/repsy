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
package io.repsy.protocols.npm.shared.storage;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.npm.shared.constants.NpmConstants;
import io.repsy.protocols.npm.shared.utils.NpmMetadataUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code metadata.json} (the packument) of an npm package in storage: reading its bytes,
 * judging whether they are usable, and writing it back. What to do when it is not usable (rebuild
 * it from the rows) is {@link NpmPackumentRebuilder}'s.
 */
@Slf4j
@NullMarked
public final class NpmPackumentStore {

  private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

  private final StorageStrategy storageStrategy;

  public NpmPackumentStore(final StorageStrategy storageStrategy) {
    this.storageStrategy = storageStrategy;
  }

  /** The storage path of the packument of the package at {@code packageBasePath}. */
  public static StoragePath storagePath(final UUID repoId, final Path packageBasePath) {

    return StoragePath.of(
        repoId, packageBasePath.resolve(NpmConstants.METADATA_FILENAME).toString());
  }

  /** The stored package metadata as it is, or {@code null} when the file is gone. */
  public byte @Nullable [] readBytes(
      final UUID repoId, final String repoName, final Path packageBasePath) throws IOException {

    final var resource = this.storageStrategy.get(storagePath(repoId, packageBasePath), repoName);

    if (resource.isEmpty()) {
      return null;
    }

    try (final var inputStream = resource.get().getInputStream()) {
      return inputStream.readAllBytes();
    }
  }

  /** Puts the bytes back as the packument, or removes it when there were none. */
  public void restoreBytes(
      final UUID repoId,
      final String repoName,
      final Path packageBasePath,
      final byte @Nullable [] metadata)
      throws IOException {

    final var storagePath = storagePath(repoId, packageBasePath);

    if (metadata == null) {
      if (this.storageStrategy.get(storagePath, repoName).isPresent()) {
        this.storageStrategy.delete(storagePath);
      }
      return;
    }

    try (final var inputStream = new ByteArrayInputStream(metadata)) {
      this.storageStrategy.write(repoName, storagePath, inputStream);
    }
  }

  public BaseUsages write(
      final String repoName,
      final Map<String, Object> metadata,
      final StoragePath metadataStoragePath)
      throws IOException {

    // Every write of a packument (a publish, a dist-tag, a deprecation, an unpublish) leaves the
    // publish-only fields out, so a packument that an earlier version of the registry stored with
    // the base64 copy of a tarball gives that space back on its next change (RPS-1390). Reads
    // already filter them (RPS-1357).
    NpmMetadataUtils.removePublishOnlyFields(metadata);

    final var mapper = new ObjectMapper();

    final var metadataBytes = mapper.writeValueAsBytes(metadata);

    try (final var byteArrayInputStream = new ByteArrayInputStream(metadataBytes)) {
      return this.storageStrategy.write(repoName, metadataStoragePath, byteArrayInputStream);
    }
  }

  /** The file at the path, which is not found when it is not in storage. */
  public Resource require(final StoragePath storagePath, final String repoName) {

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ITEM_NOT_FOUND));
  }

  /** The packument at the path as a map, which is not found when it is not in storage. */
  public Map<String, Object> read(final StoragePath metadataStoragePath, final String repoName)
      throws IOException {

    return NpmMetadataUtils.readMetadataFromResource(this.require(metadataStoragePath, repoName));
  }

  /**
   * The metadata as stored, or {@code null} when there is none to use: the file is gone ({@code
   * bytes} is {@code null}) or it is corrupt. A corrupt file is worth a warning, unlike a missing
   * one: nothing removes it but a fault, and the rows that stand in for it cannot bring back what
   * only the file held (see {@code NpmPackumentBuilder}).
   */
  public @Nullable Map<String, Object> usable(
      final byte @Nullable [] bytes, final String repoName, final Path packageBasePath) {

    if (bytes == null) {
      return null;
    }

    final var metadata = this.parse(bytes, repoName, packageBasePath);

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

  /** The metadata as a map, whatever its shape, or {@code null} when none is stored or readable. */
  public @Nullable Map<String, Object> parseStored(
      final byte @Nullable [] bytes, final String repoName, final Path packageBasePath) {

    return bytes == null ? null : this.parse(bytes, repoName, packageBasePath);
  }

  /**
   * The metadata as a map, or {@code null} (with a warning) when the bytes are not a JSON object.
   */
  private @Nullable Map<String, Object> parse(
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
}
