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
package io.repsy.protocols.docker.shared.layer.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestLayer;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractDockerLayerRenamer<ID> {

  private static final String BLOBS_PATH = "blobs";

  private final DockerStorageService<ID> dockerStorageService;
  private final LayerService<ID> layerTxService;
  private final ObjectMapper objectMapper;

  public Map<LayerInfo, StoragePath> findLayersToRename(
      final BaseRepoInfo<ID> repoInfo, final String manifestJson) {

    final var manifestInfo = this.objectMapper.readValue(manifestJson, ManifestInfo.class);

    final var layerDigests =
        manifestInfo.getLayers().stream().map(ManifestLayer::getDigest).toList();

    final var layers =
        this.layerTxService.findAllLayerInfoByRepoIdAndDigests(repoInfo.getId(), layerDigests);

    final var configLayer =
        this.layerTxService
            .findLayerInfoByRepoIdAndDigest(repoInfo.getId(), manifestInfo.getConfig().getDigest())
            .orElseThrow(() -> new ItemNotFoundException("layerNotFound"));

    layers.add(configLayer);

    final var storagePathMap = new HashMap<LayerInfo, StoragePath>();

    for (final var layer : layers) {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(BLOBS_PATH, layer.getUuid().toString()).toString());

      storagePathMap.put(layer, storagePath);
    }

    return storagePathMap;
  }

  /**
   * Renames each layer still stored under its upload UUID to its digest.
   *
   * <p>A layer whose digest is already stored is dropped instead of renamed, and the bytes that
   * frees were charged when the layer was uploaded, so they are handed back through the returned
   * usage (zero or negative) for the caller to net against the request's own usage.
   *
   * @return the disk usage the renames changed, summed over every layer
   */
  public BaseUsages renameLayers(
      final BaseRepoInfo<ID> repoInfo, final Map<LayerInfo, StoragePath> storageMap) {

    var diskUsage = 0L;

    for (final var entry : storageMap.entrySet()) {
      final var layer = entry.getKey();
      final var storagePath = entry.getValue();

      if (this.checkLayerExistsInStorage(repoInfo, storagePath.getRelativePath(), layer)) {
        final var usages =
            this.dockerStorageService.rename(
                repoInfo.getStorageKey(), storagePath.getRelativePath(), layer.getDigest());

        diskUsage += usages.getDiskUsage();
      }
    }

    return BaseUsages.ofDisk(diskUsage);
  }

  private boolean checkLayerExistsInStorage(
      final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo) {

    final var idx = BlobDigests.indexOfDigestPrefix(relativePath.getPath());

    final var mutatedPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getDigest()
            : relativePath.getPath();

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), mutatedPath);

    final var result = this.dockerStorageService.existsResource(storagePath, repoInfo.getName());

    if (result) {
      return true;
    }

    final var mutPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getUuid().toString()
            : relativePath.getPath();

    final var sp = StoragePath.of(repoInfo.getStorageKey(), mutPath);

    return this.dockerStorageService.existsResource(sp, repoInfo.getName());
  }
}
