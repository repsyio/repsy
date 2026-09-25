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
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService.ManifestFileRef;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the manifests no tag reaches (see {@link UntaggedManifestFinder}) and the files only they
 * needed. Manifest files are kilobytes, so rows and files go in the one transaction and the freed
 * bytes are exact, unlike the layer blobs the orphan-layer sweep deletes in the background.
 *
 * <p>Not safe against a push in flight: a manifest pushed by digest whose tag or index has not
 * arrived yet counts as untagged and goes; the client's next push of that index fails with
 * "manifest not found" and pushes the manifest again. An image whose manifests are all gone is
 * deleted with them, as when the last manifest is deleted by its digest.
 */
@Service
@RequiredArgsConstructor
@NullMarked
public class UntaggedManifestCleanupService {

  /** What a cleanup removed. */
  public record Result(int deletedManifests, long freedBytes) {}

  private final ImageRepository imageRepository;
  private final ImageTxService imageService;
  private final ManifestRepository manifestRepository;
  private final ManifestFileService manifestFileService;
  private final UntaggedManifestFinder untaggedManifestFinder;
  private final DockerStorageService dockerStorageService;

  /**
   * Deletes the untagged manifests of the repo, or of one of its images.
   *
   * @param repoInfo The repo to clean
   * @param imageName The image to clean, or {@code null} for every image of the repo
   * @throws ItemNotFoundException If {@code imageName} names no image of the repo
   */
  @Transactional
  public Result deleteUntagged(final RepoInfo repoInfo, final @Nullable String imageName) {

    var deleted = 0;
    var freedBytes = 0L;

    for (final var image : this.findImages(repoInfo, imageName)) {
      final var result = this.deleteUntaggedOfImage(repoInfo, image);

      deleted += result.deletedManifests();
      freedBytes += result.freedBytes();
    }

    return new Result(deleted, freedBytes);
  }

  private List<Image> findImages(final RepoInfo repoInfo, final @Nullable String imageName) {

    if (imageName == null) {
      // In one order, so two repo-wide cleanups lock the images in the same sequence.
      return this.imageRepository.findAllByRepoId(repoInfo.getStorageKey()).stream()
          .sorted(Comparator.comparing(Image::getId))
          .toList();
    }

    return List.of(
        this.imageRepository
            .findByRepoIdAndName(repoInfo.getStorageKey(), imageName)
            .orElseThrow(() -> new ItemNotFoundException("imageNotFound")));
  }

  private Result deleteUntaggedOfImage(final RepoInfo repoInfo, final Image image) {

    // Locked before anything is read, so a push that is writing to the image is waited for.
    this.imageService.lockImage(image.getId());

    final var untagged = this.untaggedManifestFinder.findUntagged(image.getId());

    if (untagged.isEmpty()) {
      // An image with no manifest at all goes too: only an image an earlier version left behind (a
      // push that failed after it created the image) can be one, since RPS-1350.
      this.imageService.deleteImageIfEmpty(repoInfo.getStorageKey(), image.getId());

      return new Result(0, 0L);
    }

    // Taken before the rows go: the files a manifest keeps are found through its row.
    final var refs =
        untagged.stream()
            .map(manifest -> new ManifestFileRef(manifest.getDigest(), manifest.getStorageName()))
            .toList();

    // Deleted one by one, as LayerTxService does: the join rows to the layers go with the manifest
    // and the index edges through the database's ON DELETE CASCADE.
    for (final var manifest : untagged) {
      this.manifestRepository.delete(manifest);
    }
    this.manifestRepository.flush();

    // The image goes with its last manifest (RPS-1288), which this may have been.
    this.imageService.deleteImageIfEmpty(repoInfo.getStorageKey(), image.getId());

    // A file is shared by every image of the repo that has the manifest: only the ones no
    // remaining row needs are deleted.
    final var fileNames =
        this.manifestFileService.findUnreferencedFileNames(
            repoInfo.getStorageKey(), image.getId(), image.getName(), refs);

    return new Result(
        untagged.size(), this.dockerStorageService.deleteManifests(repoInfo, fileNames));
  }
}
