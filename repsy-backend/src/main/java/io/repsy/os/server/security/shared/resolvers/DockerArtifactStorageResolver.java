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
package io.repsy.os.server.security.shared.resolvers;

import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Docker images are scanned by the scanner pulling them from the registry, so there is no storage
 * path to hand over: an existing image reference resolves to an empty path.
 */
@Component
@NullMarked
@RequiredArgsConstructor
public class DockerArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("DOCKER");
  private static final String NO_STORAGE_PATH = "";

  private final @NonNull ImageRepository imageRepository;
  private final @NonNull TagRepository tagRepository;
  private final @NonNull ManifestRepository manifestRepository;

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    final var exists =
        artifactVersion.startsWith(DockerConstants.SHA256_PREFIX)
            ? this.digestExists(repoId, artifactName, artifactVersion)
            : this.tagRepository
                .findByImageRepoIdAndImageNameAndName(repoId, artifactName, artifactVersion)
                .isPresent();

    return exists ? Optional.of(NO_STORAGE_PATH) : Optional.empty();
  }

  private boolean digestExists(
      final @NonNull UUID repoId, final @NonNull String imageName, final @NonNull String digest) {

    return this.imageRepository
        .findByRepoIdAndName(repoId, imageName)
        .map(image -> this.imageHasDigest(repoId, image, digest))
        .orElse(false);
  }

  private boolean imageHasDigest(
      final @NonNull UUID repoId, final @NonNull Image image, final @NonNull String digest) {

    return this.tagRepository
            .findDistinctFirstByImageRepoIdAndImageNameAndDigestOrderByCreatedAtDesc(
                repoId, image.getName(), digest)
            .isPresent()
        || !this.manifestRepository
            .findByRepoIdAndImageIdAndDigestList(repoId, image.getId(), digest)
            .isEmpty();
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
