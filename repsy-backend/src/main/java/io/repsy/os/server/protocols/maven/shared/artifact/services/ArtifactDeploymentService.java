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
import io.repsy.core.web_error.ConstraintViolations;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.PluginPrefixChange;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.artifact.services.contracts.AbstractArtifactService;
import io.repsy.protocols.maven.shared.utils.MavenFileNameUtils;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.MavenMetadataUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.PomModelUtils;
import io.repsy.protocols.maven.shared.utils.SignatureFileUtils;
import io.repsy.protocols.maven.shared.utils.SnapshotNameUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deploy side of the Maven artifacts (RPS-2064): the judgement of an upload (layout, version
 * type, override), the registration of a stored POM, the artifact and version rows and their
 * deletes. It is the bean the shared protocol module sees as its {@code ArtifactService} contract;
 * the signature, plugin and registered-version operations of that contract delegate to {@link
 * ArtifactSignatureService}, {@link MavenPluginMetadataService} and {@link ArtifactQueryService}.
 */
@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ArtifactDeploymentService extends AbstractArtifactService<UUID> {

  private static final String SOURCES_CLASSIFIER = "sources";
  private static final String JAVADOC_CLASSIFIER = "javadoc";
  private static final String POM_SUFFIX = ".pom";
  private static final String ARTIFACT_UNIQUE_CONSTRAINT =
      "ux_maven_artifact__repo_id_group_artifact";
  private static final String ARTIFACT_VERSION_UNIQUE_CONSTRAINT =
      "ux_maven_artifact_version__artifact_id_version_name";

  private final RepoTxService repoTxService;
  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final VersionDeveloperRepository versionDeveloperRepository;
  private final VersionLicenseRepository versionLicenseRepository;
  private final ArtifactUpsertHelper artifactUpsertHelper;
  private final ArtifactVersionWriteService artifactVersionWriteService;
  private final PendingSignatureService pendingSignatureService;
  private final PendingSignatureRepository pendingSignatureRepository;

  private final ArtifactQueryService artifactQueryService;
  private final ArtifactSignatureService artifactSignatureService;
  private final MavenPluginMetadataService mavenPluginMetadataService;

  private final StorageStrategyRegistry storageStrategyRegistry;

  /** One indexed query, see {@link ArtifactQueryService#getRegisteredVersions}. */
  @Override
  public List<RegisteredVersion> getRegisteredVersions(
      final BaseRepoInfo<UUID> repoInfo, final String groupId, final String artifactId) {

    return this.artifactQueryService.getRegisteredVersions(repoInfo, groupId, artifactId);
  }

  /** One indexed query, see {@link MavenPluginMetadataService#getRegisteredPlugins}. */
  @Override
  public List<RegisteredPlugin> getRegisteredPlugins(
      final BaseRepoInfo<UUID> repoInfo, final String groupId) {

    return this.mavenPluginMetadataService.getRegisteredPlugins(repoInfo, groupId);
  }

  @Override
  public StoragePath getNonSignedStoragePath(final StoragePath signedStoragePath) {

    return this.artifactSignatureService.getNonSignedStoragePath(signedStoragePath);
  }

  /** See {@link ArtifactSignatureService#verifySignature}. */
  @Override
  public SignatureOutcome verifySignature(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath signedStoragePath,
      final Resource signature) {

    return this.artifactSignatureService.verifySignature(repoInfo, signedStoragePath, signature);
  }

  /** See {@link MavenPluginMetadataService#refreshPluginPrefixFromJar}. */
  @Override
  @Transactional
  public @Nullable PluginPrefixChange refreshPluginPrefixFromJar(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath jarPath, final Resource jar) {

    return this.mavenPluginMetadataService.refreshPluginPrefixFromJar(repoInfo, jarPath, jar);
  }

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
   * <p>A groupId, artifactId or version longer than the columns it is registered in is refused the
   * same way, with {@code groupIdTooLong}, {@code artifactIdTooLong} or {@code mavenVersionTooLong}
   * (RPS-1138), instead of failing the row insert after the file was stored. An artifactId and
   * version that each individually fit those limits can still combine, once the storage layer
   * builds the file name from them, into something no POSIX file system accepts as a single path
   * component; that combination is refused too, with {@code mavenFileNameTooLong}, instead of
   * surfacing the file system's own {@code FileSystemException} as a raw 500 (RPS-1732).
   *
   * @throws BadRequestException {@code invalidArtifactPath} if the path does not parse to a GAV
   */
  @Override
  public ArtifactVersionType getVersionType(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    final var gav = MavenGavUtils.getGavByFile(storagePath);

    if (gav == null) {
      log.info(
          "Refusing a Maven upload outside the artifact layout for repo {}: {}",
          repoInfo.getName(),
          storagePath.getRelativePath().getPath());
      throw new BadRequestException(ProtocolErrorCodes.INVALID_ARTIFACT_PATH);
    }

    MavenPublishLimits.checkCoordinates(gav);
    MavenPublishLimits.checkFileNameLength(storagePath);

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
      return MavenGavUtils.isSnapshotVersionDirectoryFile(relativePath.getPath()) ? SNAPSHOT : null;
    }

    final var metadata = MavenMetadataUtils.readMetadata(content);

    if (MavenMetadataUtils.isVersionLevelMetadata(metadata)) {
      return SnapshotNameUtils.isSnapshot(metadata.getVersion()) ? SNAPSHOT : RELEASE;
    }

    // Artifact-level and group-level metadata index versions of both kinds. The files of the
    // version they describe were judged by their own GAV, so no rule applies to them.
    return MavenMetadataUtils.isPluginMetadata(metadata) ? PLUGIN : null;
  }

  /** A metadata checksum holds a hash and a metadata signature armored text: neither is XML. */
  private static boolean holdsNoXml(final String fileName) {

    return MavenFileNameUtils.isChecksumFile(fileName)
        || SignatureFileUtils.isMetadataSignature(fileName);
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
   *
   * <p>The override rule leaves a non-unique snapshot alone (RPS-1328): sbt and Ivy deploy {@code
   * <artifactId>-<version>-SNAPSHOT.pom/.jar} under that literal name every time, so refusing the
   * second deploy made the setting depend on the client. A file of an existing timestamped build
   * and of a release is still refused. {@code snapshots: false} still refuses a literal snapshot.
   */
  @Override
  public void checkDeploymentRules(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable ArtifactVersionType versionType,
      final StoragePath storagePath) {

    final var gav = MavenGavUtils.getGavByFile(storagePath);

    if (gav != null) {
      this.checkAllowOverride(repoInfo, gav, storagePath);
    }

    this.checkVersionTypeRules(repoInfo, versionType);
  }

  /**
   * Registers a stored POM, or records a verified signature and updates whether the version is
   * signed. Neither is decided from the whole path any more: a file is told to be a POM (or the
   * signature of one) by its file name's {@code .pom} (or {@code .pom.asc}) suffix alone, the same
   * rule {@link PomModelUtils#isPomToParse} and {@link SignatureFileUtils#isPomSignature} apply
   * before the file is stored. Before, an artifactId or directory that merely contained {@code
   * .pom} (for example {@code bar.pom.utils}) made every one of its files, checksums and signatures
   * look like a POM or a POM signature, so a jar answered {@code malformedPomFile} and a stored
   * {@code maven-metadata.xml} failed the same way right after being written (RPS-1196).
   *
   * <p>On a repo that verifies every signature (RPS-1188) a stored artifact {@code .asc} is a
   * verified signature too, and a stored signable file (a POM included, once it is registered)
   * loses the verified signature of its previous bytes, so {@code signed} is recomputed after every
   * upload into a registered version. On any other repo nothing of that is done: no query is made
   * for a file that registers nothing.
   */
  @Override
  @Transactional
  public void createOrUpdateArtifact(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final Resource resource) {

    // Cannot create artifact for signed files. The signature itself was verified before it was
    // stored (see verifySignature), so here it only records that and marks the version signed. It
    // is looked up by the repo's storage key, so it does not need the repo row.
    if (SignatureFileUtils.isSignatureToVerify(
        storagePath, repoInfo.isPgpVerifyAllSignaturesEnabled())) {
      this.artifactSignatureService.processSignedFile(repoInfo, storagePath);
      return;
    }

    // A jar, a classifier file, a checksum or a metadata file registers nothing. Nothing below is
    // loaded for them, so a normal `mvn deploy` does not pay a repo query per file (RPS-1179). A
    // repo that verifies every signature does look at a signable one, see refreshSignedForFile.
    if (!PomModelUtils.isPomToParse(storagePath)) {
      this.artifactSignatureService.refreshSignedForFile(repoInfo, storagePath);
      return;
    }

    final var repo = this.repoTxService.requireRepo(repoInfo.getName(), RepoType.MAVEN);

    final var gav = MavenGavUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    final var pomModel = PomModelUtils.readModel(resource);

    if (this.checkExtractedInfos(pomModel, gav, storagePath, repo)) {
      assert gav != null;
      // After the checks above, which read the parent: an over-long descriptive value is dropped
      // rather than failing the row insert (RPS-1138).
      MavenPublishLimits.dropOverLongFields(pomModel);
      this.registerPom(repoInfo, storagePath, repo, gav, pomModel);
    }
  }

  /**
   * Registers the version of a stored POM. On a repo that verifies every signature (RPS-1188) the
   * signatures that arrived before it are dealt with around that: the ones of files that are stored
   * are verified before anything is registered, so one that does not verify fails the POM's upload
   * and leaves no version behind, and are written and recorded once the version exists. The POM's
   * own row is then not forgotten: its signature, if it was parked, was just recorded.
   */
  private void registerPom(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath storagePath,
      final Repo repo,
      final Gav gav,
      final @Nullable Model pomModel) {

    final var versionPath = ArtifactSignatureService.versionPathOf(storagePath);
    final var verifyAll = repoInfo.isPgpVerifyAllSignaturesEnabled();

    if (verifyAll) {
      this.pendingSignatureService.verifyDirectory(repoInfo, versionPath);
    }

    final var prefix =
        this.mavenPluginMetadataService.resolvePluginPrefix(repoInfo, storagePath, gav, pomModel);

    this.createOrUpdateArtifactByPomFile(repo, gav, versionPath, pomModel, prefix);

    final var recorded =
        verifyAll
            && this.pendingSignatureService
                .reconcileDirectory(repoInfo, versionPath)
                .contains(PendingSignatureService.pathOf(storagePath));

    this.artifactSignatureService.updateSignedForFile(repoInfo, storagePath, recorded);
  }

  @Transactional
  public void deleteArtifact(final UUID repoId, final String groupName, final String artifactName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    this.artifactRepository.delete(artifact);
    this.dropPendingSignatures(repoId, groupPath(groupName) + "/" + artifactName + "/");
  }

  @Transactional
  public void deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName) {

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(
                repoInfo.getStorageKey(), groupName, artifactName)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    final var artifactVersion =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(artifact.getId(), versionName)
            .orElseThrow(
                () -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND));

    this.artifactVersionRepository.delete(artifactVersion);
    this.dropPendingSignatures(
        repoInfo.getStorageKey(),
        groupPath(groupName) + "/" + artifactName + "/" + versionName + "/");

    // latest/release follow the rows that are left, as they do on upload. The file's own values are
    // not used: it lists only what a Maven client deployed, and an artifact published by Ivy or sbt
    // has none (RPS-1331).
    this.artifactVersionWriteService.updateReleaseAndLatestVersion(artifact);
  }

  @Transactional
  @SuppressWarnings("all")
  public void deleteGroup(final UUID repoId, final String groupName) {

    final var repo = this.repoTxService.requireRepo(repoId);

    final var artifacts =
        this.artifactRepository.findAllByRepoIdAndGroupName(repo.getId(), groupName);

    for (final Artifact artifact : artifacts) {
      // Do not change this with delete all method.
      this.artifactRepository.delete(artifact);
      this.dropPendingSignatures(
          repoId, groupPath(groupName) + "/" + artifact.getArtifactName() + "/");
    }
  }

  /**
   * Drops the signatures parked for files under {@code directoryPrefix}: a version that is deleted
   * and uploaded again must not meet the signature of the old one (RPS-1188).
   */
  private void dropPendingSignatures(final UUID repoId, final String directoryPrefix) {

    this.pendingSignatureRepository.deleteByRepoIdAndSignedFilePathStartingWith(
        repoId, directoryPrefix);
  }

  private static String groupPath(final String groupName) {

    return groupName.replace('.', '/');
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

    // A non-unique snapshot is redeployed under its literal name by design (sbt, Ivy): it is not
    // an override. An existing timestamped build and a release are still immutable (RPS-1328).
    if (MavenGavUtils.isNonUniqueSnapshotFile(gav)) {
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
      throw new AccessNotAllowedException(ProtocolErrorCodes.ARTIFACT_OVERRIDE_IS_PROHIBITED);
    }
  }

  private void checkForStorage(final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    final var storageFileName = storagePath.getRelativePath().getFileName();

    // A file name is only looked at by its own name, never a substring of it: an artifactId that
    // happens to contain the literal "maven-metadata.xml" must not skip this check (RPS-1177).
    if (MavenFileNameUtils.isMetadataFamilyFile(storageFileName)) {
      return;
    }

    final var resourceOpt = this.mavenStorage().get(storagePath, repoInfo.getName());

    if (resourceOpt.isPresent()) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.ARTIFACT_OVERRIDE_IS_PROHIBITED);
    }
  }

  private void createArtifactVersionByGav(
      final String versionPath,
      final Gav gav,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix,
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
        versionPath,
        this.mavenStorage().listStorageItems(storagePath),
        pomModel,
        pluginPrefix,
        version);

    try {
      this.artifactUpsertHelper.insertArtifactVersion(version, pomModel, artifact);
    } catch (final DataIntegrityViolationException e) {
      this.handleArtifactVersionInsertConflict(
          repo, artifact, gav, versionPath, pomModel, pluginPrefix, version, e);
    }
  }

  private void handleArtifactVersionInsertConflict(
      final Repo repo,
      final Artifact artifact,
      final Gav gav,
      final String versionPath,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix,
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

    this.updateArtifactVersion(repo, existingVersion, versionPath, pomModel, pluginPrefix);
  }

  private void checkVersionTypeRules(
      final BaseRepoInfo<UUID> repoInfo, final @Nullable ArtifactVersionType versionType) {

    if (versionType == null) {
      return;
    }

    if (versionType == RELEASE && Boolean.FALSE.equals(repoInfo.getReleases())) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.RELEASE_VERSIONS_ARE_PROHIBITED);
    } else if (versionType == SNAPSHOT && Boolean.FALSE.equals(repoInfo.getSnapshots())) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.SNAPSHOT_VERSIONS_ARE_PROHIBITED);
    }
  }

  private Artifact createArtifactByGav(
      final Repo repo,
      final Gav gav,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix) {

    final var artifact = new Artifact();

    artifact.setArtifactName(gav.getArtifactId());
    artifact.setGroupName(gav.getGroupId());
    artifact.setCreatedAt(Instant.now());
    artifact.setLastUpdatedAt(Instant.now());
    artifact.setRepo(repo);

    if (pomModel != null) {
      this.setArtifactProperties(pomModel, pluginPrefix, artifact);
    }

    try {
      return this.artifactUpsertHelper.insertArtifact(artifact);
    } catch (final DataIntegrityViolationException e) {
      return this.handleArtifactInsertConflict(repo, gav, pomModel, pluginPrefix, e);
    }
  }

  private Artifact handleArtifactInsertConflict(
      final Repo repo,
      final Gav gav,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix,
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

    return this.updateArtifactProperties(existing, pomModel, pluginPrefix);
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
    final var declared = PomModelUtils.declaredGroupId(pomModel);

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
      final Repo repo,
      final Gav gav,
      final String versionPath,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix) {

    final var existingArtifact =
        this.getArtifact(repo.getId(), gav.getArtifactId(), gav.getGroupId());
    final var artifact =
        existingArtifact != null
            ? this.updateArtifactProperties(existingArtifact, pomModel, pluginPrefix)
            : this.createArtifactByGav(repo, gav, pomModel, pluginPrefix);

    this.createOrUpdateArtifactVersion(repo, artifact, gav, versionPath, pomModel, pluginPrefix);
  }

  private Artifact updateArtifactProperties(
      final Artifact artifact,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix) {

    artifact.setLastUpdatedAt(Instant.now());

    if (pomModel != null) {
      this.setArtifactProperties(pomModel, pluginPrefix, artifact);
    }

    return this.artifactRepository.save(artifact);
  }

  private void createOrUpdateArtifactVersion(
      final Repo repo,
      final Artifact artifact,
      final Gav gav,
      final String versionPath,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix) {

    final var artifactVersion = this.getArtifactVersionByGav(artifact.getId(), gav);

    if (artifactVersion != null) {
      this.updateArtifactVersion(repo, artifactVersion, versionPath, pomModel, pluginPrefix);
    } else {
      this.createArtifactVersionByGav(
          versionPath, gav, pomModel, pluginPrefix, artifact); // version uploaded
    }
  }

  private void updateArtifactVersion(
      final Repo repo,
      final ArtifactVersion artifactVersion,
      final String versionPath,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix) {

    artifactVersion.setLastUpdatedAt(Instant.now());

    final var versionStoragePath = StoragePath.of(repo.getId(), versionPath);

    // update artifact version properties
    this.setVersionProperties(
        versionPath,
        this.mavenStorage().listStorageItems(versionStoragePath),
        pomModel,
        pluginPrefix,
        artifactVersion);

    this.versionDeveloperRepository.deleteAllByArtifactVersionId(artifactVersion.getId());
    this.versionLicenseRepository.deleteAllByArtifactVersionId(artifactVersion.getId());

    this.artifactVersionWriteService.createVersionDevelopers(pomModel, artifactVersion);
    this.artifactVersionWriteService.createVersionLicenses(pomModel, artifactVersion);

    this.artifactVersionRepository.save(artifactVersion);

    log.info("Artifact version updated for repo {} ", repo.getId());
  }

  private void setArtifactProperties(
      final Model pomModel, final @Nullable String pluginPrefix, final Artifact artifact) {

    artifact.setName(pomModel.getName());
    artifact.setPackaging(pomModel.getPackaging());

    if (PomModelUtils.artifactIsPlugin(pomModel)) {
      artifact.setPrefix(pluginPrefix);
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
   * reflected until the POM is stored again. The plugin prefix is not one of them: a plugin jar
   * stored after the POM corrects it when it arrives (RPS-1589).
   *
   * @param versionPath the version directory inside the repo, {@code
   *     <group>/<artifactId>/<version>}
   */
  private void setVersionProperties(
      final String versionPath,
      final List<StorageItemInfo> itemsInVersionDir,
      final @Nullable Model pomModel,
      final @Nullable String pluginPrefix,
      final ArtifactVersion artifactVersion) {

    var hasSources = false;
    var hasDocuments = false;

    for (final var relativePath : this.filesOfVersionDir(versionPath, itemsInVersionDir)) {
      hasSources = hasSources || MavenGavUtils.isClassifierJar(relativePath, SOURCES_CLASSIFIER);
      hasDocuments =
          hasDocuments || MavenGavUtils.isClassifierJar(relativePath, JAVADOC_CLASSIFIER);
    }

    artifactVersion.setHasSources(hasSources);
    artifactVersion.setHasDocuments(hasDocuments);

    if (pomModel != null) {
      this.setVersionPropertiesByPomModel(pomModel, pluginPrefix, artifactVersion);
    }
  }

  /**
   * The repo-relative paths of the files that sit directly in the version directory. Directories
   * are skipped, and so are the files of nested directories, which {@code Files.walk} also lists
   * but which are not files of this version.
   */
  private List<String> filesOfVersionDir(
      final String versionPath, final List<StorageItemInfo> itemsInVersionDir) {

    return SnapshotNameUtils.versionDirFileNames(versionPath, itemsInVersionDir).stream()
        .map(name -> versionPath + "/" + name)
        .toList();
  }

  private void setVersionPropertiesByPomModel(
      final Model pomModel,
      final @Nullable String pluginPrefix,
      final ArtifactVersion artifactVersion) {

    artifactVersion.setName(pomModel.getName());
    artifactVersion.setDescription(pomModel.getDescription());
    artifactVersion.setPackaging(pomModel.getPackaging());
    artifactVersion.setUrl(pomModel.getUrl());
    artifactVersion.setOrganization(
        pomModel.getOrganization() != null ? pomModel.getOrganization().getName() : "");
    artifactVersion.setSourceCodeUrl(pomModel.getScm() != null ? pomModel.getScm().getUrl() : "");
    artifactVersion.setHasModules(
        pomModel.getModules() != null && !pomModel.getModules().isEmpty());

    if (PomModelUtils.artifactIsPlugin(pomModel)) {
      artifactVersion.setPrefix(pluginPrefix);
    }

    if (pomModel.getParent() != null) {
      artifactVersion.setParentArtifactGroup(pomModel.getParent().getGroupId());
      artifactVersion.setParentArtifactName(pomModel.getParent().getArtifactId());
      artifactVersion.setParentArtifactVersion(pomModel.getParent().getVersion());
    }
  }

  private @Nullable ArtifactVersion getArtifactVersionByGav(final UUID artifactId, final Gav gav) {

    return this.artifactQueryService.findVersionByGav(artifactId, gav).orElse(null);
  }

  private @Nullable Artifact getArtifact(
      final UUID repoId, final String artifactName, final String groupName) {

    return this.artifactQueryService.findArtifact(repoId, groupName, artifactName).orElse(null);
  }

  private StorageStrategy mavenStorage() {
    return this.storageStrategyRegistry.get(RepoType.MAVEN);
  }
}
