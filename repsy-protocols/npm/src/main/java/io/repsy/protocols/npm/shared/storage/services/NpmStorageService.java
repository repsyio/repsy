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
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.core.JacksonException;

@NullMarked
public interface NpmStorageService {
  long deleteRepo(UUID repoId);

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

  long deletePackage(UUID repoId, Path packageBasePath);

  Pair<String, Long> deletePackageVersion(
      UUID repoId, String repoName, Path packageBasePath, String packageName, String versionName)
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

  String getReadmeContent(UUID repoId, String repoName, Path packageBasePath, String versionName)
      throws IOException;

  Resource getTarball(
      UUID repoId,
      String repoName,
      @Nullable String scopeName,
      String packageName,
      String filename);

  Map<String, Object> getMetadata(StoragePath metadataStoragePath, String repoName)
      throws IOException;
}
