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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService.ManifestFileRef;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code DELETE /v2/<name>/manifests/<reference>} does (RPS-1216). A tag reference removes the
 * tag, exactly as the panel does ({@link TagDeletionComponent}). A digest reference, of either
 * algorithm, removes the manifest itself: the tags that point at it go with it, so neither the
 * digest nor those tags can be pulled afterwards, as the distribution specification requires. The
 * manifests an index lists are not deleted with it: another index may still list them, so they stay
 * as untagged manifests until they are deleted by their own digest or by the repo's cleanup.
 */
@Component
@RequiredArgsConstructor
@NullMarked
public class ManifestDeletionComponent {

  private final ImageTxService imageService;
  private final ManifestRepository manifestRepository;
  private final TagRepository tagRepository;
  private final ManifestFileService manifestFileService;
  private final DockerStorageService dockerStorageService;
  private final TagDeletionComponent tagDeletionComponent;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Deletes the tag or the manifest the reference names.
   *
   * @param repoInfo The repo the request is for
   * @param imageName The image of the request
   * @param reference A tag name, or a digest of a supported algorithm
   * @return The bytes of manifest file that were freed, to be refunded to the repo's usage
   * @throws ItemNotFoundException {@code imageNotFound}, {@code manifestNotFound} or {@code
   *     tagNotFound}
   */
  @Transactional
  public long delete(final RepoInfo repoInfo, final String imageName, final String reference) {

    if (!BlobDigests.startsWithDigestPrefix(reference)) {
      this.tagDeletionComponent.deleteTag(repoInfo, imageName, reference);

      return 0L;
    }

    return this.deleteByDigest(repoInfo, imageName, DockerDigestCalculator.normalize(reference));
  }

  private long deleteByDigest(
      final RepoInfo repoInfo, final String imageName, final String digest) {

    final var imageInfo =
        this.imageService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    final var manifest =
        this.manifestRepository
            .findByImageIdAndAnyDigest(imageInfo.getId(), digest)
            .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));

    final var fileRef = new ManifestFileRef(manifest.getDigest(), manifest.getStorageName());
    final var tags = this.tagRepository.findAllByManifestId(manifest.getId());
    final var tagNames = tags.stream().map(Tag::getName).toList();

    // The tags go with their manifest; its layer links and its index edges (as parent and as
    // child) go through the database's ON DELETE CASCADE. Flushed, so the file check below counts
    // the rows the repo still has.
    this.tagRepository.deleteAll(tags);
    this.manifestRepository.delete(manifest);
    this.manifestRepository.flush();

    // In this transaction: the image is listed with the size and the digest of what its tags reach.
    this.imageService.refreshImageSize(repoInfo.getStorageKey(), imageInfo.getId());

    this.publishVersionsDeleted(repoInfo, imageInfo.getName(), tagNames);

    // Another image of the repo may have the same manifest, and its file is shared: the file goes
    // only with the last row that has the digest.
    final var fileNames =
        this.manifestFileService.findUnreferencedFileNames(
            repoInfo.getStorageKey(), imageInfo.getId(), imageInfo.getName(), List.of(fileRef));

    return this.dockerStorageService.deleteManifests(repoInfo, fileNames);
  }

  private void publishVersionsDeleted(
      final RepoInfo repoInfo, final String imageName, final List<String> tagNames) {

    for (final var tagName : tagNames) {
      this.eventPublisher.publishEvent(
          new ArtifactVersionDeletedEvent(
              repoInfo.getStorageKey(),
              repoInfo.getType().name(),
              repoInfo.getName(),
              imageName,
              tagName));
    }
  }
}
