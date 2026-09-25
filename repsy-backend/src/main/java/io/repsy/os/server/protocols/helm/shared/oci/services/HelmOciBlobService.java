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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.helm.shared.oci.entities.HelmOciBlob;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciBlobInfo;
import io.repsy.protocols.helm.shared.oci.services.OciBlobService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@NullMarked
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HelmOciBlobService implements OciBlobService<UUID> {

  private final HelmOciBlobRepository helmOciBlobRepository;

  /**
   * Returns the blob row of the digest, inserting it when this is the first upload of that blob.
   *
   * <p>Two uploads of one blob (two CI jobs pushing the same chart, or charts that share a config
   * blob) both find no row and both insert. The insert skips a row that already exists instead of
   * failing on the unique index (the loser used to be answered 409 {@code itemAlreadyExists}, which
   * an OCI client reads as {@code DENIED}); when the other upload has inserted the row but not
   * committed yet, the statement waits for it, and the second lookup then finds the committed row
   * (RPS-1342). The caller cannot repeat the transaction instead: the blob file it finalises has
   * been moved by then.
   */
  @Override
  @Transactional
  public HelmOciBlobInfo findOrCreate(final HelmOciBlobForm form, final UUID repoId) {
    final var existing = this.helmOciBlobRepository.findByRepoIdAndDigest(repoId, form.getDigest());

    if (existing.isPresent()) {
      return this.toDetail(existing.get());
    }

    this.helmOciBlobRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(),
        repoId,
        form.getDigest(),
        form.getSize(),
        form.getMediaType(),
        Instant.now());

    return this.helmOciBlobRepository
        .findByRepoIdAndDigest(repoId, form.getDigest())
        .map(this::toDetail)
        .orElseThrow(() -> new ItemNotFoundException("blobNotFound"));
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

  private BlobDetail toDetail(final HelmOciBlob blob) {
    return BlobDetail.builder()
        .id(blob.getId())
        .digest(blob.getDigest())
        .size(blob.getSize())
        .mediaType(blob.getMediaType())
        .build();
  }

  @Builder
  @NullMarked
  private record BlobDetail(UUID id, String digest, long size, String mediaType)
      implements HelmOciBlobInfo {}
}
