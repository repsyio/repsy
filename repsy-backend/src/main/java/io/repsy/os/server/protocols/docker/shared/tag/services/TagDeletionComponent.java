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

import static io.repsy.protocols.docker.shared.utils.ManifestNameGenerator.generate;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.manifest.ManifestDetail;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class TagDeletionComponent {

  private final ImageTxService imageService;
  private final ManifestTxService manifestService;
  private final DockerStorageService dockerStorageService;

  public BaseUsages deleteTag(
      final RepoInfo repoInfo, final String imageName, final String tagName) {

    final var imageInfo =
        this.imageService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    final var tag =
        this.manifestService.findTag(repoInfo.getStorageKey(), imageInfo.getId(), tagName);

    this.manifestService.deleteTag(tag);

    final var manifestsToDeleteFileNames =
        this.findManifestsToDeleteFileNames(repoInfo, imageInfo.getId(), imageName, List.of(tag));

    final var usage =
        this.dockerStorageService.deleteManifests(repoInfo, manifestsToDeleteFileNames);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  private Set<String> findManifestsToDeleteFileNames(
      final RepoInfo repoInfo, final UUID imageId, final String imageName, final List<Tag> tags) {

    final var manifestsToDeleteFileNames = new HashSet<String>();

    for (final Tag tag : tags) {
      final var manifestsToDelete =
          this.manifestService.findManifests(repoInfo.getStorageKey(), imageId, tag.getId());

      this.deleteManifests(repoInfo, imageName, manifestsToDelete, manifestsToDeleteFileNames);
    }

    return manifestsToDeleteFileNames;
  }

  private void deleteManifests(
      final RepoInfo repoInfo,
      final String imageName,
      final List<ManifestDetail> manifestsToDelete,
      final HashSet<String> manifestsToDeleteFileNames) {

    for (final var manifest : manifestsToDelete) {
      final var manifestName = generate(repoInfo.getStorageKey(), imageName, manifest.getName());

      manifestsToDeleteFileNames.add(manifestName);
    }
  }
}
