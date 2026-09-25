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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.mappers.ImageConverter;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.docker.shared.image.exceptions.ImageDeletedException;
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
  private final ManifestRepository manifestRepository;

  /**
   * The image, created when it is not there. Runs in the caller's transaction, so a manifest push
   * that creates the image and then fails rolls the image back with everything else (RPS-1350).
   *
   * <p>The insert skips a row that exists instead of failing on the unique index, so two first
   * pushes into one new image never fail on it: the second waits for the first transaction, and
   * uses its image when it commits or creates the image itself when it rolls back.
   *
   * @throws ImageDeletedException if the image that made the insert skip is gone again by the time
   *     it is read (its last manifest was deleted): the caller runs the save again
   */
  @Override
  @Transactional
  public ImageInfo findOrCreateImage(final UUID repoId, final String imageName) {

    final var existing = this.imageRepository.findByRepoIdAndName(repoId, imageName);

    if (existing.isPresent()) {
      return this.imageConverter.toImageInfo(existing.get());
    }

    this.imageRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repoId, imageName, Instant.now());

    final var image =
        this.imageRepository
            .findByRepoIdAndName(repoId, imageName)
            .orElseThrow(() -> new ImageDeletedException(imageName));

    return this.imageConverter.toImageInfo(image);
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

  /**
   * Takes the image row's exclusive lock, held until the caller's transaction ends, and returns the
   * image. Every transaction that deletes tags or manifests of an image takes it first, so that
   * they meet a push (which holds the row's share lock while it writes) in the same order and can
   * never deadlock with it, and so that {@link #deleteImageIfEmpty} counts the manifests of an
   * image no push is in the middle of writing to.
   *
   * @throws ItemNotFoundException {@code imageNotFound} if the image is gone
   */
  @Transactional
  public Image lockImage(final UUID imageId) {

    return this.imageRepository
        .findByIdForUpdate(imageId)
        .orElseThrow(() -> new ItemNotFoundException("imageNotFound"));
  }

  /**
   * Deletes the image when it stores no manifest any more (RPS-1288): an image lives as long as it
   * has a manifest, tagged or not, and goes with the last one. Deleting the last tag never gets
   * here, because the manifest stays. The image row is locked first, so a push that is writing a
   * manifest into it is waited for and its manifest is counted; a push that has looked the image up
   * but not written yet finds it gone and creates it again (see the manifest push handler).
   *
   * @return {@code true} when the image is gone (also when it already was), {@code false} when it
   *     still has manifests
   */
  @Transactional
  public boolean deleteImageIfEmpty(final UUID repoId, final UUID imageId) {

    final var image = this.imageRepository.findByIdForUpdate(imageId).orElse(null);

    if (image == null) {
      return true;
    }

    if (!repoId.equals(image.getRepo().getId())) {
      throw new ItemNotFoundException("imageNotFound");
    }

    // Flushed first: the count must see the manifests this transaction deleted.
    this.imageRepository.flush();

    if (this.manifestRepository.countByImageId(imageId) > 0) {
      return false;
    }

    this.imageRepository.delete(image);
    this.imageRepository.flush();

    return true;
  }

  @Override
  @Transactional
  public void deleteImage(final UUID repoId, final String imageName) {

    final var image = this.findByRepoIdAndName(repoId, imageName);

    // The same lock, and the same order, as every other delete of the image's rows.
    this.imageRepository.findByIdForUpdate(image.getId());

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
        .map(this::toDto);
  }

  /**
   * The image as the list shows it, for the panel's image page.
   *
   * @throws ItemNotFoundException {@code imageNotFound}
   */
  public io.repsy.os.generated.model.ImageListItem findListItemByRepoIdAndName(
      final UUID repoId, final String imageName) {

    return this.imageRepository
        .findListItemByRepoIdAndName(repoId, imageName)
        .map(this::toDto)
        .orElseThrow(() -> new ItemNotFoundException("imageNotFound"));
  }

  /**
   * The list item with what the image stores only for untagged manifests, which the cleanup and
   * this count both take from the whole index graph of the image (RPS-1350).
   */
  private io.repsy.os.generated.model.ImageListItem toDto(
      final io.repsy.os.server.protocols.docker.shared.image.dtos.ImageListItem item) {

    final var dto = this.imageConverter.toDto(item);
    final var untagged = this.imageRepository.findUntaggedStatsByImageId(item.getId());

    return dto.untaggedManifestCount(Math.toIntExact(untagged.getManifestCount()))
        .untaggedSize(untagged.getSize());
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
