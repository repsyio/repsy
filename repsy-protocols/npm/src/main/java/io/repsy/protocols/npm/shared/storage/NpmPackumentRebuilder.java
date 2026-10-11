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
package io.repsy.protocols.npm.shared.storage;

import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.utils.NpmPackageUtils;
import io.repsy.protocols.npm.shared.utils.NpmPackumentBuilder;
import io.repsy.protocols.npm.shared.utils.NpmTarballFacts;
import io.repsy.protocols.npm.shared.utils.NpmTarballInspector;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Builds the packument of a package from its rows, reading the tarball of each version for what the
 * rows do not keep (see {@link NpmPackumentBuilder}). Writes nothing.
 */
public final class NpmPackumentRebuilder {

  private final NpmTarballStore tarballStore;

  public NpmPackumentRebuilder(final NpmTarballStore tarballStore) {
    this.tarballStore = tarballStore;
  }

  /**
   * @param registryBaseUrl The address clients reach the registry at (scheme, host and port,
   *     without the repo), or {@code null} when it is not known: then {@code dist.tarball} is left
   *     out of a rebuilt version.
   */
  public Map<String, Object> rebuild(
      final RepoRef repo,
      final Path packageBasePath,
      final NpmPackageSnapshot snapshot,
      final @Nullable String registryBaseUrl)
      throws IOException {

    final var tarballs = new HashMap<String, NpmTarballFacts>();

    for (final var version : snapshot.versions()) {
      this.inspectTarball(repo, packageBasePath, snapshot.name(), version.version())
          .ifPresent(facts -> tarballs.put(version.version(), facts));
    }

    return NpmPackumentBuilder.build(
        snapshot, tarballs, packageUrl(registryBaseUrl, repo.name(), snapshot), Instant.now());
  }

  private Optional<NpmTarballFacts> inspectTarball(
      final RepoRef repo,
      final Path packageBasePath,
      final String packageName,
      final String versionName)
      throws IOException {

    final var resource = this.tarballStore.find(repo, packageBasePath, packageName, versionName);

    if (resource.isEmpty()) {
      return Optional.empty();
    }

    try (final var inputStream = resource.get().getInputStream()) {
      return Optional.of(NpmTarballInspector.inspect(inputStream));
    }
  }

  private static @Nullable String packageUrl(
      final @Nullable String base, final String repoName, final NpmPackageSnapshot snapshot) {

    if (base == null || base.isBlank()) {
      return null;
    }

    return base.replaceAll("/+$", "")
        + "/"
        + repoName
        + "/"
        + NpmPackageUtils.buildFullName(snapshot.scope(), snapshot.name());
  }
}
