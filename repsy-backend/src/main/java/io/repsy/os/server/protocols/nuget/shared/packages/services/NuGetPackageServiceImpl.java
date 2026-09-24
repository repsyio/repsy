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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.NuGetDeletedItem;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.mappers.NuGetPackageConverter;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.utils.OffsetPageRequest;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@Transactional(readOnly = true)
@NullMarked
public class NuGetPackageServiceImpl implements NuGetPackageService<UUID> {

  private static final String ERR_PACKAGE_NOT_FOUND = "packageNotFound";
  private static final String ERR_VERSION_NOT_FOUND = "versionNotFound";
  private static final String VERSION_UNIQUE_CONSTRAINT =
      "ux_nuget_package_version__package_id_version";

  private final NuGetPackageRepository packageRepository;
  private final NuGetPackageVersionRepository packageVersionRepository;
  private final NuGetPackageConverter converter;

  public NuGetPackageServiceImpl(
      final NuGetPackageRepository packageRepository,
      final NuGetPackageVersionRepository packageVersionRepository,
      final NuGetPackageConverter converter) {

    this.packageRepository = packageRepository;
    this.packageVersionRepository = packageVersionRepository;
    this.converter = converter;
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public BaseUsages publishVersion(
      final BaseRepoInfo<UUID> repoInfo,
      final String packageId,
      final String version,
      final String nuspecXml,
      final @Nullable String readme,
      final PackageFilesWriter filesWriter)
      throws IOException {

    // The package row is created in this transaction too, so a first push that fails leaves no
    // package without versions behind (RPS-1061).
    final var pkg = this.findOrCreatePackage(repoInfo.getId(), packageId);

    final var existingVersion =
        this.packageVersionRepository.findByNugetPackageIdAndVersion(pkg.getId(), version);

    if (existingVersion.isPresent()) {
      if (!repoInfo.isAllowOverride()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Version " + version + " of package " + pkg.getPackageId() + " already exists.");
      }
      this.packageVersionRepository.delete(existingVersion.get());
      // Hibernate runs inserts before deletes at flush, so the new row would hit the unique
      // (package_id, version) index while the old one is still there. Flush the delete first.
      this.packageVersionRepository.flush();
    }

    final var pkgVersion = this.createNuGetPackageVersion(pkg, nuspecXml, version, readme);

    // Flush so a unique-index conflict (a concurrent push of the same version) fails here, before
    // any file is written. The transaction, and the row lock it holds, stays open while the files
    // are written, so a losing push waits for the winner instead of replacing its files.
    try {
      this.packageVersionRepository.saveAndFlush(pkgVersion);
    } catch (final DataIntegrityViolationException e) {
      // Only that index means the version exists. Any other violation is not the client's
      // conflict, so it is left to surface as the server error it is.
      if (!ConstraintViolations.violatesConstraint(e, VERSION_UNIQUE_CONSTRAINT)) {
        throw e;
      }

      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Version " + version + " of package " + pkg.getPackageId() + " already exists.");
    }

    return filesWriter.write(existingVersion.isPresent());
  }

  @Override
  public List<String> getVersions(final BaseRepoInfo<UUID> repoInfo, final String packageId) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    // The flat-container version list is documented in ascending version order; the repository
    // keeps returning rows newest-published-first, so the ordering is fixed up here (RPS-1130).
    return this.packageVersionRepository
        .findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(v -> v.getVersion().toLowerCase(Locale.ROOT))
        .sorted(NuGetPackageUtils.VERSION_COMPARATOR)
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

    // The registration leaves are documented in ascending version order and paged as a
    // contiguous range; the repository keeps returning rows newest-published-first, so the
    // ordering is fixed up here (RPS-1130).
    return this.packageVersionRepository
        .findByNugetPackageIdOrderByPublishedAtDesc(pkg.getId())
        .stream()
        .map(v -> this.converter.toVersionInfo(v, packageId))
        .sorted(
            Comparator.comparing(NuGetVersionInfo::version, NuGetPackageUtils.VERSION_COMPARATOR))
        .toList();
  }

  @Override
  public Page<NuGetVersionInfo> getVersionInfosPage(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final Pageable pageable) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    final var sortedPageable =
        PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            withTiebreaker(
                pageable.getSort(), Sort.by(Sort.Direction.DESC, "publishedAt"), "version"));

    return this.packageVersionRepository
        .findByNugetPackageId(pkg.getId(), sortedPageable)
        .map(v -> this.converter.toVersionInfo(v, packageId));
  }

  @Override
  public Optional<NuGetVersionInfo> findVersionInfo(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoInfo.getId(), packageId.toLowerCase(Locale.ROOT))
        .flatMap(pkg -> this.findVersion(pkg, version))
        .map(v -> this.converter.toVersionDetail(v, packageId));
  }

  @Override
  public Page<NuGetPackageSearchResult> search(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease,
      final boolean semVer2) {

    if (take <= 0) {
      return new org.springframework.data.domain.PageImpl<>(List.of());
    }

    // skip is the client's exact offset. A PageRequest can only start at a multiple of take, so it
    // would serve the window that starts at the previous multiple instead.
    return this.searchPage(
        repoInfo, query, OffsetPageRequest.of(Math.max(skip, 0), take), prerelease, semVer2);
  }

  @Override
  public Page<NuGetPackageSearchResult> searchPage(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final Pageable pageable,
      final boolean prerelease,
      final boolean semVer2) {

    // package_id is unique per repo, so it alone gives every page a stable order.
    final var sortedPageable =
        new OffsetPageRequest(
            pageable.getOffset(),
            pageable.getPageSize(),
            pageable.getSort().isSorted()
                ? pageable.getSort()
                : Sort.by(Sort.Direction.ASC, "packageId"));

    final var pkgPage =
        this.packageRepository.search(
            repoInfo.getId(), likePattern("%", query, "%"), semVer2, sortedPageable);

    return pkgPage.map(pkg -> this.toSearchResult(pkg, prerelease, semVer2));
  }

  @Override
  public List<String> autocomplete(
      final BaseRepoInfo<UUID> repoInfo,
      final String query,
      final int skip,
      final int take,
      final boolean prerelease,
      final boolean semVer2) {

    if (take <= 0) {
      return List.of();
    }

    // skip is not capped, so even a capped take can still overflow int here (e.g. skip near
    // Integer.MAX_VALUE); addExact turns that into an ArithmeticException the handler maps to 400
    // instead of silently wrapping to a negative page size.
    final var pageable =
        PageRequest.of(
            0, Math.max(Math.addExact(skip, take), 1), Sort.by(Sort.Direction.ASC, "packageId"));
    return this.packageRepository
        .search(repoInfo.getId(), likePattern("", query, "%"), semVer2, pageable)
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
        this.findVersion(pkg, version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    this.packageVersionRepository.incrementDownloadCount(pkgVersion.getId());
  }

  @Override
  @Transactional
  public void unlistVersion(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    final var pkgVersion =
        this.findVersion(pkg, version)
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
        this.findVersion(pkg, version)
            .orElseThrow(() -> new ItemNotFoundException(ERR_VERSION_NOT_FOUND));

    pkgVersion.setListed(true);

    this.packageVersionRepository.save(pkgVersion);
  }

  @Override
  public boolean versionExists(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoInfo.getId(), packageId.toLowerCase(Locale.ROOT))
        .map(
            pkg ->
                this.packageVersionRepository
                    .findByNugetPackageIdAndVersion(pkg.getId(), version)
                    .isPresent())
        .orElse(false);
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

    return this.deleteVersionAndGetDeletedItem(repoInfo, packageId, version)
        == NuGetDeletedItem.PACKAGE;
  }

  @Transactional
  public NuGetDeletedItem deleteVersionAndGetDeletedItem(
      final BaseRepoInfo<UUID> repoInfo, final String packageId, final String version) {

    final var pkg = this.findPackage(repoInfo.getId(), packageId);

    final var pkgVersion =
        this.findVersion(pkg, version)
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

  /**
   * Finds a version by the string a client sent, matched by its canonical form: versions are stored
   * without build metadata, so {@code 1.0.0+build} and {@code 1.0.0} are the same version.
   */
  private Optional<NuGetPackageVersion> findVersion(final NuGetPackage pkg, final String version) {

    return this.packageVersionRepository.findByNugetPackageIdAndVersionIgnoreCase(
        pkg.getId(), NuGetPackageUtils.normalizeNuGetVersion(version));
  }

  private NuGetPackage findPackage(final UUID repoId, final String packageId) {
    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoId, packageId.toLowerCase(Locale.ROOT))
        .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));
  }

  /**
   * Returns the package row, inserting it when this is the first version of the package.
   *
   * <p>The insert skips a row that already exists instead of failing on the unique index: on
   * PostgreSQL a failed statement aborts the transaction, which now also holds the version row and
   * the file write. When a concurrent first push has inserted the package but not committed yet,
   * the statement waits for it, and then finds the committed row.
   */
  private NuGetPackage findOrCreatePackage(final UUID repoId, final String packageId) {

    final var normalizedId = packageId.toLowerCase(Locale.ROOT);
    final var existing =
        this.packageRepository.findByRepoIdAndPackageIdIgnoreCase(repoId, normalizedId);

    if (existing.isPresent()) {
      return existing.get();
    }

    this.packageRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repoId, normalizedId, Instant.now());

    return this.packageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repoId, normalizedId)
        .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));
  }

  private NuGetPackageVersion createNuGetPackageVersion(
      final NuGetPackage nugetPackage,
      final String nuspecXml,
      final String version,
      final @Nullable String readme) {

    final var pkgVersion = new NuGetPackageVersion();
    pkgVersion.setNugetPackage(nugetPackage);
    pkgVersion.setVersion(version);
    pkgVersion.setPrerelease(version.contains("-"));
    pkgVersion.setListed(true);
    pkgVersion.setPublishedAt(Instant.now());
    pkgVersion.setDownloadCount(0);
    pkgVersion.setCreatedAt(Instant.now());
    pkgVersion.setTitle(NuGetPackageUtils.extractTitle(nuspecXml));
    pkgVersion.setDescription(NuGetPackageUtils.extractMetadataField(nuspecXml, "description"));
    pkgVersion.setAuthors(NuGetPackageUtils.extractMetadataField(nuspecXml, "authors"));
    pkgVersion.setTags(NuGetPackageUtils.extractTags(nuspecXml));
    pkgVersion.setIconUrl(NuGetPackageUtils.extractUrl(nuspecXml, "iconUrl"));
    pkgVersion.setLicenseUrl(NuGetPackageUtils.extractUrl(nuspecXml, "licenseUrl"));
    pkgVersion.setProjectUrl(NuGetPackageUtils.extractUrl(nuspecXml, "projectUrl"));
    pkgVersion.setRepositoryUrl(NuGetPackageUtils.extractRepositoryUrl(nuspecXml));
    pkgVersion.setReadme(readme);

    final var deps =
        NuGetPackageUtils.extractDependenciesFromNuspec(
            nuspecXml, nugetPackage.getPackageId(), version);
    if (!deps.isEmpty()) {
      // A failure here must fail the push (RPS-1146): the client sent these dependencies, so
      // storing the version without them, silently, would corrupt what was published. This runs
      // before saveAndFlush, so nothing is written when it throws.
      pkgVersion.setDependencies(NuGetPackageUtils.toDependenciesJson(deps));
    }

    return pkgVersion;
  }

  /**
   * Returns the requested sort, or {@code defaultSort} when there is none, followed by {@code
   * tiebreaker} ascending so rows with equal sort values keep a stable order across pages.
   */
  private static Sort withTiebreaker(
      final Sort requested, final Sort defaultSort, final String tiebreaker) {

    final var sort = requested.isSorted() ? requested : defaultSort;

    return sort.getOrderFor(tiebreaker) == null
        ? sort.and(Sort.by(Sort.Direction.ASC, tiebreaker))
        : sort;
  }

  /** The lower-cased {@code LIKE} pattern of {@code query} with its wildcards taken literally. */
  private static String likePattern(final String prefix, final String query, final String suffix) {

    final var escaped =
        query
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");

    return prefix + escaped + suffix;
  }

  private NuGetPackageSearchResult toSearchResult(
      final NuGetPackage pkg, final boolean prerelease, final boolean semVer2) {

    final var allVersions =
        this.packageVersionRepository.findByNugetPackageIdAndIsListedTrueOrderByPublishedAtDesc(
            pkg.getId());

    return this.converter.toSearchResult(pkg, prerelease, semVer2, allVersions);
  }
}
