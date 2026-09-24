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

import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Deletes a tag from the panel. That removes the tag pointer only: the manifest it pointed at stays
 * stored, untagged and pullable by its digest, and no file goes, so the repo's usage does not
 * change either. What an untagged manifest keeps on disk is released by deleting it explicitly.
 */
@Component
@RequiredArgsConstructor
@NullMarked
public class TagDeletionComponent {

  private final ImageTxService imageService;
  private final ManifestTxService manifestService;
  private final ApplicationEventPublisher eventPublisher;

  public void deleteTag(final RepoInfo repoInfo, final String imageName, final String tagName) {

    final var imageInfo =
        this.imageService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), imageName);

    final var tag =
        this.manifestService.findTag(repoInfo.getStorageKey(), imageInfo.getId(), tagName);

    this.manifestService.deleteTag(tag);

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            imageInfo.getName(),
            tagName));
  }
}
