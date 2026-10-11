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
package io.repsy.protocols.docker.protocol.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.shared.constants.DockerConstants;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;

/**
 * Finds the files of a repo and the layer rows behind them: a blob by path, the config blob of a
 * layer (stored by digest, or by uuid before the rename), and whether a layer's file exists.
 */
@RequiredArgsConstructor
final class DockerBlobResolver<ID> {

  private final DockerStorageService<ID> dockerStorageService;
  private final LayerService<ID> layerService;

  Resource getResource(final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService
        .findResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.RESOURCE_NOT_FOUND));
  }

  String getConfig(final BaseRepoInfo<ID> repoInfo, final LayerInfo layerInfo) throws IOException {

    Resource resource;

    try {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(DockerConstants.BLOBS, layerInfo.getDigest()).toString());

      resource = this.getResource(repoInfo, storagePath.getRelativePath());
    } catch (final ItemNotFoundException _) {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(DockerConstants.BLOBS, layerInfo.getUuid().toString()).toString());

      resource = this.getResource(repoInfo, storagePath.getRelativePath());
    }

    return resource.getContentAsString(StandardCharsets.UTF_8);
  }

  LayerInfo findLayerInfoByRepoIdAndDigest(final ID repoId, final String digest) {

    return this.layerService
        .findLayerInfoByRepoIdAndDigest(repoId, digest)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND));
  }

  boolean checkLayerExistsInStorage(
      final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo) {

    final var idx = BlobDigests.indexOfDigestPrefix(relativePath.getPath());

    if (this.checkLayerForSha(idx, repoInfo, relativePath, layerInfo)) {
      return true;
    }

    return this.checkLayerForUuid(idx, repoInfo, relativePath, layerInfo);
  }

  boolean checkLayerForUuid(
      final int idx,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath,
      final LayerInfo layerInfo) {

    final var mutPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getUuid().toString()
            : relativePath.getPath();

    final var sp = StoragePath.of(repoInfo.getStorageKey(), mutPath);

    return this.dockerStorageService.existsResource(sp, repoInfo.getName());
  }

  boolean checkLayerForSha(
      final int idx,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath,
      final LayerInfo layerInfo) {

    final var mutatedPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getDigest()
            : relativePath.getPath();

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), mutatedPath);

    return this.dockerStorageService.existsResource(storagePath, repoInfo.getName());
  }

  boolean existsResource(final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    return this.dockerStorageService.existsResource(
        StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath()), repoInfo.getName());
  }

  Resource getLayerResource(
      final String digest, final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    final var idx = BlobDigests.indexOfDigestPrefix(relativePath.getPath());

    final var mutatedPath =
        idx > 0 ? relativePath.getPath().substring(0, idx) + digest : relativePath.getPath();

    return this.getResource(repoInfo, new RelativePath(mutatedPath));
  }
}
