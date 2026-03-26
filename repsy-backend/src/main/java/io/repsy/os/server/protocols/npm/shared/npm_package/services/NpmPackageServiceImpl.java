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
package io.repsy.os.server.protocols.npm.shared.npm_package.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.npm.shared.constants.NpmConstants;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageDistributionTagListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageInfo;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionInfo;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageDistTag;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageKeyword;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageMaintainer;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.mappers.NpmPackageConverter;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageDistTagRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageVersionInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
@NullMarked
public class NpmPackageServiceImpl implements NpmPackageService<UUID> {

  private final RepoRepository repoRepository;
  private final NpmPackageRepository npmPackageRepository;
  private final PackageVersionRepository packageVersionRepository;
  private final PackageDistTagRepository packageDistTagRepository;
  private final PackageMaintainerRepository packageMaintainerRepository;
  private final PackageKeywordRepository packageKeywordRepository;
  private final NpmPackageConverter npmPackageConverter;

  @Transactional
  @Override
  public void addPackage(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scope,
      final String name,
      final Map<String, Object> payload)
      throws ClassCastException {

    final var repo =
        this.repoRepository
            .findById(repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var distTag = PackageUtils.extractFirstDistTagFromPayload(payload);

    final var versionPair = PackageUtils.extractVersionFromPayload(payload);

    final var npmPackage = this.addPackage(scope, name, distTag.getValue(), repo);

    final var packageVersion =
        this.addVersion(versionPair.getSecond(), versionPair.getFirst(), npmPackage);

    this.addMaintainers(versionPair.getSecond(), packageVersion);
    this.addKeywords(versionPair.getSecond(), packageVersion);

    if (!distTag.getKey().equals(NpmConstants.LATEST)) {
      this.addDistTag(npmPackage.getId(), packageVersion, NpmConstants.LATEST);
    }

    this.addDistTag(npmPackage.getId(), packageVersion, distTag.getKey());
  }

  @Override
  public PackageInfo getPackage(
      final UUID repoId, final @Nullable String scopeName, final String packageName) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    return this.npmPackageConverter.toPackageInfo(npmPackage, npmPackage.getRepo());
  }

  @Override
  public Optional<BasePackageInfo<UUID>> getPackageInfoByRepoIdAndScopeAndName(
      final UUID repoId, final @Nullable String scopeName, final String packageName) {

    final var repo =
        this.repoRepository
            .findById(repoId)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.REPO_NOT_FOUND));

    return this.npmPackageRepository
        .findByRepoIdAndScopeAndName(repoId, scopeName, packageName)
        .map(pkg -> this.npmPackageConverter.toPackageInfo(pkg, repo));
  }

  @Transactional
  @Override
  public void deletePackage(final UUID packageId) {

    final var npmPackage =
        this.npmPackageRepository
            .findById(packageId)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));

    this.npmPackageRepository.delete(npmPackage);
  }

  @Transactional
  @Override
  public void addVersionToPackage(final UUID packageId, final Map<String, Object> payload) {

    final var npmPackage =
        this.npmPackageRepository
            .findById(packageId)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));

    final var distTag = PackageUtils.extractFirstDistTagFromPayload(payload);

    final var versionPair = PackageUtils.extractVersionFromPayload(payload);

    final var packageVersion =
        this.addVersion(versionPair.getSecond(), distTag.getValue(), npmPackage);

    this.addKeywords(versionPair.getSecond(), packageVersion);
    this.addMaintainers(versionPair.getSecond(), packageVersion);
    this.addDistTag(npmPackage.getId(), packageVersion, distTag.getKey());

    if (distTag.getKey().equals(NpmConstants.LATEST)) {
      npmPackage.setLatest(packageVersion.getVersion());
      this.npmPackageRepository.save(npmPackage);
    }
  }

  @Override
  public Optional<BasePackageVersionInfo<UUID>> findOptPackageVersionByPackageIdAndVersion(
      final UUID packageId, final String versionName) {

    final var pkg =
        this.npmPackageRepository
            .findById(packageId)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));

    return this.packageVersionRepository
        .findByNpmPackageIdAndVersion(packageId, versionName)
        .map(pkv -> this.npmPackageConverter.toPackageVersionInfo(pkv, pkg));
  }

  @Transactional
  @Override
  public void updateVersionFromMetadata(
      final UUID repoId,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName,
      final Map<String, Object> metadata) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    final var version =
        this.findPackageVersionByPackageIdAndVersion(npmPackage.getId(), versionName);

    this.populatePackageVersionFromMetadata(version, metadata);
    this.packageVersionRepository.save(version);

    this.packageMaintainerRepository.deleteAllMaintainersOfVersion(version.getId());
    this.packageKeywordRepository.deleteAllKeywordsOfVersion(version.getId());

    this.addMaintainers(metadata, version);
    this.addKeywords(metadata, version);
  }

  @Transactional
  @Override
  public void handleDeprecations(
      final UUID repoId,
      final @Nullable String scopeName,
      final String packageName,
      final List<Pair<String, String>> deprecatedVersions) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    for (final var pair : deprecatedVersions) {
      if (pair.getSecond().isEmpty()) {
        this.unDeprecatePackage(npmPackage.getId(), pair.getFirst()); // un-deprecate
      } else {
        this.deprecatePackage(npmPackage.getId(), pair.getFirst(), pair.getSecond()); // deprecate
      }
    }
  }

  @Override
  public boolean isLastVersion(
      final UUID repoId, final @Nullable String scope, final String packageName) {

    final var versionCount =
        this.packageVersionRepository.countByNpmPackageRepoIdAndNpmPackageScopeAndNpmPackageName(
            repoId, scope, packageName);

    return versionCount == 1;
  }

  @Transactional
  @Override
  public void deletePackageVersion(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName,
      final String nextLatestVersion) {

    final var npmPackage =
        this.npmPackageRepository
            .findByRepoIdAndScopeAndName(repoInfo.getId(), scopeName, packageName)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));

    final var packageVersion =
        this.packageVersionRepository
            .findByNpmPackageIdAndVersion(npmPackage.getId(), versionName)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));

    if (npmPackage.getLatest().equals(versionName)) {
      final var latestPackageVersion =
          this.packageVersionRepository
              .findByNpmPackageIdAndVersion(npmPackage.getId(), nextLatestVersion)
              .orElseThrow(
                  () -> new ItemNotFoundException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));

      final var distTag = new PackageDistTag();
      distTag.setPackageVersion(latestPackageVersion);
      distTag.setTagName(NpmConstants.LATEST);
      distTag.setPackageVersion(latestPackageVersion);
      this.packageDistTagRepository.save(distTag);

      npmPackage.setLatest(latestPackageVersion.getVersion());
      this.npmPackageRepository.save(npmPackage);
    }

    this.packageVersionRepository.delete(packageVersion);
  }

  public List<PackageDistributionTagMapListItem> getDistributionTags(
      final UUID repoId, final @Nullable String scope, final String packageName) {

    return this.packageDistTagRepository.findAllByRepoIdAndScopeAndPackageName(
        repoId, scope, packageName);
  }

  @Transactional
  @Override
  public void addDistributionTag(
      final UUID repoId,
      final @Nullable String scopeName,
      final String packageName,
      final String tagName,
      final String versionName) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    final var packageVersion =
        this.findPackageVersionByPackageIdAndVersion(npmPackage.getId(), versionName);

    final var distTagOptional =
        this.packageDistTagRepository.findByPackageVersionNpmPackageIdAndTagName(
            npmPackage.getId(), tagName);

    if (distTagOptional.isPresent()) {
      final var distTag = distTagOptional.get();

      distTag.setPackageVersion(packageVersion);

      this.packageDistTagRepository.save(distTag);

      if (tagName.equals(NpmConstants.LATEST)) {
        npmPackage.setLatest(packageVersion.getVersion());

        this.npmPackageRepository.save(npmPackage);
      }

      return;
    }

    final var distTag = new PackageDistTag();

    distTag.setTagName(tagName);
    distTag.setPackageVersion(packageVersion);
    distTag.setCreatedAt(Instant.now());

    this.packageDistTagRepository.save(distTag);
  }

  @Transactional
  @Override
  public void removeDistributionTag(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String tagName) {

    final var npmPackage =
        this.findPackageByRepoIdAndScopeAndName(repoInfo.getId(), scopeName, packageName);

    final var distTagOptional =
        this.packageDistTagRepository.findByPackageVersionNpmPackageIdAndTagName(
            npmPackage.getId(), tagName);

    distTagOptional.ifPresent(this.packageDistTagRepository::delete);
  }

  public Page<PackageListItem> getPackagesContainsScope(
      final UUID repoId, final @Nullable String scope, final Pageable pageable) {

    return this.npmPackageRepository.findAllByRepoIdAndLatestVersionContainsScope(
        repoId, scope, pageable);
  }

  public Page<PackageListItem> getPackagesContainsName(
      final UUID repoId, final String name, final Pageable pageable) {

    return this.npmPackageRepository.findAllByRepoIdAndLatestVersionContainsName(
        repoId, name, pageable);
  }

  public Page<PackageListItem> getPackagesByScopeContainsName(
      final UUID repoId, final @Nullable String scope, final String name, final Pageable pageable) {

    if (scope == null) {
      return this.npmPackageRepository.findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName(
          repoId, name, pageable);
    }

    return this.npmPackageRepository.findAllByRepoIdAndLatestVersionAndScopeContainsName(
        repoId, scope, name, pageable);
  }

  public PackageVersionInfo getPackageVersion(final UUID packageId, final String versionName) {

    final var packageVersion = this.findPackageVersionByPackageIdAndVersion(packageId, versionName);

    return this.npmPackageConverter.toPackageVersionInfo(
        packageVersion, packageVersion.getNpmPackage());
  }

  public List<PackageKeywordListItem> getKeywords(final UUID versionId) {

    return this.packageKeywordRepository.findAllByPackageVersionId(versionId);
  }

  public List<PackageMaintainerListItem> getMaintainers(final UUID versionId) {

    return this.packageMaintainerRepository.findAllByPackageVersionId(versionId);
  }

  public List<PackageDistributionTagListItem> getDistributionTagsOfVersion(final UUID versionId) {

    return this.packageDistTagRepository.findAllByPackageVersionId(versionId);
  }

  public Page<PackageVersionListItem> getVersionsContainsVersion(
      final UUID repoId,
      final @Nullable String scopeName,
      final String packageName,
      final String version,
      final Pageable pageable) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    return this.packageVersionRepository.findAllByNpmPackageIdContainsVersion(
        npmPackage.getId(), version, pageable);
  }

  private void deprecatePackage(
      final UUID packageId, final String versionName, final String message) {

    final var version = this.findPackageVersionByPackageIdAndVersion(packageId, versionName);

    version.setDeprecated(true);
    version.setDeprecationMessage(message);

    this.packageVersionRepository.save(version);
  }

  private void unDeprecatePackage(final UUID packageId, final String versionName) {

    final var version = this.findPackageVersionByPackageIdAndVersion(packageId, versionName);

    version.setDeprecated(false);
    version.setDeprecationMessage(null);

    this.packageVersionRepository.save(version);
  }

  @Override
  public List<PackageDistributionTagMapListItem> getDistributionTags(final UUID packageId) {

    return this.packageDistTagRepository.findAllTagsOfPackage(packageId);
  }

  private NpmPackage findPackageByRepoIdAndScopeAndName(
      final UUID repoId, final @Nullable String scopeName, final String packageName) {

    return this.npmPackageRepository
        .findByRepoIdAndScopeAndName(repoId, scopeName, packageName)
        .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));
  }

  private PackageVersion findPackageVersionByPackageIdAndVersion(
      final UUID packageId, final String versionName) {

    return this.packageVersionRepository
        .findByNpmPackageIdAndVersion(packageId, versionName)
        .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));
  }

  private void addDistTag(
      final UUID packageId, final PackageVersion packageVersion, final String tagName) {

    final var optionalDistTag =
        this.packageDistTagRepository.findByPackageVersionNpmPackageIdAndTagName(
            packageId, tagName);

    final PackageDistTag packageDistTag;

    if (optionalDistTag.isEmpty()) {
      packageDistTag = new PackageDistTag();
      packageDistTag.setTagName(tagName);
      packageDistTag.setCreatedAt(Instant.now());
    } else {
      packageDistTag = optionalDistTag.get();
    }

    packageDistTag.setPackageVersion(packageVersion);
    this.packageDistTagRepository.save(packageDistTag);
  }

  private void addKeywords(final Map<String, Object> version, final PackageVersion packageVersion) {

    final var keywords = (ArrayList<String>) version.get("keywords");

    if (keywords != null && !keywords.isEmpty()) {
      PackageKeyword packageKeyword;

      for (final var keyword : keywords) {
        if (keyword.isEmpty()) {
          continue;
        }

        packageKeyword = new PackageKeyword();
        packageKeyword.setKeyword(keyword);
        packageKeyword.setPackageVersion(packageVersion);
        packageKeyword.setCreatedAt(Instant.now());

        this.packageKeywordRepository.save(packageKeyword);
      }
    }
  }

  private void addMaintainers(
      final Map<String, Object> version, final PackageVersion packageVersion) {

    final var maintainers = (ArrayList<Map<String, String>>) version.get("maintainers");

    if (maintainers != null && !maintainers.isEmpty()) {
      PackageMaintainer packageMaintainer;

      for (final var maintainer : maintainers) {
        if (maintainer.isEmpty()) {
          continue;
        }

        packageMaintainer = new PackageMaintainer();
        packageMaintainer.setPackageVersion(packageVersion);
        packageMaintainer.setName(maintainer.get(NpmConstants.NAME));
        packageMaintainer.setEmail(maintainer.get(NpmConstants.EMAIL));
        packageMaintainer.setUrl(maintainer.get(NpmConstants.URL));
        packageMaintainer.setCreatedAt(Instant.now());

        this.packageMaintainerRepository.save(packageMaintainer);
      }
    }
  }

  private NpmPackage addPackage(
      final @Nullable String scopeName,
      final String packageName,
      final String latest,
      final Repo repo) {

    final var npmPackage = new NpmPackage();

    npmPackage.setScope(scopeName);
    npmPackage.setName(packageName);
    npmPackage.setLatest(latest);
    npmPackage.setRepo(repo);
    npmPackage.setCreatedAt(Instant.now());

    return this.npmPackageRepository.save(npmPackage);
  }

  private PackageVersion addVersion(
      final Map<String, Object> versionData,
      final String versionName,
      final NpmPackage npmPackage) {

    final var packageVersion = new PackageVersion();

    packageVersion.setVersion(versionName);
    packageVersion.setNpmPackage(npmPackage);
    packageVersion.setCreatedAt(Instant.now());

    this.populatePackageVersionFromMetadata(packageVersion, versionData);

    return this.packageVersionRepository.save(packageVersion);
  }

  private void populatePackageVersionFromMetadata(
      final PackageVersion version, final Map<String, Object> metadata) {

    version.setDescription(this.asString(metadata.get("description")));
    version.setHomepage(this.asString(metadata.get("homepage")));

    this.populateLicense(version, metadata.get("license"));
    this.populateRepository(version, metadata.get("repository"));
    this.populateAuthor(version, metadata.get("author"));
    this.populateBugs(version, metadata.get("bugs"));
  }

  private @Nullable String asString(final @Nullable Object obj) {

    return obj != null ? obj.toString() : null;
  }

  @SuppressWarnings("unchecked")
  private void populateLicense(final PackageVersion version, final @Nullable Object license) {

    if (license == null) {
      return;
    }

    if (license instanceof final String string) {
      version.setLicense(string);
    } else if (license instanceof Map) {
      version.setLicense(((Map<String, String>) license).getOrDefault("type", ""));
    }
  }

  @SuppressWarnings("unchecked")
  private void populateRepository(final PackageVersion version, final @Nullable Object repository) {

    if (repository instanceof Map) {
      final Map<String, String> repoMap = (Map<String, String>) repository;
      version.setRepositoryType(repoMap.get("type"));
      version.setRepositoryUrl(repoMap.get(NpmConstants.URL));
    }
  }

  @SuppressWarnings("unchecked")
  private void populateAuthor(final PackageVersion version, final @Nullable Object author) {

    if (author instanceof Map) {
      final Map<String, String> authorMap = (Map<String, String>) author;
      version.setAuthorName(authorMap.get(NpmConstants.NAME));
      version.setAuthorEmail(authorMap.get(NpmConstants.EMAIL));
      version.setAuthorUrl(authorMap.get(NpmConstants.URL));
    }
  }

  @SuppressWarnings("unchecked")
  private void populateBugs(final PackageVersion version, final @Nullable Object bugs) {

    if (bugs instanceof Map) {
      final Map<String, String> bugsMap = (Map<String, String>) bugs;
      version.setBugsUrl(bugsMap.get(NpmConstants.URL));
      version.setBugsEmail(bugsMap.get(NpmConstants.EMAIL));
    }
  }
}
