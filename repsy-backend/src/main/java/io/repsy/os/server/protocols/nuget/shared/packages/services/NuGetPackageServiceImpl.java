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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.nuget.shared.packages.dtos.NuGetDeletedItem;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.mappers.NuGetPackageConverter;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
  private final NuGetPackageConverter converter;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public void publish(
      final BaseRepoInfo<UUID> repoInfo,
      final String packageId,
      final String version,
      final String nuspecXml) {

    final var repo =
        this.repoRepository
            .findById(repoInfo.getId())
            .orElseThrow(() -> new IllegalArgumentException("Repository not found"));

    final var pkg =
        this.packageRepository.findByRepoIdAndPackageIdIgnoreCase(
            repoInfo.getId(), packageId.toLowerCase(Locale.ROOT));

    final var nugetPackage = pkg.orElseGet(() -> this.crateNuGetPackage(repo, packageId));

    final boolean versionExists =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersion(nugetPackage.getId(), version)
            .isPresent();

    if (versionExists) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Version " + version + " of package " + packageId + " already exists.");
    }

    final var pkgVersion = this.createNuGetPackageVersion(nugetPackage, nuspecXml, version);

    this.packageVersionRepository.save(pkgVersion);
  }

  @Override
  public List<String> getVersions(final BaseRepoInfo<UUID> repoInfo, final String packageId) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    return this.packageVersionRepository
        .findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(v -> v.getVersion().toLowerCase(Locale.ROOT))
        .toList();
  }

  @Override
  public List<NuGetVersionInfo> getVersionInfos(
      final BaseRepoInfo<UUID> repoInfo, final String packageId) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    return this.packageVersionRepository
        .findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(v -> this.converter.toVersionInfo(v, packageId))
        .toList();
  }

  @Override
  public List<NuGetVersionInfo> getAllVersionInfos(
      final BaseRepoInfo<UUID> repoInfo, final String packageId) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    return this.packageVersionRepository
        .findByNugetPackageIdOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(v -> this.converter.toVersionInfo(v, packageId))
        .toList();
  }

  @Override
  public Page<NuGetVersionInfo> getVersionInfosPage(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final Pageable pageable) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    return this.packageVersionRepository
        .findByNugetPackageIdOrderByPublishedAtDesc(pkg.getId(), pageable)
        .map(v -> this.converter.toVersionInfo(v, packageId));
  }

  @Override
  public Optional<NuGetVersionInfo> findVersionInfo(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoInfo.getId(), packageId.toLowerCase(Locale.ROOT))
        .flatMap(
            pkg ->
                this.packageVersionRepository.findByNugetPackageIdAndVersionIgnoreCase(
                    pkg.getId(), version))
        .map(
            v -> {
              final var base = this.converter.toVersionInfo(v, packageId);
              final var deps = NuGetPackageUtils.parseDependenciesJson(v.getDependencies());
              return new NuGetVersionInfo(
                  base.packageId(),
                  base.version(),
                  base.title(),
                  base.description(),
                  base.authors(),
                  base.tags(),
                  base.iconUrl(),
                  base.licenseUrl(),
                  base.projectUrl(),
                  base.listed(),
                  base.downloadCount(),
                  base.publishedAt(),
                  deps.isEmpty() ? null : deps);
            });
  }

  @Override
  public Page<NuGetPackageSearchResult> search(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease) {

    if (take <= 0) {
      return new org.springframework.data.domain.PageImpl<>(List.of());
    }

    final var page = skip / take;
    final var pageable = PageRequest.of(page, take);

    final var pkgPage =
        this.packageRepository.findByRepoIdAndPackageIdContainingIgnoreCase(
            repoInfo.getId(), query, pageable);

    return pkgPage.map(pkg -> this.toSearchResult(pkg, prerelease));
  }

  @Override
  public List<String> autocomplete(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease) {

    if (take <= 0) {
      return List.of();
    }

    final var pageable = Pageable.ofSize(Math.max(skip + take, 1));
    return this.packageRepository
        .findByRepoIdAndPackageIdStartingWithIgnoreCase(repoInfo.getId(), query, pageable)
        .stream()
        .skip(skip)
        .limit(take)
        .map(NuGetPackage::getPackageId)
        .toList();
  }

  @Override
  @Transactional
  public void incrementDownloadCount(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersionIgnoreCase(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    this.packageVersionRepository.incrementDownloadCount(pkgVersion.getId());
  }

  @Override
  @Transactional
  public void unlistVersion(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersionIgnoreCase(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));
    pkgVersion.setListed(false);
    this.packageVersionRepository.save(pkgVersion);
  }

  @Override
  @Transactional
  public void relistVersion(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersionIgnoreCase(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));
    pkgVersion.setListed(true);
    this.packageVersionRepository.save(pkgVersion);
  }

  @Override
  @Transactional
  public void deletePackage(final BaseRepoInfo<UUID> repoInfo, final String packageId) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);
    this.packageRepository.delete(pkg);
  }

  @Override
  @Transactional
  public boolean deleteVersion(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersionIgnoreCase(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    this.packageVersionRepository.delete(pkgVersion);

    final boolean packageHasVersions =
        this.packageVersionRepository.existsByNugetPackageId(pkg.getId());

    if (!packageHasVersions) {
      this.packageRepository.delete(pkg);
      return true;
    }

    return false;
  }

  @Transactional
  public NuGetDeletedItem deleteVersionAndGetDeletedItem(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {
    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    final var pkgVersion =
        this.packageVersionRepository
            .findByNugetPackageIdAndVersionIgnoreCase(pkg.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    this.packageVersionRepository.delete(pkgVersion);

    final boolean packageHasVersions =
        this.packageVersionRepository.existsByNugetPackageId(pkg.getId());

    if (!packageHasVersions) {
      this.packageRepository.delete(pkg);
      return NuGetDeletedItem.PACKAGE;
    }

    return NuGetDeletedItem.VERSION;
  }

  private NuGetPackage findPackage(final UUID repoId, final String packageId) {
    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoId, packageId.toLowerCase(Locale.ROOT))
        .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));
  }

  private NuGetPackage crateNuGetPackage(final Repo repo, final String packageId) {

    final var newPkg = new NuGetPackage();
    newPkg.setRepo(repo);
    newPkg.setPackageId(packageId.toLowerCase(Locale.ROOT));

    return this.packageRepository.save(newPkg);
  }

  private NuGetPackageVersion createNuGetPackageVersion(
      final NuGetPackage nugetPackage, final String nuspecXml, final String version) {

    final var pkgVersion = new NuGetPackageVersion();
    pkgVersion.setNugetPackage(nugetPackage);
    pkgVersion.setVersion(version);
    pkgVersion.setPrerelease(version.contains("-"));
    pkgVersion.setListed(true);
    pkgVersion.setPublishedAt(Instant.now());
    pkgVersion.setDownloadCount(0);
    pkgVersion.setCreatedAt(Instant.now());
    pkgVersion.setTitle(NuGetPackageUtils.extractXmlTag(nuspecXml, "title"));
    pkgVersion.setDescription(NuGetPackageUtils.extractXmlTag(nuspecXml, "description"));
    pkgVersion.setAuthors(NuGetPackageUtils.extractXmlTag(nuspecXml, "authors"));
    pkgVersion.setTags(NuGetPackageUtils.extractXmlTag(nuspecXml, "tags"));
    pkgVersion.setIconUrl(NuGetPackageUtils.extractXmlTag(nuspecXml, "iconUrl"));
    pkgVersion.setLicenseUrl(NuGetPackageUtils.extractXmlTag(nuspecXml, "licenseUrl"));
    pkgVersion.setProjectUrl(NuGetPackageUtils.extractXmlTag(nuspecXml, "projectUrl"));
    pkgVersion.setRepositoryUrl(NuGetPackageUtils.extractXmlTag(nuspecXml, "repository"));

    final var deps = NuGetPackageUtils.extractDependenciesFromNuspec(nuspecXml);
    if (!deps.isEmpty()) {
      try {
        pkgVersion.setDependencies(this.objectMapper.writeValueAsString(deps));
      } catch (final Exception e) {
        log.warn("Failed to serialize NuGet dependencies for version {}", version, e);
      }
    }

    return pkgVersion;
  }

  private NuGetPackageSearchResult toSearchResult(
      final NuGetPackage pkg, final boolean prerelease) {

    final var allVersions =
        this.packageVersionRepository.findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(
            pkg.getId());

    return this.converter.toSearchResult(pkg, prerelease, allVersions);
  }
}
