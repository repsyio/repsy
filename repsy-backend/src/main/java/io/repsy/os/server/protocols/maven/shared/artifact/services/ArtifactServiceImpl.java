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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType.REDEPLOY;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.PLUGIN;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.RELEASE;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.SNAPSHOT;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.VersionDeveloperInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.VersionLicenseInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionDeveloper;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionLicense;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ArtifactServiceImpl implements ArtifactService<UUID> {

  private static final String SOURCES_SUFFIX = "-sources";
  private static final String JAVADOC_SUFFIX = "-javadoc";
  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String POM_SUFFIX = ".pom";
  private static final String SIGNED_POM_SUFFIX = ".asc";
  private static final String ERR_ARTIFACT_VERSION_NOT_FOUND = "artifactVersionNotFound";
  private static final String ERR_ARTIFACT_NOT_FOUND = "artifactNotFound";

  private final RepoRepository repoRepository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final VersionDeveloperRepository versionDeveloperRepository;
  private final VersionLicenseRepository versionLicenseRepository;
  private final ArtifactConverter artifactConverter;
  private final PGPVerifierService pgpVerifierService;
  private final KeyStoreService keyStoreService;

  @Qualifier("osStorageStrategyMaven")
  private final StorageStrategy storageStrategy;

  @Override
  public @Nullable MutablePair<@Nullable ArtifactDeployType, @Nullable ArtifactVersionType>
      getDeployAndVersionType(
          final BaseRepoInfo<UUID> baseRepoInfo, final StoragePath storagePath) {

    final var fileName = storagePath.getRelativePath().getFileName();

    if (ArtifactUtils.isChecksumFile(fileName)) {
      return new MutablePair<>(null, null);
    }

    final var repoInfo = (RepoInfo) baseRepoInfo;
    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav == null) {
      log.error(
          "Maven Gav could not calculated for repo {} for file {}",
          repoInfo.getStorageKey(),
          fileName);
      return null;
    }

    return new MutablePair<>(
        this.getDeployTypeByGav(repoInfo, gav), gav.isSnapshot() ? SNAPSHOT : RELEASE);
  }

  @Override
  public MutablePair<@Nullable ArtifactDeployType, @Nullable ArtifactVersionType>
      getDeployAndVersionTypesByMetadataTypeFiles(
          final BaseRepoInfo<UUID> baseRepoInfo, final byte[] content, final String fileName)
          throws IOException, XmlPullParserException {

    if (ArtifactUtils.isChecksumFile(fileName)) {
      return new MutablePair<>(null, null);
    }

    final var repoInfo = (RepoInfo) baseRepoInfo;
    final var metadata = ArtifactUtils.readMetadata(content);

    if (metadata == null) {
      log.error("maven-metadata could not read for {}", repoInfo.getName());
      throw new AccessNotAllowedException("unReadableMetadataFile");
    }

    if (ArtifactUtils.isPluginMetadata(metadata)) {
      return new MutablePair<>(ArtifactDeployType.NEW, PLUGIN);
    }

    return this.getDeployAndVersionTypesByMetadata(repoInfo, metadata);
  }

  @Override
  public void checkDeploymentRules(
      final BaseRepoInfo<UUID> baseRepoInfo,
      final MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair,
      final StoragePath storagePath) {

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav == null) {
      return;
    }

    final var repoInfo = (RepoInfo) baseRepoInfo;

    if (gav.getExtension() != null
        && "jar".equals(gav.getExtension())
        && !repoInfo.isAllowOverride()) {
      this.checkByStorage(repoInfo, storagePath);
      return;
    }

    this.handleDeployTypeRules(repoInfo, artifactPair);
  }

  @Override
  @Transactional
  public void createOrUpdateArtifact(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final Resource resource) {

    final var repo =
        this.repoRepository
            .findByNameAndType(repoInfo.getName(), RepoType.MAVEN)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var fullPath = storagePath.getPath().replace("\\", "/");
    final var versionPath =
        fullPath.substring(fullPath.indexOf("/") + 1, fullPath.lastIndexOf("/"));

    if (!ArtifactUtils.containsIgnoreCase(storagePath.getRelativePath().getPath(), POM_SUFFIX)
        || ArtifactUtils.isChecksumFile(
            Objects.requireNonNull(storagePath.getRelativePath().getFileName()))) {
      return;
    }

    // Cannot create artifact for signed files.
    if (storagePath.getRelativePath().getPath().endsWith(SIGNED_POM_SUFFIX)) {
      this.processSignedFileProcess(resource, storagePath, repo);
      return;
    }

    final var gav = ArtifactUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    final var pomModel = ArtifactUtils.readModel(resource);

    if (this.checkExtractedInfos(pomModel, gav, storagePath, repo)) {
      assert gav != null;
      this.createOrUpdateArtifactByPomFile(repo, gav, versionPath, pomModel);
    }
  }

  @Override
  public StoragePath getNonSignedStoragePath(final StoragePath signedStoragePath) {

    final var signaturePath = signedStoragePath.getRelativePath().getPath();

    final var suffixLength = SIGNED_POM_SUFFIX.length();

    final var nonSignedFileName = signaturePath.substring(0, signaturePath.length() - suffixLength);

    return StoragePath.of(signedStoragePath.getStorageKey(), nonSignedFileName);
  }

  @Transactional
  public void deleteArtifact(final UUID repoId, final String groupName, final String artifactName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    this.artifactRepository.delete(artifact);
  }

  @Transactional
  public void deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName,
      final Versioning versioning) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(
                repoInfo.getStorageKey(), groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    final var artifactVersion =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(artifact.getId(), versionName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND));

    this.artifactVersionRepository.delete(artifactVersion);

    if (!Objects.equals(artifact.getLatest(), versioning.getLatest())
        || !Objects.equals(artifact.getRelease(), versioning.getRelease())) {
      artifact.setLatest(versioning.getLatest());
      artifact.setRelease(versioning.getRelease());

      this.artifactRepository.save(artifact);
    }
  }

  @Transactional
  @SuppressWarnings("all")
  public void deleteGroup(final UUID repoId, final String groupName) {

    final var repo =
        this.repoRepository
            .findById(repoId)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var artifacts =
        this.artifactRepository.findAllByRepoIdAndGroupName(repo.getId(), groupName);

    for (final Artifact artifact : artifacts) {
      // Do not change this with delete all method.
      this.artifactRepository.delete(artifact);
    }
  }

  public ArtifactVersionInfo getArtifactVersion(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final @Nullable String versionName)
      throws ItemNotFoundException {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    final ArtifactVersion version;

    if (versionName == null) {
      version =
          this.artifactVersionRepository
              .findByArtifactIdAndVersionName(artifact.getId(), artifact.getLatest())
              .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND));
    } else {
      version =
          this.artifactVersionRepository
              .findByArtifactIdAndVersionName(artifact.getId(), versionName)
              .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND));
    }

    final var developers = this.getVersionDevelopers(version.getId());
    final var licenses = this.getVersionLicenses(version.getId());

    return this.artifactConverter.toArtifactVersionInfo(artifact, version, developers, licenses);
  }

  /** Get the artifact version's pom filename */
  public @Nullable String getArtifactVersionPomFilename(
      final RepoInfo repoInfo,
      final Path artifactBasePath,
      final ArtifactVersionType artifactVersionType,
      final String artifactName,
      final String versionName)
      throws IOException, XmlPullParserException {

    return switch (artifactVersionType) {
      case RELEASE -> artifactName + "-" + versionName + ".pom";
      case SNAPSHOT ->
          this.getSnapshotArtifactVersionPomFileName(
              artifactBasePath, repoInfo, versionName, artifactName);
      default -> null;
    };
  }

  public Page<ArtifactVersionListItem> getArtifactVersions(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final Pageable pageable) {

    return this.artifactVersionRepository.findAllByRepoIdAndGroupNameAndArtifactName(
        repoId, groupName, artifactName, pageable);
  }

  public Page<ArtifactVersionListItem> getArtifactVersionsContainsVersion(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final String version,
      final Pageable pageable) {

    return this.artifactVersionRepository
        .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
            repoId, groupName, artifactName, version, pageable);
  }

  public Page<ArtifactListItem> getArtifactsContainsGroupName(
      final UUID repoId, final String groupName, final Pageable pageable) {

    return this.artifactRepository.findAllByRepoIdAndContainsGroupName(repoId, groupName, pageable);
  }

  public Page<ArtifactListItem> getArtifactsContainsArtifactName(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final Pageable pageable) {

    return this.artifactRepository.findAllByRepoIdContainsArtifactName(
        repoId, groupName, artifactName, pageable);
  }

  public List<Artifact> getArtifacts(final UUID repoId, final String groupName) {

    return this.artifactRepository.findAllByRepoIdAndGroupName(repoId, groupName);
  }

  public List<String> getGroupNames(final UUID repoId) {

    return this.artifactRepository.findGroupNamesByRepoId(repoId);
  }

  public boolean hasOnlyOneArtifact(final UUID repoId, final String groupName) {

    final var artifactCount = this.artifactRepository.countByRepoIdAndGroupName(repoId, groupName);

    return artifactCount == 1;
  }

  public boolean hasOnlyOneVersion(
      final UUID repoId, final String groupName, final String artifactName) {

    final var versionCount =
        this.artifactVersionRepository.countByRepoIdAndGroupNameAndArtifactName(
            repoId, groupName, artifactName);

    return versionCount == 1;
  }

  private void createArtifactVersionByGav(
      final String versionPath,
      final Gav gav,
      final @Nullable Model pomModel,
      final Artifact artifact) {

    final var repo = artifact.getRepo();

    if (this.versionTypeNotMatched(repo, gav.isSnapshot())) {
      throw new AccessNotAllowedException("versionTypeNotMatched");
    }

    final var storagePath = StoragePath.of(artifact.getRepo().getId(), versionPath);

    final var version = new ArtifactVersion();

    version.setArtifact(artifact);
    version.setCreatedAt(Instant.now());
    version.setLastUpdatedAt(Instant.now());
    version.setType(gav.isSnapshot() ? SNAPSHOT : RELEASE);
    version.setVersionName(gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion());

    final var filesInVersionDir =
        this.storageStrategy.listStorageItems(storagePath).stream()
            .map(StorageItemInfo::getPath)
            .toList();

    this.setVersionProperties(filesInVersionDir, pomModel, version);

    final var savedArtifactVersion = this.artifactVersionRepository.save(version);

    this.createVersionDevelopersByPomModel(pomModel, savedArtifactVersion);
    this.createVersionLicensesByPomModel(pomModel, savedArtifactVersion);
    this.updateReleaseAndLatestVersions(artifact);
  }

  private void processSignedFileProcess(
      final Resource resource, final StoragePath storagePath, final Repo repo) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(storagePath);

    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException("itemNotFound");
    }

    this.verifySignature(resource, storagePath, repo);
    this.markArtifactSigned(repo, gav);
  }

  private void markArtifactSigned(final Repo repo, final Gav gav) {

    final var artifact = this.getArtifact(repo.getId(), gav.getArtifactId(), gav.getGroupId());

    if (artifact == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    final var artifactVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

    if (artifactVersion == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    artifactVersion.setSigned(true);

    this.artifactVersionRepository.save(artifactVersion);
  }

  private void verifySignature(
      final Resource signedFileResource, final StoragePath signedStoragePath, final Repo repo) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(signedStoragePath);

    final var nonSignedFileResource =
        this.storageStrategy
            .get(nonSignedStoragePath, repo.getName())
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    final var customKeys = this.keyStoreService.findByRepoId(repo.getId());

    this.pgpVerifierService.verify(nonSignedFileResource, signedFileResource, customKeys);
  }

  private void createVersionDevelopersByPomModel(
      final @Nullable Model pomModel, final ArtifactVersion artifactVersion) {

    if (pomModel == null
        || pomModel.getDevelopers() == null
        || pomModel.getDevelopers().isEmpty()) {
      return;
    }

    for (final var developer : pomModel.getDevelopers()) {
      final var versionDeveloper = new VersionDeveloper();

      versionDeveloper.setArtifactVersion(artifactVersion);
      versionDeveloper.setName(developer.getName());
      versionDeveloper.setEmail(developer.getEmail());

      this.versionDeveloperRepository.save(versionDeveloper);
    }
  }

  private void createVersionLicensesByPomModel(
      final @Nullable Model pomModel, final ArtifactVersion artifactVersion) {

    if (pomModel == null || pomModel.getLicenses() == null || pomModel.getLicenses().isEmpty()) {
      return;
    }

    for (final var license : pomModel.getLicenses()) {
      final var versionLicense = new VersionLicense();

      versionLicense.setArtifactVersion(artifactVersion);
      versionLicense.setName(license.getName());
      versionLicense.setUrl(license.getUrl());

      this.versionLicenseRepository.save(versionLicense);
    }
  }

  /* Return developer info's of given artifact version */
  private List<VersionDeveloperInfo> getVersionDevelopers(final UUID versionId) {

    return this.versionDeveloperRepository.findAllByArtifactVersionId(versionId);
  }

  /* Return license info's of given artifact version */
  private List<VersionLicenseInfo> getVersionLicenses(final UUID versionId) {

    return this.versionLicenseRepository.findAllByArtifactVersionId(versionId);
  }

  private MutablePair<ArtifactDeployType, @Nullable ArtifactVersionType>
      getDeployAndVersionTypesByMetadata(final RepoInfo repoInfo, final Metadata metadata) {

    final MutablePair<ArtifactDeployType, @Nullable ArtifactVersionType> result;

    final var lastComingVersionName = metadata.getVersioning().getVersions().getLast();

    final var artifact =
        this.getArtifact(repoInfo.getStorageKey(), metadata.getArtifactId(), metadata.getGroupId());

    if (artifact == null) {
      if (ArtifactUtils.isSnapshot(lastComingVersionName)) {
        result = new MutablePair<>(ArtifactDeployType.NEW, SNAPSHOT);
      } else {
        result = new MutablePair<>(ArtifactDeployType.NEW, RELEASE);
      }

      return result;
    }

    final var artifactVersionOptional =
        this.artifactVersionRepository.findByArtifactIdAndVersionName(
            artifact.getId(), lastComingVersionName);

    if (artifactVersionOptional.isPresent()) {
      result = new MutablePair<>(REDEPLOY, null);
    } else {
      if (ArtifactUtils.isSnapshot(lastComingVersionName)) {
        result = new MutablePair<>(ArtifactDeployType.NEW, SNAPSHOT);
      } else {
        result = new MutablePair<>(ArtifactDeployType.NEW, RELEASE);
      }
    }

    return result;
  }

  private @Nullable ArtifactVersion getArtifactVersionByGav(final UUID artifactId, final Gav gav) {

    final var gavVersion = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    final var artifactVersionOptional =
        this.artifactVersionRepository.findByArtifactIdAndVersionName(artifactId, gavVersion);

    return artifactVersionOptional.orElse(null);
  }

  private List<String> getArtifactVersionNames(final UUID artifactId) {

    final var versions = this.artifactVersionRepository.findByArtifactId(artifactId);

    final var versionNames = new ArrayList<String>();

    for (final var artifactVersion : versions) {
      versionNames.add(artifactVersion.getVersionName());
    }

    versionNames.sort(new VersionComparator());
    return versionNames;
  }

  private ArtifactDeployType getDeployTypeByGav(final RepoInfo repoInfo, final Gav gav) {

    final var artifact =
        this.getArtifact(repoInfo.getStorageKey(), gav.getArtifactId(), gav.getGroupId());

    if (artifact == null) {
      return ArtifactDeployType.NEW;
    }

    final var artifactVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

    if (artifactVersion == null) {
      return ArtifactDeployType.NEW;
    } else {
      return REDEPLOY;
    }
  }

  private void checkVersionTypeRules(
      final RepoInfo repoInfo, final @Nullable ArtifactVersionType versionType) {

    if (versionType == null) {
      return;
    }

    if (versionType == RELEASE && Boolean.FALSE.equals(repoInfo.getReleases())) {
      throw new AccessNotAllowedException("releaseVersionsAreProhibited");
    } else if (versionType == SNAPSHOT && Boolean.FALSE.equals(repoInfo.getSnapshots())) {
      throw new AccessNotAllowedException("snapshotVersionsAreProhibited");
    }
  }

  private Artifact createArtifactByGav(
      final Repo repo, final Gav gav, final @Nullable Model pomModel) {

    final var artifact = new Artifact();

    if (this.versionTypeNotMatched(repo, gav.isSnapshot())) {
      throw new AccessNotAllowedException("versionTypeNotMatched");
    }

    artifact.setArtifactName(gav.getArtifactId());
    artifact.setGroupName(gav.getGroupId());
    artifact.setCreatedAt(Instant.now());
    artifact.setLastUpdatedAt(Instant.now());
    artifact.setRepo(repo);

    if (pomModel != null) {
      this.setArtifactProperties(pomModel, artifact);
    }

    return this.artifactRepository.save(artifact);
  }

  private boolean versionTypeNotMatched(final Repo repo, final boolean snapshot) {
    return snapshot != repo.getSnapshots() && !(repo.getSnapshots() && repo.getReleases());
  }

  private @Nullable String getGroupIdFromPomModel(final Model pomModel) {

    var groupId = pomModel.getGroupId();

    if (groupId == null && pomModel.getParent() != null) {
      groupId = pomModel.getParent().getGroupId();
      pomModel.setGroupId(groupId);
    }

    return groupId;
  }

  private void updateReleaseAndLatestVersions(final Artifact artifact) {

    final var versionNames = this.getArtifactVersionNames(artifact.getId());
    final var latest = versionNames.getLast();

    String release = null;

    for (int i = versionNames.size() - 1; i >= 0; i--) {
      if (!ArtifactUtils.isSnapshot(versionNames.get(i))) {
        release = versionNames.get(i);
        break;
      }
    }

    artifact.setLatest(latest);
    artifact.setRelease(release);
    this.artifactRepository.save(artifact);
  }

  private boolean checkExtractedInfos(
      final @Nullable Model pomModel,
      final @Nullable Gav gav,
      final StoragePath storagePath,
      final Repo repo) {

    if (this.isInvalidGav(gav)) {
      log.error(
          "Maven Gav could not be calculated for repo {} for file {}",
          repo.getName(),
          storagePath.getPath());
      return false;
    } else if (this.isInvalidPomModel(pomModel)) {
      log.error(
          "Maven Pom model could not be calculated for repo {} for file {}",
          repo.getName(),
          storagePath.getPath());
      return false;
    }

    final var groupId = this.getGroupIdFromPomModel(pomModel);

    return !this.isGroupIdMismatch(groupId, gav);
  }

  private boolean isInvalidGav(final @Nullable Gav gav) {

    return gav == null || gav.isHash();
  }

  private boolean isInvalidPomModel(final @Nullable Model model) {

    return model == null;
  }

  private boolean isGroupIdMismatch(final @Nullable String groupId, final Gav gav) {

    return groupId != null && !groupId.equalsIgnoreCase(gav.getGroupId());
  }

  private void createOrUpdateArtifactByPomFile(
      final Repo repo, final Gav gav, final String versionPath, final @Nullable Model pomModel) {

    final var artifact = this.getArtifact(repo.getId(), gav.getArtifactId(), gav.getGroupId());

    if (artifact != null) {
      artifact.setLastUpdatedAt(Instant.now());

      // update artifact properties
      if (pomModel != null) {
        this.setArtifactProperties(pomModel, artifact);
      }

      this.artifactRepository.save(artifact);

      final var artifactVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

      if (artifactVersion == null) {
        this.createArtifactVersionByGav(versionPath, gav, pomModel, artifact); // version uploaded
      } else {
        artifactVersion.setLastUpdatedAt(Instant.now());

        final var versionStoragePath = StoragePath.of(repo.getId(), versionPath);

        final var filesInVersionDir =
            this.storageStrategy.listStorageItems(versionStoragePath).stream()
                .map(StorageItemInfo::getPath)
                .toList();

        // update artifact version properties
        this.setVersionProperties(filesInVersionDir, pomModel, artifactVersion);

        this.versionDeveloperRepository.deleteAllByArtifactVersionId(artifactVersion.getId());
        this.versionLicenseRepository.deleteAllByArtifactVersionId(artifactVersion.getId());

        this.createVersionDevelopersByPomModel(pomModel, artifactVersion);
        this.createVersionLicensesByPomModel(pomModel, artifactVersion);

        this.artifactVersionRepository.save(artifactVersion);

        log.info("Artifact version updated for repo {} ", repo.getId());
      }

    } else {
      final var savedArtifact = this.createArtifactByGav(repo, gav, pomModel);

      this.createArtifactVersionByGav(versionPath, gav, pomModel, savedArtifact);
    }
  }

  private @Nullable Artifact getArtifact(
      final UUID repoId, final String artifactName, final String groupName) {

    final var artifactOptional =
        this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            repoId, groupName, artifactName);

    return artifactOptional.orElse(null);
  }

  private void handleDeployTypeRules(
      final RepoInfo repoInfo, final MutablePair<ArtifactDeployType, ArtifactVersionType> result) {

    final var deployType = result.getKey();
    final var versionType = result.getValue();

    if (deployType != REDEPLOY || repoInfo.isAllowOverride()) {
      this.checkVersionTypeRules(repoInfo, versionType);
      return;
    }

    throw new AccessNotAllowedException("artifactOverrideIsProhibited");
  }

  private void checkByStorage(final RepoInfo repoInfo, final StoragePath storagePath) {
    final var resourceOpt = this.storageStrategy.get(storagePath, repoInfo.getName());

    if (resourceOpt.isPresent()) {
      throw new AccessNotAllowedException("artifactOverrideIsProhibited");
    }
  }

  private @Nullable String getSnapshotArtifactVersionPomFileName(
      final Path artifactBasePath,
      final RepoInfo repoInfo,
      final String versionName,
      final String artifactName)
      throws IOException, XmlPullParserException {

    final var versionPath = artifactBasePath.resolve(versionName + "/" + METADATA_FILENAME);

    final var versionStoragePath = StoragePath.of(repoInfo.getStorageKey(), versionPath.toString());

    final var resource =
        this.storageStrategy
            .get(versionStoragePath, repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    final var versionMetadata = ArtifactUtils.readMetadata(resource.getContentAsByteArray());

    if (versionMetadata == null) {
      throw new IOException();
    }

    final var snapshotVersions = versionMetadata.getVersioning().getSnapshotVersions();

    for (final SnapshotVersion sv : snapshotVersions) {
      if ("pom".equals(sv.getExtension())) {
        return artifactName + "-" + sv.getVersion() + ".pom";
      }
    }

    return null;
  }

  private void setArtifactProperties(final Model pomModel, final Artifact artifact) {

    artifact.setName(pomModel.getName());
    artifact.setPackaging(pomModel.getPackaging());

    if (ArtifactUtils.artifactIsPlugin(pomModel)) {
      artifact.setPrefix(ArtifactUtils.getPrefixFromArtifactId(pomModel.getArtifactId()));
      artifact.setPlugin(true);
    }
  }

  private void setVersionProperties(
      final List<String> fileNamesInVersionDir,
      final @Nullable Model pomModel,
      final ArtifactVersion artifactVersion) {

    var hasSources = false;
    var hasDocuments = false;

    for (final var fileName : fileNamesInVersionDir) {
      if (!hasSources) {
        hasSources = ArtifactUtils.containsIgnoreCase(fileName, SOURCES_SUFFIX);
      }

      if (!hasDocuments) {
        hasDocuments = ArtifactUtils.containsIgnoreCase(fileName, JAVADOC_SUFFIX);
      }
    }

    artifactVersion.setHasSources(hasSources);
    artifactVersion.setHasDocuments(hasDocuments);

    if (pomModel != null) {
      this.setVersionPropertiesByPomModel(pomModel, artifactVersion);
    }
  }

  private void setVersionPropertiesByPomModel(
      final Model pomModel, final ArtifactVersion artifactVersion) {

    artifactVersion.setName(pomModel.getName());
    artifactVersion.setDescription(pomModel.getDescription());
    artifactVersion.setPackaging(pomModel.getPackaging());
    artifactVersion.setUrl(pomModel.getUrl());
    artifactVersion.setOrganization(
        pomModel.getOrganization() != null ? pomModel.getOrganization().getName() : "");
    artifactVersion.setSourceCodeUrl(pomModel.getScm() != null ? pomModel.getScm().getUrl() : "");
    artifactVersion.setHasModules(
        pomModel.getModules() != null && !pomModel.getModules().isEmpty());

    if (ArtifactUtils.artifactIsPlugin(pomModel)) {
      artifactVersion.setPrefix(ArtifactUtils.getPrefixFromArtifactId(pomModel.getArtifactId()));
    }

    if (pomModel.getParent() != null) {
      artifactVersion.setParentArtifactGroup(pomModel.getParent().getGroupId());
      artifactVersion.setParentArtifactName(pomModel.getParent().getArtifactId());
      artifactVersion.setParentArtifactVersion(pomModel.getParent().getVersion());
    }
  }
}
