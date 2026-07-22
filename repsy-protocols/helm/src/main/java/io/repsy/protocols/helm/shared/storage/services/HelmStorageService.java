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
package io.repsy.protocols.helm.shared.storage.services;

import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface HelmStorageService<ID> {

  void createRepo(UUID repoUuid);

  long deleteRepo(UUID repoUuid);

  void saveChart(String repoName, StoragePath storagePath, InputStream chartStream);

  Optional<Resource> getResource(StoragePath storagePath, String repoName) throws IOException;

  long deleteChart(StoragePath storagePath, String repoName) throws IOException;

  long deleteChartFile(UUID repoUuid, String filename, String digest, String repoName)
      throws IOException;

  void deleteManifestFile(UUID repoUuid, String name, String reference, String repoName)
      throws IOException;

  void clearTrash();

  String getChartRelativePath(String name, String version);

  void saveBlobChunk(UUID repoUuid, UUID uploadId, InputStream chunk, String repoName);

  long getBlobSize(UUID repoUuid, UUID uploadId, String repoName) throws IOException;

  void finalizeBlob(UUID repoUuid, UUID uploadId, String digest);

  Optional<Resource> getBlob(UUID repoUuid, String digest, String repoName);

  boolean blobExists(UUID repoUuid, String digest, String repoName);

  void saveManifest(UUID repoUuid, String name, String reference, byte[] content, String repoName);

  Optional<Resource> getManifest(UUID repoUuid, String name, String reference, String repoName);

  boolean manifestExists(UUID repoUuid, String name, String reference, String repoName);
}
