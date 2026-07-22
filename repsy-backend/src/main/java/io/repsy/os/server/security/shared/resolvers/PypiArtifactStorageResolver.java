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

import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.pypi.shared.utils.PackageStorageUtils;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Unlike every other protocol, a single PyPI release can have several files (an sdist and one or
 * more platform/interpreter-specific wheels), and the real filenames carry build tags
 * (`-py3-none-any`, `-cp39-cp39-manylinux...`) that cannot be derived from name+version alone —
 * there is also no metadata table recording the exact filename (see {@code Release} entity). So
 * this resolver lists the package's directory and filters by version with the same {@link
 * PackageStorageUtils#isFileBelongsRelease} used elsewhere in this protocol, rather than computing
 * a single path. When more than one file matches, the sdist ({@code .tar.gz}) is preferred — Trivy
 * analyzes source distributions more reliably than wheels — falling back to whichever match was
 * listed first. This is a reasonable default, not a guarantee of scanning the "right" file.
 */
@Component
@NullMarked
public class PypiArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("PYPI");
  private static final String SDIST_SUFFIX = ".tar.gz";
  private static final String DIGEST_SUFFIX = PackageStorageUtils.HASH_ALGORITHM;

  private final @NonNull StorageStrategy pypiStorageStrategy;

  public PypiArtifactStorageResolver(
      final @Qualifier("osStorageStrategyPypi") @NonNull StorageStrategy pypiStorageStrategy) {
    this.pypiStorageStrategy = pypiStorageStrategy;
  }

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    final var packagePath = artifactName.endsWith("/") ? artifactName : artifactName + "/";
    final var storagePath = StoragePath.of(repoId, packagePath);
    final var items = this.pypiStorageStrategy.listDirectoryContents(storagePath);

    final var candidates = filterMatchingFilenames(items, artifactVersion);
    final var chosen = pickPreferredFilename(candidates);

    return chosen.map(filename -> Paths.get(artifactName, filename).toString());
  }

  private static @NonNull List<@NonNull String> filterMatchingFilenames(
      final @NonNull List<@NonNull StorageItemInfo> items, final @NonNull String artifactVersion) {

    return items.stream()
        .filter(item -> !item.isDirectory())
        .map(StorageItemInfo::getName)
        .filter(filename -> !filename.endsWith(DIGEST_SUFFIX))
        .filter(filename -> PackageStorageUtils.isFileBelongsRelease(filename, artifactVersion))
        .toList();
  }

  private static @NonNull Optional<String> pickPreferredFilename(
      final @NonNull List<@NonNull String> candidates) {

    return candidates.stream()
        .filter(filename -> filename.endsWith(SDIST_SUFFIX))
        .findFirst()
        .or(() -> candidates.stream().findFirst());
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
