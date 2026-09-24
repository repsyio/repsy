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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;

@NullMarked
public interface NpmStorageService {
  void deleteRepo(UUID repoId);

  void createRepo(UUID repoId);

  long removeDistributionTag(UUID repoId, String repoName, Path packageBasePath, String tagName)
      throws IOException;

  BaseUsages writeMetadataToFile(
      String repoName, Map<String, Object> metadata, StoragePath metadataStoragePath)
      throws IOException;

  Pair<Map<String, Object>, Long> addDistributionTag(
      UUID repoId, String repoName, Path packageBasePath, String tagName, String versionName)
      throws IOException;

  Pair<Long, Long> processPackagePayload(Map<String, Object> payload, String repoName)
      throws URISyntaxException, JacksonException;

  BaseUsages writeTarballAndMetadata(
      UUID repoId,
      String repoName,
      Map<String, Object> metadata,
      Path packageBasePath,
      String packageName,
      String versionName)
      throws IOException, URISyntaxException;

  Pair<Pair<Long, Long>, Map<String, Object>> processVersionPayload(
      Map<String, Object> payload, Path packageBasePath, UUID repoId, String repoName)
      throws IOException, URISyntaxException;

  /**
   * Reads the stored package metadata as it is, so a publish that fails can put it back.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package has no
   *     metadata
   */
  byte[] readMetadataBytes(UUID repoId, String repoName, Path packageBasePath) throws IOException;

  /**
   * Puts the package metadata back to {@code metadata}, as {@link #readMetadataBytes} returned it,
   * after a change that wrote it has to be undone.
   */
  void restoreMetadataBytes(UUID repoId, String repoName, Path packageBasePath, byte[] metadata)
      throws IOException;

  /**
   * Tells whether the tarball of the version is in storage, whether or not the database knows the
   * version.
   */
  boolean tarballExists(
      UUID repoId, String repoName, Path packageBasePath, String packageName, String versionName);

  /**
   * Removes what a publish that failed part-way left of a version it was adding: its tarball, and
   * the package metadata, which is put back to {@code previousMetadata} or, for a package the
   * publish was creating, removed. A file the publish never got to write is skipped.
   *
   * @param previousMetadata the metadata as {@link #readMetadataBytes} returned it before the
   *     publish, or {@code null} when the package did not exist
   */
  void discardPublishedVersion(
      UUID repoId,
      String repoName,
      Path packageBasePath,
      String packageName,
      String versionName,
      byte @Nullable [] previousMetadata)
      throws IOException;

  long deletePackage(UUID repoId, Path packageBasePath);

  /**
   * Removes the version from the package metadata and its tarball from storage, and tells by how
   * many bytes the package's files grew (a negative number: what was freed).
   *
   * <p>The tarball is removed last, because a removed file cannot be put back. A failure before
   * that puts the metadata back as it was and leaves the tarball. The one thing that stays undone
   * is a failure of the caller's own commit after this returned: the tarball is then gone and its
   * row remains.
   *
   * @param newLatest the version {@code latest} moves to, or {@code null} when the removed version
   *     was not the latest
   */
  long removeVersion(
      UUID repoId,
      String repoName,
      Path packageBasePath,
      String packageName,
      String versionName,
      @Nullable String newLatest)
      throws IOException;

  /**
   * Sets the {@code deprecated} message of the versions in the package metadata as it is stored,
   * and tells by how many bytes the file grew.
   *
   * @param deprecations pairs of version and message; an empty message removes the deprecation
   */
  long deprecateVersions(
      UUID repoId, String repoName, Path packageBasePath, List<Pair<String, String>> deprecations)
      throws IOException;

  Map<String, Object> getMetadata(
      UUID repoId,
      String repoName,
      @Nullable String scopeName,
      String packageName,
      boolean isAbbreviated)
      throws IOException;

  Map<String, Object> createAbbreviatedMetadata(Map<String, Object> fullMetadata);

  Path getPackageBasePath(@Nullable String scopeName, String packageName);

  @Nullable String getReadmeContent(
      UUID repoId, String repoName, Path packageBasePath, String versionName) throws IOException;

  Resource getTarball(
      UUID repoId,
      String repoName,
      @Nullable String scopeName,
      String packageName,
      String filename);

  Map<String, Object> getMetadata(StoragePath metadataStoragePath, String repoName)
      throws IOException;
}
