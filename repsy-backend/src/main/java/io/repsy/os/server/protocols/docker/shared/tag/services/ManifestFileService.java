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

import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides which manifest files may go when manifest rows are deleted. A manifest file is stored
 * once per repo and digest ({@code manifests/<digest>}) and shared by every image that has the
 * manifest, so a file goes only when no manifest row of the repo carries its digest any more; a
 * file an earlier version named after a tag goes when no other row of the image still uses that
 * name. Ask after the rows are deleted (and flushed), with the references taken before.
 */
@Component
@RequiredArgsConstructor
@NullMarked
public class ManifestFileService {

  private final ManifestRepository manifestRepository;

  /** What identifies the file of a manifest row: its digest and, for a legacy file, its name. */
  public record ManifestFileRef(String digest, @Nullable String storageName) {}

  @Transactional(readOnly = true)
  public List<ManifestFileRef> findRefsOfImage(final UUID imageId) {

    return this.manifestRepository.findFileViewsByImageId(imageId).stream()
        .map(view -> new ManifestFileRef(view.getDigest(), view.getStorageName()))
        .toList();
  }

  /**
   * The names, inside the repo's {@code manifests} directory, of the files that no manifest row
   * needs any more.
   *
   * @param repoId The repo the files belong to
   * @param imageId The image the rows were deleted from
   * @param imageName The name of that image, which the legacy file names contain
   * @param refs The references of the rows that were deleted
   */
  @Transactional(readOnly = true)
  public Set<String> findUnreferencedFileNames(
      final UUID repoId,
      final UUID imageId,
      final String imageName,
      final Collection<ManifestFileRef> refs) {

    final var names = new LinkedHashSet<String>();

    for (final var ref : refs) {
      if (this.manifestRepository.countByImageRepoIdAndDigest(repoId, ref.digest()) == 0) {
        names.add(ref.digest());
      }

      if (ref.storageName() != null
          && this.manifestRepository.countByImageIdAndStorageName(imageId, ref.storageName())
              == 0) {
        names.add(ManifestNameGenerator.generate(repoId, imageName, ref.storageName()));
      }
    }

    return names;
  }
}
