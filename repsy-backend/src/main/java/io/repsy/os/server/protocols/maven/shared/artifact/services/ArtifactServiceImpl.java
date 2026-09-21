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
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final String ARTIFACT_UNIQUE_CONSTRAINT =
      "ux_maven_artifact__repo_id_group_artifact";
  private static final String ARTIFACT_VERSION_UNIQUE_CONSTRAINT =
      "ux_maven_artifact_version__artifact_id_version_name";

  private final RepoRepository repoRepository;
  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final VersionDeveloperRepository versionDeveloperRepository;
  private final VersionLicenseRepository versionLicenseRepository;
  private final ArtifactConverter artifactConverter;
  private final PGPVerifierService pgpVerifierService;
  private final KeyStoreService keyStoreService;
  private final ArtifactUpsertHelper artifactUpsertHelper;
  private final ArtifactVersionWriteService artifactVersionWriteService;

  @Qualifier("osStorageStrategyMaven")
  private final StorageStrategy storageStrategy;

  /**
   * Classifies an upload of a file that sits in the Maven layout, {@code
   * <group>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<extension>}. A path that
   * is not a Maven 2 artifact path is refused here, before any query and before anything is stored,
   * because every rule (override, releases and snapshots, the artifact rows, the scanner) is keyed
   * on the GAV and a file without one would bypass them all. Sonatype Nexus refuses the same paths
   * with {@code 400} under its strict layout policy. A checksum of any path is not judged and comes
   * back without a type. A file in a {@code SNAPSHOT} directory must also carry that directory's
   * artifactId and base version, literal or timestamped (RPS-1184).
   *
   * @throws BadRequestException {@code invalidArtifactPath} if the path does not parse to a GAV
   */
  @Override
  public MutablePair<@Nullable ArtifactDeployType, @Nullable ArtifactVersionType>
      getDeployAndVersionType(
          final BaseRepoInfo<UUID> baseRepoInfo, final StoragePath storagePath) {

    final var fileName = storagePath.getRelativePath().getFileName();

    if (ArtifactUtils.isChecksumFile(fileName)) {
      return new MutablePair<>(null, null);
    }

    final var repoInfo = (RepoInfo) baseRepoInfo;
    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav == null) {
      log.info(
          "Refusing a Maven upload outside the artifact layout for repo {}: {}",
          repoInfo.getName(),
          storagePath.getRelativePath().getPath());
      throw new BadRequestException("invalidArtifactPath");
    }

    return new MutablePair<>(
        this.getDeployTypeByGav(repoInfo, gav), gav.isSnapshot() ? SNAPSHOT : RELEASE);
  }

  /**
   * Classifies a {@code maven-metadata.xml} upload. Only the version-level file (the {@code
   * g/a/<baseVersion>/} file of a snapshot deploy) is judged, by its own {@code <version>}, exactly
   * like the artifact files of that directory. The artifact-level and group-level files index
   * versions of both kinds, and the files of the version they describe were judged by their own
   * GAV, so no version-type rule applies to them. No deploy type is returned for metadata: {@code
   * checkDeploymentRules} does not read it.
   */
  @Override
  public MutablePair<@Nullable ArtifactDeployType, @Nullable ArtifactVersionType>
      getDeployAndVersionTypesByMetadataTypeFiles(
          final BaseRepoInfo<UUID> baseRepoInfo, final byte[] content, final String fileName)
          throws IOException, XmlPullParserException {

    if (ArtifactUtils.isChecksumFile(fileName)) {
      return new MutablePair<>(null, null);
    }

    // readMetadata throws for malformed content, it never returns null.
    final var metadata = Objects.requireNonNull(ArtifactUtils.readMetadata(content));

    if (ArtifactUtils.isVersionLevelMetadata(metadata)) {
      return new MutablePair<>(
          null, ArtifactUtils.isSnapshot(metadata.getVersion()) ? SNAPSHOT : RELEASE);
    }

    // Artifact-level and group-level metadata index versions of both kinds. The files of the
    // version they describe were judged by their own GAV, so no rule applies to them.
    return new MutablePair<>(null, ArtifactUtils.isPluginMetadata(metadata) ? PLUGIN : null);
  }

  /**
   * Refuses an upload that the repo settings do not allow. The version-type rule ({@code releases}
   * and {@code snapshots}) applies to new versions and to redeploys alike (RPS-1174), so switching
   * a kind off also stops overwriting the versions of that kind that already exist. It applies to
   * version-level metadata by its {@code <version>}; other metadata carries no version type and is
   * not judged, and override never applies to metadata. The override rule is checked first for real
   * artifact files, so {@code artifactOverrideIsProhibited} keeps precedence when both rules would
   * refuse the upload. The version-type rule does not depend on the path parsing to a GAV.
   */
  @Override
  public void checkDeploymentRules(
      final BaseRepoInfo<UUID> baseRepoInfo,
      final MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair,
      final StoragePath storagePath) {

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null) {
      this.checkAllowOverride(baseRepoInfo, gav, storagePath);
    }

    this.checkVersionTypeRules(baseRepoInfo, artifactPair.getValue());
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

    // Cannot create artifact for signed files. The signature itself was verified before it was
    // stored (see verifySignature), so here it only marks the version signed.
    if (ArtifactUtils.isPomSignature(storagePath)) {
      this.processSignedFileProcess(storagePath, repo);
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

    return this.artifactConverter.toArtifactVersionInfo(artifact, version);
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

  public Page<io.repsy.os.generated.model.ArtifactVersionListItem> getArtifactVersions(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final Pageable pageable) {

    return this.artifactVersionRepository
        .findAllByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName, pageable)
        .map(this.artifactConverter::toArtifactVersionListItemDto);
  }

  public Page<io.repsy.os.generated.model.ArtifactVersionListItem>
      getArtifactVersionsContainsVersion(
          final UUID repoId,
          final String groupName,
          final String artifactName,
          final String version,
          final Pageable pageable) {

    return this.artifactVersionRepository
        .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
            repoId, groupName, artifactName, version, pageable)
        .map(this.artifactConverter::toArtifactVersionListItemDto);
  }

  public Page<io.repsy.os.generated.model.ArtifactListItem> getArtifactsContainsGroupName(
      final UUID repoId, final String groupName, final Pageable pageable) {

    return this.artifactRepository
        .findAllByRepoIdAndContainsGroupName(repoId, groupName, pageable)
        .map(this.artifactConverter::toArtifactListItemDto);
  }

  public Page<io.repsy.os.generated.model.ArtifactListItem> getArtifactsContainsArtifactName(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final Pageable pageable) {

    return this.artifactRepository
        .findAllByRepoIdContainsArtifactName(repoId, groupName, artifactName, pageable)
        .map(this.artifactConverter::toArtifactListItemDto);
  }

  public List<Artifact> getArtifacts(final UUID repoId, final String groupName) {

    return this.artifactRepository.findAllByRepoIdAndGroupName(repoId, groupName);
  }

  public List<String> getArtifactVersionNames(
      final UUID repoId, final String groupName, final String artifactName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    return this.artifactVersionRepository.findByArtifactId(artifact.getId()).stream()
        .map(ArtifactVersion::getVersionName)
        .toList();
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

  private void checkAllowOverride(
      final BaseRepoInfo<UUID> repoInfo, final Gav gav, final StoragePath storagePath) {

    if (repoInfo.isAllowOverride()) {
      return;
    }

    final var fileName = gav.getName();

    if (fileName == null) {
      return;
    }

    this.checkForStorage(repoInfo, storagePath);

    if (fileName.endsWith(POM_SUFFIX)) {
      this.checkForDB(repoInfo.getId(), gav);
    }
  }

  private void checkForDB(final UUID repoId, final Gav gav) {

    final var artifactName = gav.getArtifactId();
    final var groupName = gav.getGroupId();
    final var version = gav.getVersion();

    if (this.artifactRepository
        .existsByRepoIdAndArtifactNameAndGroupNameAndArtifactVersionsVersionName(
            repoId, artifactName, groupName, version)) {
      throw new AccessNotAllowedException("artifactOverrideIsProhibited");
    }
  }

  private void checkForStorage(final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    final var storageFileName = storagePath.getRelativePath().getFileName();

    if (storageFileName.contains(METADATA_FILENAME)) {
      return;
    }

    final var resourceOpt = this.storageStrategy.get(storagePath, repoInfo.getName());

    if (resourceOpt.isPresent()) {
      throw new AccessNotAllowedException("artifactOverrideIsProhibited");
    }
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

    try {
      this.artifactUpsertHelper.insertArtifactVersion(version, pomModel, artifact);
    } catch (final DataIntegrityViolationException e) {
      this.handleArtifactVersionInsertConflict(
          repo, artifact, gav, versionPath, pomModel, version, e);
    }
  }

  private void handleArtifactVersionInsertConflict(
      final Repo repo,
      final Artifact artifact,
      final Gav gav,
      final String versionPath,
      final @Nullable Model pomModel,
      final ArtifactVersion version,
      final DataIntegrityViolationException e) {

    if (!ConstraintViolations.violatesConstraint(e, ARTIFACT_VERSION_UNIQUE_CONSTRAINT)) {
      throw e;
    }

    log.warn(
        "Concurrent maven artifact version insert for {}:{} version {}, updating existing row"
            + " instead: {}",
        gav.getGroupId(),
        gav.getArtifactId(),
        version.getVersionName(),
        e.getMessage());

    final var existingVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

    if (existingVersion == null) {
      throw e;
    }

    this.updateArtifactVersion(repo, existingVersion, versionPath, pomModel);
  }

  private void processSignedFileProcess(final StoragePath storagePath, final Repo repo) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(storagePath);

    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException("itemNotFound");
    }

    this.markArtifactSigned(repo, gav);
  }

  private void markArtifactSigned(final Repo repo, final Gav gav) {

    final var artifactVersion = this.findArtifactVersion(repo.getId(), gav);

    // verifySignature refuses a signature without a registered version before it is stored, so
    // this is a defensive check for a version that vanished in between.
    if (artifactVersion == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    artifactVersion.setSigned(true);

    this.artifactVersionRepository.save(artifactVersion);
  }

  /**
   * The registered version a file of {@code gav} belongs to (a snapshot file belongs to its base
   * version), or {@code null} when the artifact or the version is not registered.
   */
  private @Nullable ArtifactVersion findArtifactVersion(final UUID repoId, final Gav gav) {

    final var artifact = this.getArtifact(repoId, gav.getArtifactId(), gav.getGroupId());

    if (artifact == null) {
      return null;
    }

    return this.getArtifactVersionByGav(artifact.getId(), gav);
  }

  /**
   * Verifies the signature of a stored file before the signature itself is stored, so a refused
   * signature leaves no trace: nothing is written and no row or file is deleted (RPS-1186). It used
   * to run after the signature was stored, and the facade then rolled the whole version back.
   *
   * @throws ItemNotFoundException {@code itemNotFound} when the signed file is not stored
   * @throws ItemNotFoundException {@code artifactVersionNotFound} when the POM is stored but its
   *     version is not registered (RPS-1191)
   */
  @Override
  public void verifySignature(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath signedStoragePath,
      final Resource signature) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(signedStoragePath);

    final var nonSignedFileResource =
        this.storageStrategy
            .get(nonSignedStoragePath, repoInfo.getName())
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    // A POM is stored without a registered version when its declared groupId is not its path's
    // (checkExtractedInfos skips it). A signature could then not be recorded, so it is refused
    // here, before the key lookup and before anything is stored (RPS-1191).
    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (gav == null || this.findArtifactVersion(repoInfo.getStorageKey(), gav) == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    final var customKeys = this.keyStoreService.findHostsByRepoId(repoInfo.getStorageKey());

    this.pgpVerifierService.verify(nonSignedFileResource, signature, customKeys);
  }

  private @Nullable ArtifactVersion getArtifactVersionByGav(final UUID artifactId, final Gav gav) {

    final var gavVersion = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    final var artifactVersionOptional =
        this.artifactVersionRepository.findByArtifactIdAndVersionName(artifactId, gavVersion);

    return artifactVersionOptional.orElse(null);
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
      final BaseRepoInfo<UUID> repoInfo, final @Nullable ArtifactVersionType versionType) {

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

    try {
      return this.artifactUpsertHelper.insertArtifact(artifact);
    } catch (final DataIntegrityViolationException e) {
      return this.handleArtifactInsertConflict(repo, gav, pomModel, e);
    }
  }

  private Artifact handleArtifactInsertConflict(
      final Repo repo,
      final Gav gav,
      final @Nullable Model pomModel,
      final DataIntegrityViolationException e) {

    if (!ConstraintViolations.violatesConstraint(e, ARTIFACT_UNIQUE_CONSTRAINT)) {
      throw e;
    }

    log.warn(
        "Concurrent maven artifact insert for {}:{} in repo {}, updating existing row instead: {}",
        gav.getGroupId(),
        gav.getArtifactId(),
        repo.getId(),
        e.getMessage());

    final var existing = this.getArtifact(repo.getId(), gav.getArtifactId(), gav.getGroupId());

    if (existing == null) {
      throw e;
    }

    return this.updateArtifactProperties(existing, pomModel);
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

    final var existingArtifact =
        this.getArtifact(repo.getId(), gav.getArtifactId(), gav.getGroupId());
    final var artifact =
        existingArtifact != null
            ? this.updateArtifactProperties(existingArtifact, pomModel)
            : this.createArtifactByGav(repo, gav, pomModel);

    this.createOrUpdateArtifactVersion(repo, artifact, gav, versionPath, pomModel);
  }

  private Artifact updateArtifactProperties(
      final Artifact artifact, final @Nullable Model pomModel) {

    artifact.setLastUpdatedAt(Instant.now());

    if (pomModel != null) {
      this.setArtifactProperties(pomModel, artifact);
    }

    return this.artifactRepository.save(artifact);
  }

  private void createOrUpdateArtifactVersion(
      final Repo repo,
      final Artifact artifact,
      final Gav gav,
      final String versionPath,
      final @Nullable Model pomModel) {

    final var artifactVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

    if (artifactVersion != null) {
      this.updateArtifactVersion(repo, artifactVersion, versionPath, pomModel);
    } else {
      this.createArtifactVersionByGav(versionPath, gav, pomModel, artifact); // version uploaded
    }
  }

  private void updateArtifactVersion(
      final Repo repo,
      final ArtifactVersion artifactVersion,
      final String versionPath,
      final @Nullable Model pomModel) {

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

    this.artifactVersionWriteService.createVersionDevelopers(pomModel, artifactVersion);
    this.artifactVersionWriteService.createVersionLicenses(pomModel, artifactVersion);

    this.artifactVersionRepository.save(artifactVersion);

    log.info("Artifact version updated for repo {} ", repo.getId());
  }

  private @Nullable Artifact getArtifact(
      final UUID repoId, final String artifactName, final String groupName) {

    final var artifactOptional =
        this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            repoId, groupName, artifactName);

    return artifactOptional.orElse(null);
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
