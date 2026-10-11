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
import io.repsy.os.server.protocols.docker.shared.layer.dtos.OrphanLayerInfo;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.mappers.LayerMapper;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@AllArgsConstructor
public class LayerTxService implements LayerService<UUID> {

  private final LayerMapper layerConverter;
  private final LayerRepository layerRepository;

  @Override
  @Transactional
  public LayerInfo getOrCreate(final LayerForm layerForm, final UUID repoId) {

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
  public void update(final LayerInfo layerInfo, final UUID repoId) {

    final var layer = this.getLayer(repoId, layerInfo.getDigest());

    layer.setSize(layerInfo.getSize());
    layer.setMediaType(layerInfo.getMediaType());
    layer.setDigest(layerInfo.getDigest());

    this.layerRepository.save(layer);
  }

  @Override
  public Optional<LayerInfo> findLayerInfoByRepoIdAndDigest(
      final UUID repoId, final String digest) {

    final var layerOpt = this.layerRepository.findByRepoIdAndDigest(repoId, digest);

    return layerOpt.map(this.layerConverter::toLayerInfo);
  }

  @Override
  public void isAllExistsByRepoIdAndDigests(final UUID repoId, final List<String> digests) {

    // The count is of stored rows, one per digest, so it is compared with the distinct digests: a
    // manifest may name one blob twice (RPS-1490).
    final var distinctDigests = List.copyOf(new LinkedHashSet<>(digests));
    final var foundCount = this.layerRepository.countByRepoIdAndDigestIn(repoId, distinctDigests);

    if (foundCount != distinctDigests.size()) {
      throw new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND);
    }
  }

  @Override
  public List<LayerInfo> findAllLayerInfoByRepoIdAndDigests(
      final UUID repoId, final List<String> layerDigests) {

    // This method should return mutable list.
    final var layers = new ArrayList<LayerInfo>();

    this.layerRepository.findAllByRepoIdAndDigestIn(repoId, layerDigests).stream()
        .map(this.layerConverter::toLayerInfo)
        .forEach(layers::add);

    return layers;
  }

  @Transactional
  @SuppressWarnings("all")
  public void deleteAllLayers(final UUID repoId) {

    final var layers = this.layerRepository.findAllByRepoId(repoId);

    // Do not replace with deleteAll(), it uses aspectj
    for (final var layer : layers) {
      this.layerRepository.delete(layer);
    }
  }

  @Transactional
  public List<OrphanLayerInfo> deleteOrphanLayers(final UUID repoId) {

    final var orphans = this.layerRepository.findOrphansByRepoId(repoId);

    final var result =
        orphans.stream()
            .map(layer -> new OrphanLayerInfo(layer.getDigest(), layer.getSize()))
            .toList();

    for (final var layer : orphans) {
      this.layerRepository.delete(layer);
    }

    return result;
  }

  private Layer getLayer(final UUID repoId, final String digest) {

    return this.layerRepository
        .findByRepoIdAndDigest(repoId, digest)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND));
  }
}
