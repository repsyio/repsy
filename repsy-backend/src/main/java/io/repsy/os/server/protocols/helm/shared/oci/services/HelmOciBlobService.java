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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciBlob;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@NullMarked
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HelmOciBlobService implements OciBlobService<UUID> {

  private final HelmOciBlobRepository helmOciBlobRepository;

  @Override
  @Transactional
  public HelmOciBlobInfo findOrCreate(final HelmOciBlobForm form, final UUID repoId) {
    return this.helmOciBlobRepository
        .findByRepoIdAndDigest(repoId, form.getDigest())
        .map(this::toDetail)
        .orElseGet(
            () -> this.toDetail(this.helmOciBlobRepository.save(this.buildEntity(form, repoId))));
  }

  @Override
  public Optional<HelmOciBlobInfo> findByDigest(final UUID repoId, final String digest) {
    return this.helmOciBlobRepository.findByRepoIdAndDigest(repoId, digest).map(this::toDetail);
  }

  @Override
  @Transactional
  public void deleteByRepoIdAndDigest(final UUID repoId, final String digest) {
    this.helmOciBlobRepository.deleteByRepoIdAndDigest(repoId, digest);
  }

  private HelmOciBlob buildEntity(final HelmOciBlobForm form, final UUID repoId) {
    final var repo = new Repo();
    repo.setId(repoId);

    final var blob = new HelmOciBlob();
    blob.setRepo(repo);
    blob.setDigest(form.getDigest());
    blob.setSize(form.getSize());
    blob.setMediaType(form.getMediaType());
    return blob;
  }

  private BlobDetail toDetail(final HelmOciBlob blob) {
    return BlobDetail.builder()
        .id(blob.getId())
        .digest(blob.getDigest())
        .size(blob.getSize())
        .mediaType(blob.getMediaType())
        .build();
  }

  @Value
  @Builder
  @NullMarked
  private static final class BlobDetail implements HelmOciBlobInfo {
    UUID id;
    String digest;
    long size;
    String mediaType;
  }
}
