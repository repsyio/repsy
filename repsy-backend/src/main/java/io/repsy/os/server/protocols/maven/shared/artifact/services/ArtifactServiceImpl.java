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

  private static final String SOURCES_CLASSIFIER = "sources";
  private static final String JAVADOC_CLASSIFIER = "javadoc";
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
   * with {@code 400} under its strict layout policy. A file in a {@code SNAPSHOT} directory must
   * also carry that directory's artifactId and base version, literal or timestamped (RPS-1184).
   *
   * <p>A checksum of any path is judged by the file it belongs to: the GAV calculator strips the
   * checksum suffix, so the layout check, the version type and the override rule of the base file
   * apply to its {@code .sha1}, {@code .md5}, {@code .sha256} and {@code .sha512} alike (RPS-1183).
   * Otherwise a checksum would create the directory of a version whose file is refused.
   *
   * @throws BadRequestException {@code invalidArtifactPath} if the path does not parse to a GAV
   */
  @Override
  public ArtifactVersionType getVersionType(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav == null) {
      log.info(
          "Refusing a Maven upload outside the artifact layout for repo {}: {}",
          repoInfo.getName(),
          storagePath.getRelativePath().getPath());
      throw new BadRequestException("invalidArtifactPath");
    }

    return gav.isSnapshot() ? SNAPSHOT : RELEASE;
  }

  /**
   * Classifies a {@code maven-metadata.xml} upload. Only the version-level file (the {@code
   * g/a/<baseVersion>/} file of a snapshot deploy) is judged, by its own {@code <version>}, exactly
   * like the artifact files of that directory. The artifact-level and group-level files index
   * versions of both kinds, and the files of the version they describe were judged by their own
   * GAV, so no version-type rule applies to them.
   *
   * <p>A metadata checksum or signature ({@code .asc}) holds a hash or armored text, not XML, so it
   * is never parsed and carries no {@code <version>}. Its version-level file is recognised by its
   * directory instead ({@code g/a/<X-SNAPSHOT>/}), the only level a real client writes at version
   * level, and is judged as a snapshot. A checksum or signature at any other level stays unjudged
   * (RPS-1183, RPS-1185).
   */
  @Override
  public @Nullable ArtifactVersionType getVersionTypeByMetadataTypeFiles(
      final BaseRepoInfo<UUID> repoInfo, final byte[] content, final StoragePath storagePath) {

    final var relativePath = storagePath.getRelativePath();
    final var fileName = relativePath.getFileName();

    if (holdsNoXml(fileName)) {
      return ArtifactUtils.isSnapshotVersionDirectoryFile(relativePath.getPath()) ? SNAPSHOT : null;
    }

    final var metadata = ArtifactUtils.readMetadata(content);

    if (ArtifactUtils.isVersionLevelMetadata(metadata)) {
      return ArtifactUtils.isSnapshot(metadata.getVersion()) ? SNAPSHOT : RELEASE;
    }

    // Artifact-level and group-level metadata index versions of both kinds. The files of the
    // version they describe were judged by their own GAV, so no rule applies to them.
    return ArtifactUtils.isPluginMetadata(metadata) ? PLUGIN : null;
  }

  /** A metadata checksum holds a hash and a metadata signature armored text: neither is XML. */
  private static boolean holdsNoXml(final String fileName) {

    return ArtifactUtils.isChecksumFile(fileName) || ArtifactUtils.isMetadataSignature(fileName);
  }

  /**
   * Refuses an upload that the repo settings do not allow. The version-type rule ({@code releases}
   * and {@code snapshots}) applies to new versions and to redeploys alike (RPS-1174), so switching
   * a kind off also stops overwriting the versions of that kind that already exist. It applies to
   * version-level metadata by its {@code <version>}; other metadata carries no version type and is
   * not judged, and override never applies to metadata. Both rules apply to a checksum as they do
   * to the file it belongs to (RPS-1183); the override rule looks for the checksum file itself, so
   * the first checksum of a stored file is not an override and a second upload of it is. The
   * override rule is checked first for real artifact files, so {@code artifactOverrideIsProhibited}
   * keeps precedence when both rules would refuse the upload. The version-type rule does not depend
   * on the path parsing to a GAV.
   */
  @Override
  public void checkDeploymentRules(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable ArtifactVersionType versionType,
      final StoragePath storagePath) {

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null) {
      this.checkAllowOverride(repoInfo, gav, storagePath);
    }

    this.checkVersionTypeRules(repoInfo, versionType);
  }

  /**
   * Registers a stored POM, or marks a version signed for a stored POM signature. Neither is
   * decided from the whole path any more: a file is told to be a POM (or the signature of one) by
   * its file name's {@code .pom} (or {@code .pom.asc}) suffix alone, the same rule {@link
   * ArtifactUtils#isPomToParse} and {@link ArtifactUtils#isPomSignature} apply before the file is
   * stored. Before, an artifactId or directory that merely contained {@code .pom} (for example
   * {@code bar.pom.utils}) made every one of its files, checksums and signatures look like a POM or
   * a POM signature, so a jar answered {@code malformedPomFile} and a stored {@code
   * maven-metadata.xml} failed the same way right after being written (RPS-1196).
   */
  @Override
  @Transactional
  public void createOrUpdateArtifact(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final Resource resource) {

    // Cannot create artifact for signed files. The signature itself was verified before it was
    // stored (see verifySignature), so here it only marks the version signed. It is looked up by
    // the repo's storage key, so it does not need the repo row.
    if (ArtifactUtils.isPomSignature(storagePath)) {
      this.processSignedFileProcess(storagePath, repoInfo.getStorageKey());
      return;
    }

    // A jar, a classifier file, a checksum or a metadata file registers nothing. Nothing below is
    // loaded for them, so a normal `mvn deploy` does not pay a repo query per file (RPS-1179).
    if (!ArtifactUtils.isPomToParse(storagePath)) {
      return;
    }

    final var repo =
        this.repoRepository
            .findByNameAndType(repoInfo.getName(), RepoType.MAVEN)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var fullPath = storagePath.getPath().replace("\\", "/");
    final var versionPath =
        fullPath.substring(fullPath.indexOf("/") + 1, fullPath.lastIndexOf("/"));

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

  /**
   * Confirms the artifact and the version exist, without mutating anything. Called before any
   * storage or DB deletion runs so a version name that does not exist fails with a 404 instead of
   * being reached only after {@code hasOnlyOneVersion} has already routed the request into
   * cascading deletes (RPS-1190).
   *
   * @throws ItemNotFoundException {@code artifactNotFound} or {@code artifactVersionNotFound}
   */
  public void requireArtifactVersion(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final String versionName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    if (this.artifactVersionRepository
        .findByArtifactIdAndVersionName(artifact.getId(), versionName)
        .isEmpty()) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }
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

    // A file name is only looked at by its own name, never a substring of it: an artifactId that
    // happens to contain the literal "maven-metadata.xml" must not skip this check (RPS-1177).
    if (ArtifactUtils.isMetadataFamilyFile(storageFileName)) {
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

    final var storagePath = StoragePath.of(artifact.getRepo().getId(), versionPath);

    final var version = new ArtifactVersion();

    version.setArtifact(artifact);
    version.setCreatedAt(Instant.now());
    version.setLastUpdatedAt(Instant.now());
    version.setType(gav.isSnapshot() ? SNAPSHOT : RELEASE);
    version.setVersionName(gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion());

    this.setVersionProperties(
        versionPath, this.storageStrategy.listStorageItems(storagePath), pomModel, version);

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

  private void processSignedFileProcess(final StoragePath storagePath, final UUID repoId) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(storagePath);

    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException("itemNotFound");
    }

    this.markArtifactSigned(repoId, gav);
  }

  private void markArtifactSigned(final UUID repoId, final Gav gav) {

    final var artifactVersion = this.findArtifactVersion(repoId, gav);

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

    // A POM stored before RPS-1193, or whose rows are gone, can lack a registered version (a POM of
    // another group was stored but skipped by checkExtractedInfos). A signature could then not be
    // recorded, so it is refused here, before the key lookup and before anything is stored
    // (RPS-1191).
    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    if (gav == null || this.findArtifactVersion(repoInfo.getStorageKey(), gav) == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    final var sources = this.keyStoreService.findPublicKeySources(repoInfo.getStorageKey());

    this.pgpVerifierService.verify(nonSignedFileResource, signature, sources);
  }

  private @Nullable ArtifactVersion getArtifactVersionByGav(final UUID artifactId, final Gav gav) {

    final var gavVersion = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    final var artifactVersionOptional =
        this.artifactVersionRepository.findByArtifactIdAndVersionName(artifactId, gavVersion);

    return artifactVersionOptional.orElse(null);
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

    // The upload refuses such a POM before it is stored (RPS-1193). This stays as a logged skip for
    // a POM stored before that, or reached by another path: it must still not be registered.
    final var declared = ArtifactUtils.declaredGroupId(pomModel);

    if (declared != null && !declared.equals(gav.getGroupId())) {
      log.warn(
          "Stored POM {} declares groupId {} under group {}, not registered (refused before the"
              + " store since RPS-1193)",
          storagePath.getPath(),
          declared,
          gav.getGroupId());
      return false;
    }

    return true;
  }

  private boolean isInvalidGav(final @Nullable Gav gav) {

    return gav == null || gav.isHash();
  }

  private boolean isInvalidPomModel(final @Nullable Model model) {

    return model == null;
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

    // update artifact version properties
    this.setVersionProperties(
        versionPath,
        this.storageStrategy.listStorageItems(versionStoragePath),
        pomModel,
        artifactVersion);

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

  /**
   * Sets the flags that depend on the files of the version directory and, when a POM was parsed,
   * the ones that depend on it. A version has sources (or documents) when a file of the directory
   * is the {@code jar} whose own classifier is exactly {@code sources} (or {@code javadoc}). It
   * used to be any stored path containing {@code -sources} (or {@code -javadoc}), and the paths are
   * the physical ones, so an artifactId such as {@code foo-sources}, a checksum or a signature of a
   * sources jar, or a storage directory of that name flagged every version (RPS-1198).
   *
   * <p>Only the files directly in the version directory count. The flags are only recomputed when a
   * POM of the version is stored, so a sources or javadoc jar uploaded after the POM is not
   * reflected until the POM is stored again.
   *
   * @param versionPath the version directory inside the repo, {@code
   *     <group>/<artifactId>/<version>}
   */
  private void setVersionProperties(
      final String versionPath,
      final List<StorageItemInfo> itemsInVersionDir,
      final @Nullable Model pomModel,
      final ArtifactVersion artifactVersion) {

    var hasSources = false;
    var hasDocuments = false;

    for (final var relativePath : this.filesOfVersionDir(versionPath, itemsInVersionDir)) {
      hasSources = hasSources || ArtifactUtils.isClassifierJar(relativePath, SOURCES_CLASSIFIER);
      hasDocuments =
          hasDocuments || ArtifactUtils.isClassifierJar(relativePath, JAVADOC_CLASSIFIER);
    }

    artifactVersion.setHasSources(hasSources);
    artifactVersion.setHasDocuments(hasDocuments);

    if (pomModel != null) {
      this.setVersionPropertiesByPomModel(pomModel, artifactVersion);
    }
  }

  /**
   * The repo-relative paths of the files that sit directly in the version directory. Directories
   * are skipped, and so are the files of nested directories, which {@code Files.walk} also lists
   * but which are not files of this version.
   */
  private List<String> filesOfVersionDir(
      final String versionPath, final List<StorageItemInfo> itemsInVersionDir) {

    return itemsInVersionDir.stream()
        .filter(item -> !item.isDirectory())
        .filter(
            item ->
                item.getPath()
                    .replace("\\", "/")
                    .endsWith("/" + versionPath + "/" + item.getName()))
        .map(item -> versionPath + "/" + item.getName())
        .toList();
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
