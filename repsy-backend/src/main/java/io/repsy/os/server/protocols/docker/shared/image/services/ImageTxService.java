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
package io.repsy.os.server.protocols.docker.shared.image.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.mappers.ImageConverter;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ImageTxService implements ImageService<UUID> {

  private final ImageConverter imageConverter;
  private final ImageRepository imageRepository;
  private final RepoRepository repoRepository;
  private final LayerRepository layerRepository;
  private final TagRepository tagRepository;

  @Override
  @Transactional
  public ImageInfo findOrCreateImage(final UUID repoId, final String imageName) {

    final var imageOpt = this.imageRepository.findByRepoIdAndName(repoId, imageName);

    if (imageOpt.isPresent()) {
      return this.imageConverter.toImageInfo(imageOpt.get());
    }

    final var repo = new Repo();
    repo.setId(repoId);

    final var image = new Image();
    image.setName(imageName);
    image.setRepo(repo);

    final var savedImage = this.imageRepository.save(image);

    return this.imageConverter.toImageInfo(savedImage);
  }

  /**
   * Recomputes the size and the digest the panel lists the image with, after a manifest was pushed
   * or a tag or a manifest was deleted: the size is that of the layers the tagged manifests reach,
   * the digest is the one of the manifest the most recently moved tag points at, and none once no
   * tag is left. Runs in the caller's transaction, so the numbers never disagree with the rows that
   * changed.
   */
  @Override
  @Transactional
  public void refreshImageSize(final UUID repoId, final UUID imageId) {

    final var digest =
        this.tagRepository
            .findFirstByImageIdOrderByLastUpdatedAtDescIdDesc(imageId)
            .map(Tag::getDigest)
            .orElse(null);

    this.storeSizeAndDigest(repoId, imageId, digest);
  }

  private void storeSizeAndDigest(
      final UUID repoId, final UUID imageId, final @Nullable String digest) {

    // Flushed first: the size is summed by a query that must see the deletes of this transaction.
    this.imageRepository.flush();

    final var totalSize = this.layerRepository.sumDistinctSizeByImageId(repoId, imageId);

    this.imageRepository.updateImageSizeAndDigest(
        repoId, imageId, digest, totalSize, Instant.now());
  }

  @Override
  @Transactional
  public void deleteImage(final UUID repoId, final String imageName) {

    final var image = this.findByRepoIdAndName(repoId, imageName);

    // The image's tags go with it through the mapping; its manifests, their layer links and their
    // index edges go through the database's ON DELETE CASCADE. Flushed here, so a caller that asks
    // which manifest digests the repo still has sees them gone.
    this.imageRepository.delete(image);
    this.imageRepository.flush();
  }

  public Page<io.repsy.os.generated.model.ImageListItem> findAllByRepoIdAndContainsName(
      final UUID repoId, final String imageName, final Pageable pageable) {

    final var repo =
        this.repoRepository
            .findById(repoId)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    return this.imageRepository
        .findAllByRepoIdAndContainsName(repo.getId(), imageName, pageable)
        .map(this.imageConverter::toDto);
  }

  @Override
  public ImageInfo findImageInfoByRepoIdAndName(final UUID repoId, final String imageName) {

    final var image = this.findByRepoIdAndName(repoId, imageName);

    return this.imageConverter.toImageInfo(image);
  }

  public List<Image> findAllByRepoId(final UUID repoId) {

    return this.imageRepository.findAllByRepoId(repoId);
  }

  private Image findByRepoIdAndName(final UUID repoId, final String imageName) {

    return this.imageRepository
        .findByRepoIdAndName(repoId, imageName)
        .orElseThrow(() -> new ItemNotFoundException("imageNotFound"));
  }
}
