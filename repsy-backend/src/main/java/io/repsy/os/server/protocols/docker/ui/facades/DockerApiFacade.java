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
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.generated.model.ManifestListItem;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.generated.model.RepoSettingsInfo;
import io.repsy.os.generated.model.TagDetail;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.ui.utils.RepoUtils;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull ImageTxService imageTxService;
  private final @NonNull LayerTxService layerTxService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull DockerStorageService dockerStorageService;

  public @NonNull BaseUsages deleteRepo(final @NonNull RepoInfo repoInfo) {

    RepoUtils.validateRepoName(repoInfo.getName());

    final var images = this.imageTxService.findAllByRepoId(repoInfo.getStorageKey());

    for (final var image : images) {
      this.manifestService.deleteTagsByImageInfo(repoInfo.getStorageKey(), image.getId());
      this.imageTxService.deleteImage(repoInfo.getStorageKey(), image.getName());
    }

    this.layerTxService.deleteAllLayers(repoInfo.getStorageKey());

    final var free = this.dockerStorageService.deleteRepo(repoInfo.getStorageKey());

    return BaseUsages.builder().diskUsage(-1L * free).build();
  }

  @Transactional(readOnly = true)
  public @NonNull RepoSettingsInfo getSettings(final @NonNull RepoInfo repoInfo) {

    RepoUtils.validateRepoName(repoInfo.getName());

    return RepoSettingsInfo.builder()
        .privateRepo(repoInfo.isPrivateRepo())
        .searchable(repoInfo.isSearchable())
        .releases(repoInfo.getReleases())
        .snapshots(repoInfo.getSnapshots())
        .allowOverride(repoInfo.isAllowOverride())
        .build();
  }

  public void updateSettings(
      final @NonNull RepoInfo repoInfo, final @NonNull RepoSettingsForm settings) {

    RepoUtils.validateRepoName(repoInfo.getName());

    this.repoTxService.updateSettings(repoInfo.getStorageKey(), settings);
  }

  public @NonNull BaseUsages deleteImage(
      final @NonNull RepoInfo repoInfo, final @NonNull String imageName) {

    final var imageInfo =
        this.imageTxService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    final var tags = this.manifestService.findAllTags(repoInfo.getStorageKey(), imageInfo.getId());

    final var manifestsToDeleteFileNames =
        this.findManifestsToDeleteFileNames(repoInfo, imageInfo.getId(), imageName, tags);

    this.imageTxService.deleteImage(repoInfo.getStorageKey(), imageInfo.getName());

    final var usage =
        this.dockerStorageService.deleteManifests(repoInfo, manifestsToDeleteFileNames);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  @Transactional(readOnly = true)
  public @NonNull LayerInfo findLayerByDigestAndRepoAndImageName(
      final @NonNull RepoInfo repoInfo, final @NonNull String configDigest) {

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

  public @NonNull String getManifest(
      final @NonNull RepoInfo repoInfo, final @NonNull String fileName) throws IOException {

    final var storagePath =
        StoragePath.of(repoInfo.getStorageKey(), Paths.get(MANIFESTS_PATH, fileName).toString());

    final var manifestResource = this.getResource(repoInfo, storagePath.getRelativePath());

    return manifestResource.getContentAsString(StandardCharsets.UTF_8);
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

    return this.manifestService.findManifestsByTagIdContainsName(tag.getId(), name, pageable);
  }

  private @NonNull Set<String> findManifestsToDeleteFileNames(
      final @NonNull RepoInfo repoInfo,
      final @NonNull UUID imageId,
      final @NonNull String imageName,
      final @NonNull List<Tag> tags) {

    final var manifestsToDeleteFileNames = new HashSet<String>();

    for (final Tag tag : tags) {
      final var manifestsToDelete =
          this.manifestService.findManifests(repoInfo.getStorageKey(), imageId, tag.getId());

      for (final var manifest : manifestsToDelete) {
        final var manifestName =
            ManifestNameGenerator.generate(repoInfo.getStorageKey(), imageName, manifest.getName());

        manifestsToDeleteFileNames.add(manifestName);
      }
    }

    return manifestsToDeleteFileNames;
  }

  private @NonNull Resource getResource(
      final @NonNull RepoInfo repoInfo, final @NonNull RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService
        .getResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));
  }

  public void createRepo(final @NonNull UUID repoId) {
    this.dockerStorageService.createRepo(repoId);
  }
}
