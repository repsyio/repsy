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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class NuGetPackageServiceImpl implements NuGetPackageService<UUID> {

  private static final String ERR_PACKAGE_NOT_FOUND = "packageNotFound";
  private static final String ERR_VERSION_NOT_FOUND = "versionNotFound";

  private final RepoRepository repoRepository;
  private final NuGetPackageRepository packageRepository;
  private final NuGetPackageVersionRepository packageVersionRepository;

  @Override
  @Transactional
  public void publish(
      final BaseRepoInfo<UUID> repoInfo,
      final String packageId,
      final String version,
      final String nuspecXml)
      throws IOException {
    log.debug("Publishing NuGet package {} version {}", packageId, version);
    // TODO: Parse nuspecXml, create package version, call storage service
  }

  @Override
  public List<String> getVersions(final BaseRepoInfo<UUID> repoInfo, final String packageId) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    return this.packageVersionRepository
        .findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(NuGetPackageVersion::getVersion)
        .toList();
  }

  @Override
  public Page<String> search(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease) {

    final var page = skip / take;
    final var pageable = PageRequest.of(page, take);

    try {
      final var pkg = this.packageRepository.findByRepoIdAndPackageIdIgnoreCase(
          repoInfo.getId(), query.toLowerCase());

      if (pkg.isEmpty()) {
        return new PageImpl<>(List.of(), pageable, 0);
      }

      final var versions = this.packageVersionRepository
          .findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(pkg.get().getId());

      final var searchResults = versions
          .stream()
          .filter(v -> !v.isPrerelease() || prerelease)
          .map(NuGetPackageVersion::getVersion)
          .toList();

      return new PageImpl<>(searchResults, pageable, searchResults.size());
    } catch (final Exception e) {
      log.debug("NuGet search failed for query: {}", query, e);
      return new PageImpl<>(List.of(), pageable, 0);
    }
  }

  @Override
  public List<String> autocomplete(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease) {

    try {
      // Simple autocomplete: find packages matching prefix (case-insensitive)
      return this.packageRepository
          .findAll()
          .stream()
          .filter(p -> p.getRepo().getId().equals(repoInfo.getId()))
          .filter(p -> p.getPackageId().toLowerCase().startsWith(query.toLowerCase()))
          .limit(take)
          .skip(skip)
          .map(NuGetPackage::getPackageId)
          .distinct()
          .toList();
    } catch (final Exception e) {
      log.debug("NuGet autocomplete failed for query: {}", query, e);
      return List.of();
    }
  }

  @Override
  public void incrementDownloadCount(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersion(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    pkgVersion.setDownloadCount(pkgVersion.getDownloadCount() + 1);
    this.packageVersionRepository.save(pkgVersion);
  }

  private NuGetPackage findPackage(final UUID repoId, final String packageId) {
    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoId, packageId.toLowerCase())
        .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));
  }
}
