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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.npm.shared.constants.NpmConstants;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageDistributionTagListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageInfo;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageKeywordListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageMaintainerListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionInfo;
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
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.npm.shared.npm_package.dtos.NpmPackageSnapshot;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import io.repsy.protocols.npm.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
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

  private static final String PACKAGE_VERSION_ALREADY_EXISTS = "packageVersionAlreadyExists";
  private static final String VERSION_UNIQUE_CONSTRAINT =
      "ux_npm_package_version__package_id_version";

  private final RepoRepository repoRepository;
  private final NpmPackageRepository npmPackageRepository;
  private final PackageVersionRepository packageVersionRepository;
  private final PackageDistTagRepository packageDistTagRepository;
  private final PackageMaintainerRepository packageMaintainerRepository;
  private final PackageKeywordRepository packageKeywordRepository;
  private final NpmPackageConverter npmPackageConverter;

  @Transactional(rollbackFor = {IOException.class, URISyntaxException.class})
  @Override
  public BaseUsages publishVersion(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName,
      final Map<String, Object> payload,
      final VersionWriter writer)
      throws IOException, URISyntaxException {

    final PublishKind kind;

    try {
      final var repo =
          this.repoRepository
              .findById(repoInfo.getStorageKey())
              .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.REPO_NOT_FOUND));

      kind =
          this.recordVersion(
              repo, scopeName, packageName, versionName, payload, repoInfo.isAllowOverride());

      // Flush so a unique-index conflict fails here, before any file is written. The transaction,
      // and the package row it holds locked, stays open while the files are written, so a
      // concurrent publish of the package waits for this one instead of replacing its files.
      this.packageVersionRepository.flush();
    } catch (final DataIntegrityViolationException e) {
      // Only that index means the version exists. Any other violation is not the client's
      // conflict, so it is left to surface as the server error it is.
      if (!ConstraintViolations.violatesConstraint(e, VERSION_UNIQUE_CONSTRAINT)) {
        throw e;
      }

      throw new AccessNotAllowedException(PACKAGE_VERSION_ALREADY_EXISTS);
    }

    return writer.write(kind);
  }

  /**
   * Writes the rows of the version, taking the package row with a lock, and tells what it did.
   *
   * <p>The lock serialises the publishes of one package. That matters beyond the version row: every
   * publish rewrites the package's one metadata file from what it read, so two publishes of
   * different versions would otherwise lose one of them.
   */
  private PublishKind recordVersion(
      final Repo repo,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName,
      final Map<String, Object> payload,
      final boolean allowOverride) {

    final var repoId = repo.getId();
    final var existing =
        this.npmPackageRepository.findWithLockByRepoIdAndScopeAndName(
            repoId, scopeName, packageName);

    if (existing.isPresent()) {
      return this.recordVersionOf(existing.get(), versionName, payload, allowOverride);
    }

    // Skips the insert when a concurrent first publish of the package has done it, instead of
    // failing on the unique index: on PostgreSQL a failed statement aborts the transaction, which
    // also holds the file write. The statement waits for that publish to finish, and then finds
    // the committed package.
    final var inserted =
        this.npmPackageRepository.insertIfAbsent(
            UuidCreator.getTimeOrderedEpoch(),
            repoId,
            scopeName,
            packageName,
            versionName,
            Instant.now());

    final var npmPackage =
        this.npmPackageRepository
            .findWithLockByRepoIdAndScopeAndName(repoId, scopeName, packageName)
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));

    if (inserted == 0) {
      return this.recordVersionOf(npmPackage, versionName, payload, allowOverride);
    }

    this.addNewVersion(npmPackage, versionName, payload, true);

    return PublishKind.NEW_PACKAGE;
  }

  private PublishKind recordVersionOf(
      final NpmPackage npmPackage,
      final String versionName,
      final Map<String, Object> payload,
      final boolean allowOverride) {

    final var existing =
        this.packageVersionRepository.findByNpmPackageIdAndVersion(npmPackage.getId(), versionName);

    if (existing.isEmpty()) {
      this.addNewVersion(npmPackage, versionName, payload, false);
      return PublishKind.NEW_VERSION;
    }

    if (!allowOverride) {
      throw new AccessNotAllowedException(PACKAGE_VERSION_ALREADY_EXISTS);
    }

    this.replaceVersion(
        existing.get(), PackageUtils.extractVersionFromPayload(payload).getSecond());

    return PublishKind.REPLACES_VERSION;
  }

  private void addNewVersion(
      final NpmPackage npmPackage,
      final String versionName,
      final Map<String, Object> payload,
      final boolean firstVersion) {

    final var distTag = PackageUtils.extractFirstDistTagFromPayload(payload);
    final var versionData = PackageUtils.extractVersionFromPayload(payload).getSecond();
    final var packageVersion = this.addVersion(versionData, versionName, npmPackage);

    this.addMaintainers(versionData, packageVersion);
    this.addKeywords(versionData, packageVersion);

    final var isLatestTag = distTag.getKey().equals(NpmConstants.LATEST);

    // The first version of a package is its latest, whatever tag it is published under. The
    // package row was inserted with that version as its latest.
    if (firstVersion && !isLatestTag) {
      this.addDistTag(npmPackage.getId(), packageVersion, NpmConstants.LATEST);
    }

    this.addDistTag(npmPackage.getId(), packageVersion, distTag.getKey());

    if (!firstVersion && isLatestTag) {
      npmPackage.setLatest(packageVersion.getVersion());
      this.npmPackageRepository.save(npmPackage);
    }
  }

  private void replaceVersion(final PackageVersion version, final Map<String, Object> versionData) {

    this.populatePackageVersionFromMetadata(version, versionData);
    this.packageVersionRepository.save(version);

    this.packageMaintainerRepository.deleteAllMaintainersOfVersion(version.getId());
    this.packageKeywordRepository.deleteAllKeywordsOfVersion(version.getId());

    this.addMaintainers(versionData, version);
    this.addKeywords(versionData, version);
  }

  @Override
  public PackageInfo getPackage(
      final UUID repoId, final @Nullable String scopeName, final String packageName) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    return this.npmPackageConverter.toPackageInfo(npmPackage, npmPackage.getRepo());
  }

  @Override
  public NpmPackageSnapshot getSnapshot(
      final UUID repoId, final @Nullable String scopeName, final String packageName) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    final var versions =
        this.packageVersionRepository.findByNpmPackageId(npmPackage.getId()).stream()
            .sorted(
                Comparator.comparing(PackageVersion::getCreatedAt)
                    .thenComparing(PackageVersion::getVersion))
            .map(this::toSnapshot)
            .toList();

    final var tags = new LinkedHashMap<String, String>();

    for (final var tag : this.packageDistTagRepository.findAllTagsOfPackage(npmPackage.getId())) {
      tags.put(tag.getTag(), tag.getVersion());
    }

    return new NpmPackageSnapshot(
        npmPackage.getScope(),
        npmPackage.getName(),
        npmPackage.getLatest(),
        npmPackage.getCreatedAt(),
        versions,
        tags);
  }

  private NpmPackageSnapshot.Version toSnapshot(final PackageVersion version) {

    final var keywords =
        this.packageKeywordRepository.findAllByPackageVersionId(version.getId()).stream()
            .map(PackageKeywordListItem::getKeyword)
            .toList();
    final var maintainers =
        this.packageMaintainerRepository.findAllByPackageVersionId(version.getId()).stream()
            .map(
                maintainer ->
                    new NpmPackageSnapshot.Maintainer(
                        maintainer.getName(), maintainer.getEmail(), maintainer.getUrl()))
            .toList();

    return new NpmPackageSnapshot.Version(
        version.getVersion(),
        version.getCreatedAt(),
        version.getDescription(),
        version.getHomepage(),
        version.getLicense(),
        version.getRepositoryType(),
        version.getRepositoryUrl(),
        version.getAuthorName(),
        version.getAuthorEmail(),
        version.getAuthorUrl(),
        version.getBugsUrl(),
        version.getBugsEmail(),
        version.isDeprecated()
            ? Objects.requireNonNullElse(version.getDeprecationMessage(), "")
            : null,
        keywords,
        maintainers);
  }

  // The storage strategy throws the IOException of a failed removal unchecked (sneaky), which only
  // rolls the rows back when the rule names it.
  @Transactional(rollbackFor = IOException.class)
  @Override
  public PackageDeletion deletePackage(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final PackageRemover remover) {

    final var npmPackage = this.lockPackage(repoInfo, scopeName, packageName);

    return this.removePackage(npmPackage, this.getVersionNames(npmPackage.getId()), remover);
  }

  private PackageDeletion removePackage(
      final NpmPackage npmPackage, final List<String> versions, final PackageRemover remover) {

    this.npmPackageRepository.delete(npmPackage);
    // Flush so a rejection by the database fails here, before any file is removed.
    this.npmPackageRepository.flush();

    return new PackageDeletion(versions, remover.removePackage());
  }

  @Override
  public List<String> getVersionNames(final UUID packageId) {

    return this.packageVersionRepository.findByNpmPackageId(packageId).stream()
        .map(PackageVersion::getVersion)
        .toList();
  }

  @Transactional(rollbackFor = IOException.class)
  @Override
  public BaseUsages handleDeprecations(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final List<Pair<String, String>> deprecations,
      final MetadataWriter writer)
      throws IOException {

    final var npmPackage = this.lockPackage(repoInfo, scopeName, packageName);

    for (final var pair : deprecations) {
      if (pair.getSecond().isEmpty()) {
        this.unDeprecatePackage(npmPackage.getId(), pair.getFirst()); // un-deprecate
      } else {
        this.deprecatePackage(npmPackage.getId(), pair.getFirst(), pair.getSecond()); // deprecate
      }
    }

    // Flush so a database failure surfaces here, before the metadata file is touched.
    this.packageVersionRepository.flush();

    return writer.write();
  }

  @Transactional(rollbackFor = IOException.class)
  @Override
  public PackageDeletion deletePackageVersion(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String versionName,
      final VersionRemover versionRemover,
      final PackageRemover packageRemover)
      throws IOException {

    final var npmPackage = this.lockPackage(repoInfo, scopeName, packageName);

    // Counted under the lock, so a publish that finished first is part of what is left.
    final var versions = this.packageVersionRepository.findByNpmPackageId(npmPackage.getId());

    final var removed =
        versions.stream()
            .filter(version -> version.getVersion().equals(versionName))
            .findFirst()
            .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));

    if (versions.size() == 1) {
      return this.removePackage(npmPackage, List.of(versionName), packageRemover);
    }

    final var newLatest = this.nextLatestAfterRemoving(npmPackage, removed, versions);

    // The version goes first: deleting it takes the tags that point at it along, the latest tag
    // among them, which is then made anew on the version that takes over.
    this.packageVersionRepository.delete(removed);
    this.packageVersionRepository.flush();

    if (newLatest != null) {
      this.pointTagAt(npmPackage, newLatest, NpmConstants.LATEST);

      npmPackage.setLatest(newLatest.getVersion());
      this.npmPackageRepository.save(npmPackage);
      // Flush so a rejection by the database fails here, before any file is touched.
      this.npmPackageRepository.flush();
    }

    return new PackageDeletion(
        List.of(versionName),
        versionRemover.removeVersion(newLatest == null ? null : newLatest.getVersion()));
  }

  /**
   * The highest remaining version when the version that is being removed is the package's latest,
   * otherwise {@code null}.
   */
  private @Nullable PackageVersion nextLatestAfterRemoving(
      final NpmPackage npmPackage, final PackageVersion removed, final List<PackageVersion> all) {

    if (!npmPackage.getLatest().equals(removed.getVersion())) {
      return null;
    }

    final var remaining = all.stream().filter(version -> !version.equals(removed)).toList();
    final var latestName =
        PackageUtils.resolveLatestVersion(
            remaining.stream().map(PackageVersion::getVersion).toList());

    return remaining.stream()
        .filter(version -> version.getVersion().equals(latestName))
        .findFirst()
        .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));
  }

  public List<PackageDistributionTagMapListItem> getDistributionTags(
      final UUID repoId, final @Nullable String scope, final String packageName) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scope, packageName);

    return this.getDistributionTags(npmPackage.getId());
  }

  @Transactional(rollbackFor = IOException.class)
  @Override
  public BaseUsages addDistributionTag(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String tagName,
      final String versionName,
      final MetadataWriter writer)
      throws IOException {

    // The lock is held until the transaction ends, i.e. across the metadata write below, so a
    // concurrent publish or tag change of the package waits instead of losing this update.
    final var npmPackage = this.lockPackage(repoInfo, scopeName, packageName);

    final var packageVersion =
        this.packageVersionRepository
            .findByNpmPackageIdAndVersion(npmPackage.getId(), versionName)
            .orElseThrow(() -> new BadRequestException(ErrorConstants.PACKAGE_VERSION_NOT_FOUND));

    this.pointTagAt(npmPackage, packageVersion, tagName);

    // Flush so a database failure surfaces here, before the metadata file is touched.
    this.packageDistTagRepository.flush();

    return writer.write();
  }

  private void pointTagAt(
      final NpmPackage npmPackage, final PackageVersion packageVersion, final String tagName) {

    final var distTagOptional =
        this.packageDistTagRepository.findByPackageVersionNpmPackageIdAndTagName(
            npmPackage.getId(), tagName);

    if (distTagOptional.isEmpty()) {
      final var distTag = new PackageDistTag();

      distTag.setTagName(tagName);
      distTag.setPackageVersion(packageVersion);
      distTag.setCreatedAt(Instant.now());

      this.packageDistTagRepository.save(distTag);

      return;
    }

    final var distTag = distTagOptional.get();

    distTag.setPackageVersion(packageVersion);

    this.packageDistTagRepository.save(distTag);

    if (tagName.equals(NpmConstants.LATEST)) {
      npmPackage.setLatest(packageVersion.getVersion());

      this.npmPackageRepository.save(npmPackage);
    }
  }

  @Transactional(rollbackFor = IOException.class)
  @Override
  public BaseUsages removeDistributionTag(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName,
      final String tagName,
      final MetadataWriter writer)
      throws IOException {

    final var npmPackage = this.lockPackage(repoInfo, scopeName, packageName);

    this.packageDistTagRepository
        .findByPackageVersionNpmPackageIdAndTagName(npmPackage.getId(), tagName)
        .ifPresent(this.packageDistTagRepository::delete);

    // Flush so the delete is executed, and a failure surfaces, before the metadata file is touched.
    this.packageDistTagRepository.flush();

    return writer.write();
  }

  private NpmPackage lockPackage(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable String scopeName,
      final String packageName) {

    return this.npmPackageRepository
        .findWithLockByRepoIdAndScopeAndName(repoInfo.getStorageKey(), scopeName, packageName)
        .orElseThrow(() -> new ItemNotFoundException(ErrorConstants.PACKAGE_NOT_FOUND));
  }

  public Page<io.repsy.os.generated.model.NpmPackageListItem> getPackagesContainsScope(
      final UUID repoId, final @Nullable String scope, final Pageable pageable) {

    return this.npmPackageRepository
        .findAllByRepoIdAndLatestVersionContainsScope(repoId, scope, pageable)
        .map(this.npmPackageConverter::toPackageListItemDto);
  }

  public Page<io.repsy.os.generated.model.NpmPackageListItem> getPackagesByScopeContainsName(
      final UUID repoId, final @Nullable String scope, final String name, final Pageable pageable) {

    if (scope == null) {
      return this.npmPackageRepository
          .findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName(repoId, name, pageable)
          .map(this.npmPackageConverter::toPackageListItemDto);
    }

    return this.npmPackageRepository
        .findAllByRepoIdAndLatestVersionAndScopeContainsName(repoId, scope, name, pageable)
        .map(this.npmPackageConverter::toPackageListItemDto);
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

  public Page<io.repsy.os.generated.model.PackageVersionListItem> getVersionsContainsVersion(
      final UUID repoId,
      final @Nullable String scopeName,
      final String packageName,
      final String version,
      final Pageable pageable) {

    final var npmPackage = this.findPackageByRepoIdAndScopeAndName(repoId, scopeName, packageName);

    return this.packageVersionRepository
        .findAllByNpmPackageIdContainsVersion(npmPackage.getId(), version, pageable)
        .map(this.npmPackageConverter::toPackageVersionListItemDto);
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

    if (!(version.get("keywords") instanceof final Collection<?> keywords) || keywords.isEmpty()) {
      return;
    }

    PackageKeyword packageKeyword;

    for (final var raw : keywords) {
      if (!(raw instanceof final String keyword) || keyword.isEmpty()) {
        continue;
      }

      packageKeyword = new PackageKeyword();
      packageKeyword.setKeyword(keyword);
      packageKeyword.setPackageVersion(packageVersion);
      packageKeyword.setCreatedAt(Instant.now());

      this.packageKeywordRepository.save(packageKeyword);
    }
  }

  private void addMaintainers(
      final Map<String, Object> version, final PackageVersion packageVersion) {

    if (!(version.get("maintainers") instanceof final Collection<?> maintainers)
        || maintainers.isEmpty()) {
      return;
    }

    PackageMaintainer packageMaintainer;

    for (final var raw : maintainers) {
      if (!(raw instanceof final Map<?, ?> maintainer) || maintainer.isEmpty()) {
        continue;
      }

      packageMaintainer = new PackageMaintainer();
      packageMaintainer.setPackageVersion(packageVersion);
      packageMaintainer.setName((String) maintainer.get(NpmConstants.NAME));
      packageMaintainer.setEmail((String) maintainer.get(NpmConstants.EMAIL));
      packageMaintainer.setUrl((String) maintainer.get(NpmConstants.URL));
      packageMaintainer.setCreatedAt(Instant.now());

      this.packageMaintainerRepository.save(packageMaintainer);
    }
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
