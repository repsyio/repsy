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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.Resource;

public interface HelmStorageService<ID> {

  void createRepo(UUID repoId);

  void deleteRepo(UUID repoId);

  void saveChart(String repoName, StoragePath storagePath, InputStream chartStream);

  Optional<Resource> findResource(StoragePath storagePath, String repoName) throws IOException;

  long deleteChart(StoragePath storagePath, String repoName);

  long deleteChartFile(RepoRef repo, String filename, String digest);

  /**
   * Deletes the stored manifest file and answers the bytes it held (zero when there was none), so
   * the caller can release them from the repo's disk usage.
   */
  long deleteManifestFile(RepoRef repo, String name, String reference);

  void clearTrash();

  String getChartRelativePath(String name, String version);

  /**
   * Appends a chunk to the upload's temp file, so a blob sent in several {@code PATCH} requests is
   * stored whole. Answers the appended bytes as the disk usage change.
   */
  BaseUsages saveBlobChunk(RepoRef repo, UUID uploadId, InputStream chunk);

  long getBlobSize(RepoRef repo, UUID uploadId) throws IOException;

  BaseUsages finalizeBlob(UUID repoId, UUID uploadId, String digest);

  /**
   * Lists the files under the repo's {@code oci/blobs} directory that were last written before
   * {@code notModifiedSince}: finalized blobs (named by digest) and upload temp files (named by
   * upload session) alike, so the caller has to tell them apart.
   */
  List<StaleFile> listStaleBlobFiles(UUID repoId, Instant notModifiedSince);

  /**
   * Deletes one file of the repo's {@code oci/blobs} directory and answers the bytes it held, so
   * the caller can release them from the repo's disk usage.
   */
  long deleteBlobFile(RepoRef repo, String fileName);

  /**
   * Deletes the finalized blob stored under {@code digest} and answers the bytes it held, or zero
   * when there is no such blob.
   */
  long deleteBlob(RepoRef repo, String digest);

  Optional<Resource> findBlob(RepoRef repo, String digest);

  boolean blobExists(RepoRef repo, String digest);

  /**
   * Writes the manifest file and answers the disk usage it changed: its size for a new file, the
   * difference when a manifest of the same reference is replaced.
   */
  BaseUsages saveManifest(RepoRef repo, String name, String reference, byte[] content);

  Optional<Resource> findManifest(RepoRef repo, String name, String reference);

  boolean manifestExists(RepoRef repo, String name, String reference);
}
