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
package io.repsy.os.server.protocols.docker.ui.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.generated.model.ManifestListItem;
import io.repsy.os.generated.model.TagDetail;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.OrphanLayerService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.UntaggedManifestCleanupService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.utils.RepoUtils;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerApiFacade implements ProtocolApiFacade {

  private static final @NonNull String BLOBS_PATH = "blobs";
  private static final @NonNull String MANIFESTS_PATH = "manifests";

  private final @NonNull ImageTxService imageTxService;
  private final @NonNull LayerTxService layerTxService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull ManifestFileService manifestFileService;
  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull OrphanLayerService orphanLayerService;
  private final @NonNull UntaggedManifestCleanupService untaggedManifestCleanupService;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  /**
   * Deletes the rows of the repo, then its files. Not one transaction (RPS-2114): a repo with many
   * images would hold a pooled connection and row locks on REPO, DOCKER_IMAGE and DOCKER_LAYER
   * while the whole blob tree is deleted from storage, and concurrent pushes would block on the
   * repo lock. Each image is deleted in its own short transaction by {@link ImageTxService}, the
   * layers in one, and the storage delete runs with no transaction open.
   *
   * <p>A storage failure leaves the rows gone and the repo row (the caller deletes it last) in
   * place: the user repeats the delete, which finds no images and deletes the files again. A crash
   * between the steps is repaired the same way. Nothing is orphaned that a repeat cannot remove.
   */
  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {

    RepoUtils.validateRepoName(repoInfo.getName());

    final var images = this.imageTxService.findAllByRepoId(repoInfo.getStorageKey());

    for (final var image : images) {
      this.imageTxService.deleteImage(repoInfo.getStorageKey(), image.getName());
    }

    this.layerTxService.deleteAllLayers(repoInfo.getStorageKey());

    this.dockerStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  // The event is published after the DB image delete but before the storage manifest delete, and
  // that is safe: this method is @Transactional, and the listener
  // (ArtifactScanListener.onArtifactVersionDeleted) is synchronous and calls a @Transactional
  // (REQUIRED) method, so the scan cleanup joins this transaction. If deleteManifests() below
  // fails, the exception rolls back the image, tag and manifest rows and the scan rows together
  // (DockerDeleteStorageFailureIT), and the usage is only updated by the caller once this returns.
  // The storage call deletes the manifest files of one image only, so the transaction stays short.
  @Transactional
  public @NonNull BaseUsages deleteImage(
      final @NonNull RepoInfo repoInfo, final @NonNull String imageName) {

    final var imageInfo =
        this.imageTxService.getImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    final var tags = this.manifestService.findAllTags(repoInfo.getStorageKey(), imageInfo.getId());

    // Taken before the rows go: the files a manifest keeps are found through its row.
    final var manifestRefs = this.manifestFileService.findRefsOfImage(imageInfo.getId());

    this.imageTxService.deleteImage(repoInfo.getStorageKey(), imageInfo.getName());

    this.publishVersionsDeleted(repoInfo, imageInfo.getName(), tags);

    // A manifest file is shared by every image of the repo that has the manifest: only the files no
    // remaining row needs are deleted.
    final var manifestsToDeleteFileNames =
        this.manifestFileService.findUnreferencedFileNames(
            repoInfo.getStorageKey(), imageInfo.getId(), imageInfo.getName(), manifestRefs);

    final var usage =
        this.dockerStorageService.deleteManifests(repoInfo, manifestsToDeleteFileNames);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  private void publishVersionsDeleted(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String imageName,
      final @NonNull List<Tag> tags) {

    for (final var tag : tags) {
      this.eventPublisher.publishEvent(
          new ArtifactVersionDeletedEvent(
              repoInfo.getStorageKey(),
              repoInfo.getType().name(),
              repoInfo.getName(),
              imageName,
              tag.getName()));
    }
  }

  @Transactional(readOnly = true)
  public @NonNull LayerInfo getConfigLayerByImageAndDigest(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String imageName,
      final @NonNull String configDigest) {

    final var imageInfo =
        this.imageTxService.getImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    if (!this.manifestService.existsByImageIdAndConfigDigest(imageInfo.getId(), configDigest)) {
      throw new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND);
    }

    return this.layerTxService
        .findLayerInfoByRepoIdAndDigest(repoInfo.getStorageKey(), configDigest)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND));
  }

  // No transaction: it only reads storage, so it must not hold a connection while it does.
  public @NonNull String getConfig(final @NonNull RepoInfo repoInfo, final @NonNull String fileName)
      throws IOException {

    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), Paths.get(BLOBS_PATH, fileName).toString());

    final var configResource = this.getResource(repoInfo, storagePath.getRelativePath());

    return configResource.getContentAsString(StandardCharsets.UTF_8);
  }

  /**
   * Reads the manifest from the first of the file names that exists (see {@link
   * ManifestTxService#findManifestFileNamesByReference}).
   */
  public @NonNull String getManifest(
      final @NonNull RepoInfo repoInfo, final @NonNull List<String> fileNames) throws IOException {

    for (final var fileName : fileNames) {
      final var storagePath =
          StoragePath.of(repoInfo.getStorageKey(), Paths.get(MANIFESTS_PATH, fileName).toString());

      if (this.dockerStorageService.existsResource(storagePath, repoInfo.getName())) {
        return this.getResource(repoInfo, storagePath.getRelativePath())
            .getContentAsString(StandardCharsets.UTF_8);
      }
    }

    throw new ItemNotFoundException(ProtocolErrorCodes.MANIFEST_NOT_FOUND);
  }

  @Transactional(readOnly = true)
  public @NonNull TagDetail getTagDetail(
      final @NonNull UUID repoId, final @NonNull String imageName, final @NonNull String tagName) {

    final var imageInfo = this.imageTxService.getImageInfoByRepoIdAndName(repoId, imageName);

    final var tagDetail = this.manifestService.getTagDetail(repoId, imageInfo.getId(), tagName);
    tagDetail.setImageName(imageInfo.getName());

    return tagDetail;
  }

  @Transactional(readOnly = true)
  public @NonNull Page<ManifestListItem> getTagManifestsLikeName(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String imageName,
      final @NonNull String tagName,
      final @NonNull String name,
      final @NonNull Pageable pageable) {

    final var imageInfo =
        this.imageTxService.getImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);
    final var tag =
        this.manifestService.getTag(repoInfo.getStorageKey(), imageInfo.getId(), tagName);

    return this.manifestService.findManifestsByTagContainsName(tag, name, pageable);
  }

  private @NonNull Resource getResource(
      final @NonNull RepoInfo repoInfo, final @NonNull RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService
        .findResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.MANIFEST_NOT_FOUND));
  }

  /**
   * Deletes the untagged manifests of the repo, or of one image, with their files. The caller
   * refunds the returned bytes and then sweeps the layers those manifests kept alive.
   */
  public UntaggedManifestCleanupService.Result deleteUntaggedManifests(
      final @NonNull RepoInfo repoInfo, final @Nullable String imageName) {

    return this.untaggedManifestCleanupService.deleteUntagged(repoInfo, imageName);
  }

  /**
   * Deletes the layers no manifest uses and schedules their blobs for deletion.
   *
   * @return The layers whose rows are gone and whose blobs are being deleted in the background
   */
  public @NonNull List<OrphanLayerInfo> deleteOrphanLayers(final @NonNull RepoInfo repoInfo) {
    return this.orphanLayerService.deleteOrphanLayers(repoInfo);
  }

  @Override
  public void createRepo(final @NonNull UUID repoId) {
    this.dockerStorageService.createRepo(repoId);
  }
}
