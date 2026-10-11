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

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.shared.storage.AbstractArtifactStorageService;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;

@Slf4j
public abstract class AbstractHelmStorageService<ID> extends AbstractArtifactStorageService
    implements HelmStorageService<ID> {

  private static final String OCI_BLOBS_PATH = "oci/blobs";

  protected AbstractHelmStorageService(final StorageStrategy storageStrategy) {
    super(storageStrategy);
  }

  @Override
  public void saveChart(
      final String repoName, final StoragePath storagePath, final InputStream chartStream) {
    this.storageStrategy.write(repoName, storagePath, chartStream);
  }

  @Override
  public Optional<Resource> findResource(final StoragePath storagePath, final String repoName)
      throws IOException {
    return this.storageStrategy.get(storagePath, repoName);
  }

  @Override
  public long deleteChart(final StoragePath storagePath, final String repoName) {
    final var usage = this.fileUsage(storagePath, repoName);
    log.debug("Deleting chart at {} ({} bytes)", storagePath, usage);
    this.storageStrategy.delete(storagePath);
    return usage;
  }

  @Override
  public long deleteChartFile(final RepoRef repo, final String filename, final String digest) {
    final var classicPath = StoragePath.of(repo.id(), HelmConstants.CHARTS_PATH + "/" + filename);
    final var classicUsage = this.fileUsage(classicPath, repo.name());
    if (classicUsage > 0) {
      log.debug("Deleting classic chart at {} ({} bytes)", classicPath, classicUsage);
      this.storageStrategy.delete(classicPath);
      return classicUsage;
    }
    final var ociPath = StoragePath.of(repo.id(), "oci/blobs/" + digest);
    final var ociUsage = this.fileUsage(ociPath, repo.name());
    if (ociUsage > 0) {
      log.debug("Deleting OCI blob at {} ({} bytes)", ociPath, ociUsage);
      this.storageStrategy.delete(ociPath);
    }
    return ociUsage;
  }

  @Override
  public long deleteManifestFile(final RepoRef repo, final String name, final String reference) {
    final var path = StoragePath.of(repo.id(), "oci/manifests/" + name + "/" + reference);
    final var usage = this.fileUsage(path, repo.name());
    if (usage > 0) {
      log.debug("Deleting OCI manifest file at {} ({} bytes)", path, usage);
      this.storageStrategy.delete(path);
    }
    return usage;
  }

  @Override
  public void clearTrash() {
    final var unused = this.storageStrategy.clearTrash();
  }

  @Override
  public String getChartRelativePath(final String name, final String version) {
    return HelmConstants.CHARTS_PATH + "/" + name + "-" + version + HelmConstants.TGZ_EXTENSION;
  }

  @Override
  public BaseUsages saveBlobChunk(
      final RepoRef repo, final UUID uploadId, final InputStream chunk) {
    final var storagePath = StoragePath.of(repo.id(), "oci/blobs/" + uploadId);
    return this.storageStrategy.appendStream(repo.name(), storagePath, chunk);
  }

  @Override
  public long getBlobSize(final RepoRef repo, final UUID uploadId) throws IOException {
    final var storagePath = StoragePath.of(repo.id(), "oci/blobs/" + uploadId);
    return this.storageStrategy.getFileUsage(storagePath, repo.name());
  }

  @Override
  public BaseUsages finalizeBlob(final UUID repoId, final UUID uploadId, final String digest) {
    final var storagePath = StoragePath.of(repoId, "oci/blobs/" + uploadId);
    return this.storageStrategy.renameObject(storagePath, digest);
  }

  @Override
  public List<StaleFile> listStaleBlobFiles(final UUID repoId, final Instant notModifiedSince) {
    return this.storageStrategy.listStaleFiles(
        StoragePath.of(repoId, OCI_BLOBS_PATH), notModifiedSince);
  }

  @Override
  public long deleteBlobFile(final RepoRef repo, final String fileName) {
    final var storagePath = StoragePath.of(repo.id(), OCI_BLOBS_PATH + "/" + fileName);
    return this.deleteFileWithUsage(storagePath, repo.name());
  }

  @Override
  public long deleteBlob(final RepoRef repo, final String digest) {
    final var storagePath = StoragePath.of(repo.id(), OCI_BLOBS_PATH + "/" + digest);
    final var blob = this.storageStrategy.get(storagePath, repo.name());
    if (blob.isEmpty()) {
      return 0;
    }
    final long usage;
    try {
      usage = blob.get().contentLength();
    } catch (final IOException e) {
      throw new ErrorOccurredException(e);
    }
    log.debug("Deleting OCI blob at {} ({} bytes)", storagePath, usage);
    this.storageStrategy.delete(storagePath);
    return usage;
  }

  @Override
  public Optional<Resource> findBlob(final RepoRef repo, final String digest) {
    final var storagePath = StoragePath.of(repo.id(), "oci/blobs/" + digest);
    return this.storageStrategy.get(storagePath, repo.name());
  }

  @Override
  public boolean blobExists(final RepoRef repo, final String digest) {
    final var resourceOpt = this.findBlob(repo, digest);
    return resourceOpt.isPresent() && resourceOpt.get().exists();
  }

  @Override
  public BaseUsages saveManifest(
      final RepoRef repo, final String name, final String reference, final byte[] content) {
    final var storagePath = StoragePath.of(repo.id(), "oci/manifests/" + name + "/" + reference);
    try (final var inputStream = new ByteArrayInputStream(content)) {
      return this.storageStrategy.write(repo.name(), storagePath, inputStream);
    } catch (final IOException e) {
      throw new RuntimeException("Failed to save OCI manifest", e);
    }
  }

  @Override
  public Optional<Resource> findManifest(
      final RepoRef repo, final String name, final String reference) {
    final var storagePath = StoragePath.of(repo.id(), "oci/manifests/" + name + "/" + reference);
    return this.storageStrategy.get(storagePath, repo.name());
  }

  @Override
  public boolean manifestExists(final RepoRef repo, final String name, final String reference) {
    final var resourceOpt = this.findManifest(repo, name, reference);
    return resourceOpt.isPresent() && resourceOpt.get().exists();
  }
}
