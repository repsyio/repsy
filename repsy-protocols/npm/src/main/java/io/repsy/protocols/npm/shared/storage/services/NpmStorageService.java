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
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
   * after a change that wrote it has to be undone. {@code null} means there was none: a file that
   * is there now is removed.
   */
  void restoreMetadataBytes(
      UUID repoId, String repoName, Path packageBasePath, byte @Nullable [] metadata)
      throws IOException;

  /** A change to the package metadata file that reports how many bytes the file grew by. */
  @FunctionalInterface
  interface MetadataChange {

    long apply() throws IOException;
  }

  /**
   * Runs {@code change} on the package metadata file and puts the file back as it was when the
   * change fails, so the file never keeps what the rolled-back rows do not have.
   *
   * <p>A package whose metadata file is gone from storage has its rows as the only record of what
   * it holds (RPS-1300), so the file is rebuilt from them first, from {@code snapshot}, and the
   * change is made to that. Being put back then means being removed again, as the file was not
   * there before. The rebuild is a write like any other and counts in the growth that is returned.
   *
   * <p>The caller holds the package row locked, so no other write can change the file in between.
   *
   * @param snapshot the rows of the package as they are now, with the change the caller is making
   *     already in them; asked for only when the file is missing
   * @return the growth of the file: what {@code change} reports, plus the size of a rebuilt file
   */
  long changeMetadata(
      UUID repoId,
      String repoName,
      Path packageBasePath,
      Supplier<NpmPackageSnapshot> snapshot,
      MetadataChange change)
      throws IOException;

  /**
   * The package metadata as stored or, when the file is gone, as the rows {@code snapshot} gives
   * would have it written. Nothing is written.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the file is gone and
   *     {@code snapshot} finds no package either
   */
  Map<String, Object> readMetadataOrRebuild(
      UUID repoId, String repoName, Path packageBasePath, Supplier<NpmPackageSnapshot> snapshot)
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
   * <p>The package metadata is rebuilt from {@code snapshot} when its file is gone: see {@link
   * #changeMetadata}.
   *
   * @param newLatest the version {@code latest} moves to, or {@code null} when the removed version
   *     was not the latest
   * @param snapshot the rows of the package with the removal already made in them
   */
  long removeVersion(
      UUID repoId,
      String repoName,
      Path packageBasePath,
      String packageName,
      String versionName,
      @Nullable String newLatest,
      Supplier<NpmPackageSnapshot> snapshot)
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

  /**
   * Like {@link #getMetadata(UUID, String, String, String, boolean)}, but a package whose row
   * exists and whose metadata file is gone is served from its rows (RPS-1300), so a client can read
   * the package it is about to change. Nothing is written.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the file is gone and
   *     {@code snapshot} finds no package either
   */
  Map<String, Object> getMetadata(
      UUID repoId,
      String repoName,
      @Nullable String scopeName,
      String packageName,
      boolean isAbbreviated,
      Supplier<NpmPackageSnapshot> snapshot)
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
