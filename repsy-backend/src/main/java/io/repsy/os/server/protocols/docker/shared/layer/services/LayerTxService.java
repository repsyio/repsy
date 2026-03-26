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
package io.repsy.os.server.protocols.docker.shared.layer.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.mappers.LayerConverter;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@AllArgsConstructor
public class LayerTxService implements LayerService<UUID> {

  private final @NonNull LayerConverter layerConverter;
  private final @NonNull LayerRepository layerRepository;

  @Override
  @Transactional
  public @NonNull LayerInfo findOrCreate(
      final @NonNull LayerForm layerForm, final @NonNull UUID repoId) {

    final var layerOpt = this.findLayerInfoByRepoIdAndDigest(repoId, layerForm.getDigest());

    if (layerOpt.isPresent()) {
      return layerOpt.get();
    }

    final var repo = new Repo();
    repo.setId(repoId);

    final var layer = new Layer();
    layer.setDigest(layerForm.getDigest());
    layer.setMediaType(layerForm.getMediaType());
    layer.setSize(layerForm.getSize());
    layer.setRepo(repo);

    return this.layerConverter.toLayerInfo(this.layerRepository.save(layer));
  }

  @Override
  @Transactional
  public void update(final @NonNull LayerInfo layerInfo, final @NonNull UUID repoId) {

    final var layer = this.getLayer(repoId, layerInfo.getDigest());

    layer.setSize(layerInfo.getSize());
    layer.setMediaType(layerInfo.getMediaType());
    layer.setDigest(layerInfo.getDigest());

    this.layerRepository.save(layer);
  }

  @Override
  public @NonNull Optional<LayerInfo> findLayerInfoByRepoIdAndDigest(
      final @NonNull UUID repoId, final @NonNull String digest) {

    final var layerOpt = this.layerRepository.findByRepoIdAndDigest(repoId, digest);

    return layerOpt.map(this.layerConverter::toLayerInfo);
  }

  @Override
  public void isAllExistsByRepoIdAndDigests(
      final @NonNull UUID repoId, final @NonNull List<String> digests) {

    final var foundCount = this.layerRepository.countByRepoIdAndDigestIn(repoId, digests);

    if (foundCount != digests.size()) {
      throw new ItemNotFoundException("layerNotFound");
    }
  }

  @Override
  public @NonNull List<LayerInfo> findAllLayerInfoByRepoIdAndDigests(
      final @NonNull UUID repoId, final @NonNull List<String> layerDigests) {

    // This method should return mutable list.
    final var layers = new ArrayList<LayerInfo>();

    this.layerRepository.findAllByRepoIdAndDigestIn(repoId, layerDigests).stream()
        .map(this.layerConverter::toLayerInfo)
        .forEach(layers::add);

    return layers;
  }

  @Transactional
  @SuppressWarnings("all")
  public void deleteAllLayers(final @NonNull UUID repoId) {

    final var layers = this.layerRepository.findAllByRepoId(repoId);

    // Do not replace with deleteAll(), it uses aspectj
    for (final var layer : layers) {
      this.layerRepository.delete(layer);
    }
  }

  private @NonNull Layer getLayer(final @NonNull UUID repoId, final @NonNull String digest) {

    return this.layerRepository
        .findByRepoIdAndDigest(repoId, digest)
        .orElseThrow(() -> new ItemNotFoundException("layerNotFound"));
  }
}
