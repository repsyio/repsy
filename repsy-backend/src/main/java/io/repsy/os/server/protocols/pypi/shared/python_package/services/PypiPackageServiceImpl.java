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
package io.repsy.os.server.protocols.pypi.shared.python_package.services;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.f4b6a3.uuid.UuidCreator;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.ReleaseClassifier;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.ReleaseProjectURL;
import io.repsy.os.server.protocols.pypi.shared.python_package.mappers.PypiPackageConverter;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseClassifierRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseProjectURLRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseRepository;
import io.repsy.os.server.shared.utils.RequestBaseUrlUtils;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.pypi.shared.python_package.dtos.ReleaseVersionRequiresPython;
import io.repsy.protocols.pypi.shared.python_package.services.PypiPackageService;
import io.repsy.protocols.pypi.shared.utils.PackageUtils;
import io.repsy.protocols.pypi.shared.utils.ReleaseVersion;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class PypiPackageServiceImpl implements PypiPackageService<UUID> {

  private static final String ERR_PACKAGE_NOT_FOUND = "packageNotFound";

  private final RepoRepository repoRepository;
  private final ReleaseRepository releaseRepository;
  private final ConversionService conversionService;
  private final Configuration freeMarkerConfiguration;
  private final PypiPackageConverter pypiPackageConverter;
  private final PypiPackageRepository pypiPackageRepository;
  private final ReleaseClassifierRepository releaseClassifierRepository;
  private final ReleaseProjectURLRepository releaseProjectURLRepository;

  @Override
  public PackageInfo getPackage(final UUID repoId, final String packageNormalizedName) {

    final var pythonPypiPackage =
        this.pypiPackageRepository
            .findByRepoIdAndNormalizedName(repoId, packageNormalizedName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));

    return Objects.requireNonNull(
        this.conversionService.convert(pythonPypiPackage, PackageInfo.class));
  }

  @Override
  @Transactional(rollbackFor = IOException.class)
  public BaseUsages publishRelease(
      final BaseRepoInfo<UUID> repoInfo,
      final PackageUploadForm uploadForm,
      final ReleaseFileWriter fileWriter)
      throws IOException {

    this.addOrUpdateRelease(repoInfo, uploadForm);

    // Flush so a rejection by the database fails here, before the file is written. The
    // transaction, and the package row lock it holds, stays open while the file is written, so a
    // concurrent upload of the package waits for this one instead of racing it on the file.
    this.pypiPackageRepository.flush();

    return fileWriter.write();
  }

  private void addOrUpdateRelease(
      final BaseRepoInfo<UUID> repoInfo, final PackageUploadForm uploadForm) {

    final var releaseVersion = ReleaseVersion.of(uploadForm.getVersion());

    final var pythonPypiPackage =
        this.lockOrCreatePackage(
            repoInfo.getStorageKey(), uploadForm.getName(), uploadForm.getNormalizedName());

    final var existingReleaseOpt =
        this.releaseRepository.findByPypiPackageIdAndVersion(
            pythonPypiPackage.getId(), releaseVersion.getVersion());

    if (existingReleaseOpt.isPresent()) {
      this.updateRelease(existingReleaseOpt.get(), uploadForm, releaseVersion);
    } else {
      final var release = this.addRelease(uploadForm, releaseVersion, pythonPypiPackage);
      this.addReleaseClassifiers(uploadForm, release);
      this.addReleaseProjectURLs(uploadForm, release);
    }

    if (releaseVersion.isFinalRelease()) {
      pythonPypiPackage.setStableVersion(releaseVersion.getVersion());
    }

    pythonPypiPackage.setLatestVersion(releaseVersion.getVersion());
    this.pypiPackageRepository.save(pythonPypiPackage);
  }

  @Override
  public boolean packageExists(final UUID repoId, final String packageNormalizedName) {

    return this.pypiPackageRepository.existsByRepoIdAndNormalizedName(
        repoId, packageNormalizedName);
  }

  @Override
  public List<ReleaseVersionRequiresPython> getReleaseIndexListItemInfos(final UUID packageId) {

    return this.releaseRepository.findAllByPypiPackageId(packageId);
  }

  @Transactional
  public void deletePackage(final UUID repoId, final String normalizeName) {

    final var pythonPypiPackage =
        this.pypiPackageRepository
            .findByRepoIdAndNormalizedName(repoId, normalizeName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));

    this.pypiPackageRepository.delete(pythonPypiPackage);
  }

  /** delete given release, version should be normalized */
  @Transactional
  public void deleteRelease(final UUID packageId, final String releaseVersion) {

    final var release =
        this.releaseRepository
            .findByPypiPackageIdAndVersion(packageId, releaseVersion)
            .orElseThrow(() -> new ItemNotFoundException("releaseNotFound"));

    this.releaseRepository.delete(release);
  }

  @Transactional
  public void updatePackageReleaseVersionsIfNecessary(
      final PackageInfo packageInfo, final String releaseVersion) {

    if (releaseVersion.equals(packageInfo.getStableVersion())
        || releaseVersion.equals(packageInfo.getLatestVersion())) {
      final var releases =
          this.releaseRepository.findAllByPypiPackageIdOrderByCreatedAtDesc(packageInfo.getId());

      final var latest = releases.getFirst();

      final var stableVersionOptional =
          releases.stream().filter(Release::isFinalRelease).findFirst();

      final var newStableVersion = stableVersionOptional.map(Release::getVersion).orElse(null);

      this.pypiPackageRepository.updatePackageLatestVersionAndStableVersion(
          packageInfo.getId(), latest.getVersion(), newStableVersion);
    }
  }

  public ReleaseDetail getReleaseDetail(final UUID packageId, final String releaseVersion) {

    final var release =
        this.releaseRepository
            .findByPypiPackageIdAndVersion(packageId, releaseVersion)
            .orElseThrow(() -> new ItemNotFoundException("releaseNotFound"));

    final var classifiers = this.releaseClassifierRepository.findAllByReleaseId(release.getId());

    final var projectURLs = this.releaseProjectURLRepository.findAllByReleaseId(release.getId());

    return this.pypiPackageConverter.toReleaseDetail(release, classifiers, projectURLs);
  }

  public Page<io.repsy.os.generated.model.ReleaseListItem> getReleaseList(
      final UUID repoId, final String packageName, final Pageable pageable) {

    final var pythonPypiPackage =
        this.pypiPackageRepository
            .findByRepoIdAndNormalizedName(repoId, PackageUtils.normalizePackageName(packageName))
            .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));

    return this.releaseRepository
        .findAllByPypiPackageId(pythonPypiPackage.getId(), pageable)
        .map(this.pypiPackageConverter::toReleaseListItemDto);
  }

  public Page<io.repsy.os.generated.model.ReleaseListItem> getReleasesContainsVersion(
      final UUID repoId, final String packageName, final String version, final Pageable pageable) {

    final var pythonPypiPackage =
        this.pypiPackageRepository
            .findByRepoIdAndNormalizedName(repoId, PackageUtils.normalizePackageName(packageName))
            .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));

    return this.releaseRepository
        .findAllByPypiPackageIdContainsName(pythonPypiPackage.getId(), version, pageable)
        .map(this.pypiPackageConverter::toReleaseListItemDto);
  }

  public Page<io.repsy.os.generated.model.PypiPackageListItem> getPackageList(
      final UUID repoId, final Pageable pageable) {
    return this.pypiPackageRepository
        .findAllByRepoId(repoId, pageable)
        .map(this.pypiPackageConverter::toPackageListItemDto);
  }

  public Page<io.repsy.os.generated.model.PypiPackageListItem> getPackagesContainsName(
      final UUID repoId, final String name, final Pageable pageable) {

    return this.pypiPackageRepository
        .findAllByRepoIdContainsName(repoId, name, pageable)
        .map(this.pypiPackageConverter::toPackageListItemDto);
  }

  public ByteArrayResource getPackageList(final UUID repoId, final String repoName)
      throws IOException, TemplateException {

    final var packages = this.pypiPackageRepository.findAllByRepoIdAsListItem(repoId);

    final var template = this.freeMarkerConfiguration.getTemplate("packages.ftl");

    final var repoUri =
        UriComponentsBuilder.fromUriString(RequestBaseUrlUtils.resolveBaseUrl())
            .pathSegment(repoName)
            .build()
            .toUriString();

    return new ByteArrayResource(
        FreeMarkerTemplateUtils.processTemplateIntoString(
                template, Map.of("packages", packages, "repoName", repoName, "repoUri", repoUri))
            .getBytes(UTF_8));
  }

  public boolean isPackageHasNoReleases(final UUID packageId) {

    return this.releaseRepository.countAllByPypiPackageId(packageId) == 0;
  }

  /**
   * Returns the package row, locked until the transaction ends, inserting it first when this is the
   * first upload of the package name.
   *
   * <p>The lock is what makes uploads of one package take turns, so the release row (a first upload
   * of a version) and the archive (an upload of a file) each have one writer at a time. The insert
   * skips a row that already exists instead of failing on the unique index, because on PostgreSQL a
   * failed statement aborts the transaction.
   */
  private PypiPackage lockOrCreatePackage(
      final UUID repoId, final String packageName, final String normalizedName) {

    final var existing =
        this.pypiPackageRepository.findLockedByRepoIdAndNormalizedName(repoId, normalizedName);

    if (existing.isPresent()) {
      return existing.get();
    }

    if (!this.repoRepository.existsById(repoId)) {
      throw new ItemNotFoundException("repoNotFound");
    }

    this.pypiPackageRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repoId, packageName, normalizedName, Instant.now());

    return this.pypiPackageRepository
        .findLockedByRepoIdAndNormalizedName(repoId, normalizedName)
        .orElseThrow(() -> new ItemNotFoundException(ERR_PACKAGE_NOT_FOUND));
  }

  private void updateRelease(
      final Release release,
      final PackageUploadForm uploadForm,
      final ReleaseVersion releaseVersion) {

    if (!release.getVersion().equals(releaseVersion.getVersion())) {
      throw new IllegalStateException("Version mismatch in override operation");
    }

    release.setRequiresPython(uploadForm.getRequires_python());
    release.setSummary(uploadForm.getSummary());
    release.setHomePage(uploadForm.getHome_page());
    release.setAuthor(uploadForm.getAuthor());
    release.setAuthorEmail(uploadForm.getAuthor_email());
    release.setLicense(uploadForm.getLicense());
    release.setDescription(uploadForm.getDescription());
    release.setDescriptionContentType(uploadForm.getDescription_content_type());

    this.releaseRepository.save(release);

    this.releaseClassifierRepository.deleteAllByReleaseId(release.getId());
    this.releaseProjectURLRepository.deleteAllByReleaseId(release.getId());

    this.addReleaseClassifiers(uploadForm, release);
    this.addReleaseProjectURLs(uploadForm, release);
  }

  private Release addRelease(
      final PackageUploadForm uploadForm,
      final ReleaseVersion releaseVersion,
      final PypiPackage pythonPypiPackage) {

    final var release = new Release();

    release.setVersion(releaseVersion.getVersion());
    release.setPypiPackage(pythonPypiPackage);
    release.setFinalRelease(releaseVersion.isFinalRelease());
    release.setPreRelease(releaseVersion.isPreRelease());
    release.setPostRelease(releaseVersion.isPostRelease());
    release.setDevRelease(releaseVersion.isDevelopmentRelease());
    release.setRequiresPython(uploadForm.getRequires_python());
    release.setSummary(uploadForm.getSummary());
    release.setHomePage(uploadForm.getHome_page());
    release.setAuthor(uploadForm.getAuthor());
    release.setAuthorEmail(uploadForm.getAuthor_email());
    release.setLicense(uploadForm.getLicense());
    release.setDescription(uploadForm.getDescription());
    release.setDescriptionContentType(uploadForm.getDescription_content_type());
    release.setCreatedAt(Instant.now());

    this.releaseRepository.save(release);

    return release;
  }

  private void addReleaseClassifiers(final PackageUploadForm uploadForm, final Release release) {

    if (uploadForm.getClassifiers() == null) {
      return;
    }

    final var releaseClassifiers = new ArrayList<ReleaseClassifier>();

    for (final var classifier : uploadForm.getClassifiers()) {
      if (StringUtils.isBlank(classifier)) {
        continue;
      }

      final var split = classifier.split("::", 2);

      if (split.length != 2) {
        continue;
      }

      final var releaseClassifier = new ReleaseClassifier();

      releaseClassifier.setRelease(release);
      releaseClassifier.setClassifier(split[0].trim());
      releaseClassifier.setValue(split[1].trim());

      releaseClassifiers.add(releaseClassifier);
    }

    this.releaseClassifierRepository.saveAll(releaseClassifiers);
  }

  private void addReleaseProjectURLs(final PackageUploadForm uploadForm, final Release release) {

    if (uploadForm.getProject_urls() == null) {
      return;
    }

    final var releaseProjectURLs = new ArrayList<ReleaseProjectURL>();

    for (final var projectURL : uploadForm.getProject_urls()) {
      if (projectURL == null || projectURL.isBlank()) {
        continue;
      }

      final var split = projectURL.split(",", -1);

      if (split.length != 2) {
        continue;
      }

      final var releaseProjectURL = new ReleaseProjectURL();

      releaseProjectURL.setRelease(release);
      releaseProjectURL.setLabel(split[0]);
      releaseProjectURL.setUrl(split[1].trim());

      releaseProjectURLs.add(releaseProjectURL);
    }

    this.releaseProjectURLRepository.saveAll(releaseProjectURLs);
  }
}
