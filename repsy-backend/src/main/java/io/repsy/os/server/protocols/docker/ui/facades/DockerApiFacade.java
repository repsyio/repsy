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
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.OrphanLayerCleanupService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.ui.utils.RepoUtils;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DockerApiFacade implements ProtocolApiFacade {

  private static final @NonNull String BLOBS_PATH = "blobs";
  private static final @NonNull String MANIFESTS_PATH = "manifests";

  private final @NonNull ImageTxService imageTxService;
  private final @NonNull LayerTxService layerTxService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull ManifestFileService manifestFileService;
  private final @NonNull DockerStorageService dockerStorageService;
  private final @NonNull OrphanLayerCleanupService orphanLayerCleanupService;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Override
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {

    RepoUtils.validateRepoName(repoInfo.getName());

    final var images = this.imageTxService.findAllByRepoId(repoInfo.getStorageKey());

    for (final var image : images) {
      this.imageTxService.deleteImage(repoInfo.getStorageKey(), image.getName());
    }

    this.layerTxService.deleteAllLayers(repoInfo.getStorageKey());

    this.dockerStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  // Event is published after the DB image delete but before the storage manifest delete. If
  // deleteManifests() below fails and the transaction rolls back, the DB image record comes
  // back, but the VulnerabilityScan cleanup triggered by this event has already committed
  // (ArtifactScanListener.handleArtifactVersionDeleted runs synchronously, in its own
  // transaction) and will not be undone — a known limitation inherited from the equivalent
  // repsy-cloud code path, out of scope for this change.
  public @NonNull BaseUsages deleteImage(
      final @NonNull RepoInfo repoInfo, final @NonNull String imageName) {

    final var imageInfo =
        this.imageTxService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

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
  public @NonNull LayerInfo findConfigLayerByImageAndDigest(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String imageName,
      final @NonNull String configDigest) {

    final var imageInfo =
        this.imageTxService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    if (!this.manifestService.existsByImageIdAndConfigDigest(imageInfo.getId(), configDigest)) {
      throw new ItemNotFoundException("layerNotFound");
    }

    return this.layerTxService
        .findLayerInfoByRepoIdAndDigest(repoInfo.getStorageKey(), configDigest)
        .orElseThrow(() -> new ItemNotFoundException("layerNotFound"));
  }

  @Transactional(readOnly = true)
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

    throw new ItemNotFoundException("manifestNotFound");
  }

  @Transactional(readOnly = true)
  public @NonNull TagDetail getTagDetail(
      final @NonNull UUID repoId, final @NonNull String imageName, final @NonNull String tagName) {

    final var imageInfo = this.imageTxService.findImageInfoByRepoIdAndName(repoId, imageName);

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
        this.imageTxService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);
    final var tag =
        this.manifestService.findTag(repoInfo.getStorageKey(), imageInfo.getId(), tagName);

    return this.manifestService.findManifestsByTagContainsName(tag, name, pageable);
  }

  private @NonNull Resource getResource(
      final @NonNull RepoInfo repoInfo, final @NonNull RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService
        .getResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));
  }

  public void deleteOrphanLayers(final @NonNull RepoInfo repoInfo) {

    // The rows go first, in their own transaction, so a concurrent push cannot re-reference a row
    // whose blob is about to be deleted. The price: a blob whose delete fails stays on disk, still
    // charged to the repo and unreachable from the DB. AbandonedBlobUploadCleanupService sweeps it
    // once it is older than the TTL: it also collects a digest-named blob with no docker_layer row,
    // not just UUID-named upload files (RPS-1172).
    final var orphans = this.layerTxService.deleteOrphanLayers(repoInfo.getStorageKey());

    this.orphanLayerCleanupService.cleanupBlobs(repoInfo.getStorageKey(), orphans);
  }

  @Override
  public void createRepo(final @NonNull UUID repoId) {
    this.dockerStorageService.createRepo(repoId);
  }
}
