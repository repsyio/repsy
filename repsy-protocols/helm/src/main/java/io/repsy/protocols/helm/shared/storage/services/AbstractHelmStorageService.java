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
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@Slf4j
@RequiredArgsConstructor
@NullMarked
public abstract class AbstractHelmStorageService<ID> implements HelmStorageService<ID> {

  private final StorageStrategy storageStrategy;

  @Override
  public void createRepo(final UUID repoUuid) {
    this.storageStrategy.createDirectory(repoUuid.toString());
  }

  @Override
  public long deleteRepo(final UUID repoUuid) {
    final var storagePath = StoragePath.of(repoUuid);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);
    this.storageStrategy.deleteDirectory(storagePath);
    return usage;
  }

  @Override
  public void saveChart(
      final String repoName, final StoragePath storagePath, final InputStream chartStream) {
    this.storageStrategy.write(repoName, storagePath, chartStream);
  }

  @Override
  public Optional<Resource> getResource(final StoragePath storagePath, final String repoName)
      throws IOException {
    return this.storageStrategy.get(storagePath, repoName);
  }

  @Override
  public long deleteChart(final StoragePath storagePath, final String repoName) throws IOException {
    final var usage = this.storageStrategy.getFileUsage(storagePath, repoName);
    log.debug("Deleting chart at {} ({} bytes)", storagePath, usage);
    this.storageStrategy.delete(storagePath);
    return usage;
  }

  @Override
  public long deleteChartFile(
      final UUID repoUuid, final String filename, final String digest, final String repoName)
      throws IOException {
    final var classicPath = StoragePath.of(repoUuid, HelmConstants.CHARTS_PATH + "/" + filename);
    final var classicUsage = this.storageStrategy.getFileUsage(classicPath, repoName);
    if (classicUsage > 0) {
      log.debug("Deleting classic chart at {} ({} bytes)", classicPath, classicUsage);
      this.storageStrategy.delete(classicPath);
      return classicUsage;
    }
    final var ociPath = StoragePath.of(repoUuid, "oci/blobs/" + digest);
    final var ociUsage = this.storageStrategy.getFileUsage(ociPath, repoName);
    if (ociUsage > 0) {
      log.debug("Deleting OCI blob at {} ({} bytes)", ociPath, ociUsage);
      this.storageStrategy.delete(ociPath);
    }
    return ociUsage;
  }

  @Override
  public void deleteManifestFile(
      final UUID repoUuid, final String name, final String reference, final String repoName)
      throws IOException {
    final var path = StoragePath.of(repoUuid, "oci/manifests/" + name + "/" + reference);
    final var usage = this.storageStrategy.getFileUsage(path, repoName);
    if (usage > 0) {
      log.debug("Deleting OCI manifest file at {} ({} bytes)", path, usage);
      this.storageStrategy.delete(path);
    }
  }

  @Override
  public void clearTrash() {
    this.storageStrategy.clearTrash();
  }

  @Override
  public void saveBlobChunk(
      final UUID repoUuid, final UUID uploadId, final InputStream chunk, final String repoName) {
    final var storagePath = StoragePath.of(repoUuid, "oci/blobs/" + uploadId);
    this.storageStrategy.write(repoName, storagePath, chunk);
  }

  @Override
  public long getBlobSize(final UUID repoUuid, final UUID uploadId, final String repoName)
      throws IOException {
    final var storagePath = StoragePath.of(repoUuid, "oci/blobs/" + uploadId);
    return this.storageStrategy.getFileUsage(storagePath, repoName);
  }

  @Override
  public void finalizeBlob(final UUID repoUuid, final UUID uploadId, final String digest) {
    final var storagePath = StoragePath.of(repoUuid, "oci/blobs/" + uploadId);
    this.storageStrategy.renameObject(storagePath, digest);
  }

  @Override
  public Optional<Resource> getBlob(
      final UUID repoUuid, final String digest, final String repoName) {
    final var storagePath = StoragePath.of(repoUuid, "oci/blobs/" + digest);
    return this.storageStrategy.get(storagePath, repoName);
  }

  @Override
  public boolean blobExists(final UUID repoUuid, final String digest, final String repoName) {
    final var resourceOpt = this.getBlob(repoUuid, digest, repoName);
    return resourceOpt.isPresent() && resourceOpt.get().exists();
  }

  @Override
  public void saveManifest(
      final UUID repoUuid,
      final String name,
      final String reference,
      final byte[] content,
      final String repoName) {
    final var storagePath = StoragePath.of(repoUuid, "oci/manifests/" + name + "/" + reference);
    try (final var inputStream = new ByteArrayInputStream(content)) {
      this.storageStrategy.write(repoName, storagePath, inputStream);
    } catch (final IOException e) {
      throw new RuntimeException("Failed to save OCI manifest", e);
    }
  }

  @Override
  public Optional<Resource> getManifest(
      final UUID repoUuid, final String name, final String reference, final String repoName) {
    final var storagePath = StoragePath.of(repoUuid, "oci/manifests/" + name + "/" + reference);
    return this.storageStrategy.get(storagePath, repoName);
  }

  @Override
  public boolean manifestExists(
      final UUID repoUuid, final String name, final String reference, final String repoName) {
    final var resourceOpt = this.getManifest(repoUuid, name, reference, repoName);
    return resourceOpt.isPresent() && resourceOpt.get().exists();
  }
}
