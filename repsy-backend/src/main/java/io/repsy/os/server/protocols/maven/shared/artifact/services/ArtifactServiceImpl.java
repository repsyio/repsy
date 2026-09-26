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
import io.repsy.os.generated.model.MavenGroupSummary;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.PluginDescriptorReader;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
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
  private static final String SIGNATURE_SUFFIX = ".asc";
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
  private final VersionSignatureService versionSignatureService;
  private final PendingSignatureService pendingSignatureService;
  private final PendingSignatureRepository pendingSignatureRepository;

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
   * <p>A groupId, artifactId or version longer than the columns it is registered in is refused the
   * same way, with {@code groupIdTooLong}, {@code artifactIdTooLong} or {@code mavenVersionTooLong}
   * (RPS-1138), instead of failing the row insert after the file was stored.
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

    MavenPublishLimits.checkCoordinates(gav);

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

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null) {
      this.checkAllowOverride(repoInfo, gav, storagePath);
    }

    this.checkVersionTypeRules(repoInfo, versionType);
  }

  /**
   * Registers a stored POM, or records a verified signature and updates whether the version is
   * signed. Neither is decided from the whole path any more: a file is told to be a POM (or the
   * signature of one) by its file name's {@code .pom} (or {@code .pom.asc}) suffix alone, the same
   * rule {@link ArtifactUtils#isPomToParse} and {@link ArtifactUtils#isPomSignature} apply before
   * the file is stored. Before, an artifactId or directory that merely contained {@code .pom} (for
   * example {@code bar.pom.utils}) made every one of its files, checksums and signatures look like
   * a POM or a POM signature, so a jar answered {@code malformedPomFile} and a stored {@code
   * maven-metadata.xml} failed the same way right after being written (RPS-1196).
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
    if (ArtifactUtils.isSignatureToVerify(
        storagePath, repoInfo.isPgpVerifyAllSignaturesEnabled())) {
      this.processSignedFileProcess(repoInfo, storagePath);
      return;
    }

    // A jar, a classifier file, a checksum or a metadata file registers nothing. Nothing below is
    // loaded for them, so a normal `mvn deploy` does not pay a repo query per file (RPS-1179). A
    // repo that verifies every signature does look at a signable one, see refreshSignedForFile.
    if (!ArtifactUtils.isPomToParse(storagePath)) {
      this.refreshSignedForFile(repoInfo, storagePath);
      return;
    }

    final var repo =
        this.repoRepository
            .findByNameAndType(repoInfo.getName(), RepoType.MAVEN)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var gav = ArtifactUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    final var pomModel = ArtifactUtils.readModel(resource);

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

    final var versionPath = versionPathOf(storagePath);
    final var verifyAll = repoInfo.isPgpVerifyAllSignaturesEnabled();

    if (verifyAll) {
      this.pendingSignatureService.verifyDirectory(repoInfo, versionPath);
    }

    final var prefix = this.resolvePluginPrefix(repoInfo, storagePath, gav, pomModel);

    this.createOrUpdateArtifactByPomFile(repo, gav, versionPath, pomModel, prefix);

    final var recorded =
        verifyAll
            && this.pendingSignatureService
                .reconcileDirectory(repoInfo, versionPath)
                .contains(PendingSignatureService.pathOf(storagePath));

    this.updateSignedForFile(repoInfo, storagePath, recorded);
  }

  /**
   * A signable file was stored into a repo that verifies every signature (RPS-1188). The signature
   * that arrived before it, if any, is checked now and recorded ({@link
   * PendingSignatureService#reconcileFile}); otherwise its bytes are new, so the verified signature
   * of the previous ones is forgotten. Whether the version is signed is recomputed either way. A
   * file of a version that is not registered yet (a jar before its POM) is left to the POM's
   * registration.
   *
   * <p>Known window, accepted (RPS-1335): whether the repo verifies every signature is taken from
   * {@code repoInfo}, which is from the start of the request, and a repo for which it says no
   * returns before any lock or query. A toggle-on that commits while such a file is being uploaded
   * starts its recomputation, and if that has already passed the file's version, the version keeps
   * a {@code signed} that was computed without the new file until the next upload into it or the
   * next toggle. It is bounded (one version per upload that raced the toggle), heals itself, and
   * needs an upload and a toggle within the same moment. Closing it needs the setting read for
   * every signable file of a flag-off repo, which is the query per file that RPS-1179 removed; a
   * plain {@code mvn deploy} uploads many of them.
   */
  private void refreshSignedForFile(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    if (!this.isSignableInVerifyAllRepo(repoInfo, storagePath)) {
      return;
    }

    final var recorded =
        this.pendingSignatureService.reconcileFile(
            repoInfo, PendingSignatureService.pathOf(storagePath));

    this.updateSignedForFile(repoInfo, storagePath, recorded);
  }

  /**
   * From the setting as {@code repoInfo} has it, so a request that began before a toggle is decided
   * by the old value. When that says on, {@link #updateSignedForFile} reads the setting again under
   * the version's lock; when it says off nothing is read, and that is the accepted window of {@link
   * #refreshSignedForFile} (RPS-1335).
   */
  private boolean isSignableInVerifyAllRepo(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    return repoInfo.isPgpVerifyAllSignaturesEnabled()
        && ArtifactUtils.isSignableFile(storagePath.getRelativePath().getFileName());
  }

  /**
   * Forgets the verified signature of a signable file that was stored again, unless it was just
   * recorded for the new bytes, and recomputes whether the version is signed.
   */
  private void updateSignedForFile(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final boolean recorded) {

    if (!this.isSignableInVerifyAllRepo(repoInfo, storagePath)) {
      return;
    }

    final var relativePath = storagePath.getRelativePath();
    final var gav = ArtifactUtils.convertPathToGav(relativePath.getPath());
    final var version =
        gav == null ? null : this.findArtifactVersion(repoInfo.getStorageKey(), gav);

    if (version == null) {
      return;
    }

    // Locked first: a signature request that recorded this file's signature holds the lock until it
    // commits, so the record is either visible from here on or comes after the recomputation below
    // (RPS-1320). The setting is read after the lock and not taken from repoInfo, which is from the
    // start of the request: a toggle that committed since has its recomputation waiting for this
    // lock, and what is written here must be by the setting it will find (RPS-1323).
    final var verifyAll = this.versionSignatureService.lockAndIsVerifyAll(version);

    if (verifyAll && !recorded) {
      this.forgetUnlessStillVerifies(repoInfo, version, storagePath);
    }

    this.versionSignatureService.refreshSigned(
        repoInfo.getStorageKey(), version, versionPathOf(storagePath), verifyAll);
  }

  private void forgetUnlessStillVerifies(
      final BaseRepoInfo<UUID> repoInfo,
      final ArtifactVersion version,
      final StoragePath storagePath) {

    if (!this.stillVerifies(repoInfo, version, PendingSignatureService.pathOf(storagePath))) {
      this.versionSignatureService.forget(version, storagePath.getRelativePath().getFileName());
    }
  }

  /**
   * Whether the verified signature recorded for a file that was just stored is the one of the bytes
   * that are stored now, and not of the ones they replaced.
   *
   * <p>A signature is small and its request often runs whole between the moment the file it signs
   * is stored and the moment that file's own request gets here: it finds the file, verifies against
   * these very bytes and records them. Forgetting that record on the file's behalf would leave the
   * version unsigned for good, as nothing recomputes it afterwards (RPS-1320). So a record that is
   * there is checked once more against the file and the signature that are stored, and is kept if
   * it holds. When the file is new no record exists and nothing is read. When it replaced another
   * (a redeploy) the record is, in general, the one of the old bytes, does not verify and goes; if
   * the same bytes were stored again it holds, and stays.
   */
  private boolean stillVerifies(
      final BaseRepoInfo<UUID> repoInfo, final ArtifactVersion version, final String filePath) {

    final var fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

    if (!this.versionSignatureService.isRecorded(version, fileName)) {
      return false;
    }

    final var repoName = repoInfo.getName();
    final var file =
        this.storageStrategy.get(StoragePath.of(repoInfo.getStorageKey(), filePath), repoName);
    final var signature =
        this.storageStrategy.get(
            StoragePath.of(repoInfo.getStorageKey(), filePath + SIGNATURE_SUFFIX), repoName);

    if (file.isEmpty() || signature.isEmpty()) {
      return false;
    }

    try {
      this.verifyAgainst(repoInfo, file.get(), signature.get());

      return true;
    } catch (final RuntimeException e) {
      // Not verified, or not verifiable now (a key that cannot be found): not vouched for.
      log.debug("The recorded signature of {} no longer verifies: {}", filePath, e.toString());

      return false;
    }
  }

  /** The version directory a file sits in, {@code <group>/<artifactId>/<version>}. */
  private static String versionPathOf(final StoragePath storagePath) {

    final var fullPath = storagePath.getPath().replace("\\", "/");

    return fullPath.substring(fullPath.indexOf("/") + 1, fullPath.lastIndexOf("/"));
  }

  /** One indexed query, see {@link ArtifactVersionRepository#findRegisteredVersions}. */
  @Override
  public List<RegisteredVersion> getRegisteredVersions(
      final BaseRepoInfo<UUID> repoInfo, final String groupId, final String artifactId) {

    return this.artifactVersionRepository.findRegisteredVersions(
        repoInfo.getStorageKey(), groupId, artifactId);
  }

  /** One indexed query, see {@link ArtifactRepository#findRegisteredPlugins}. */
  @Override
  public List<RegisteredPlugin> getRegisteredPlugins(
      final BaseRepoInfo<UUID> repoInfo, final String groupId) {

    return this.artifactRepository.findRegisteredPlugins(repoInfo.getStorageKey(), groupId);
  }

  @Override
  public StoragePath getNonSignedStoragePath(final StoragePath signedStoragePath) {

    final var signaturePath = signedStoragePath.getRelativePath().getPath();

    final var suffixLength = SIGNATURE_SUFFIX.length();

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
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_NOT_FOUND));

    final var artifactVersion =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(artifact.getId(), versionName)
            .orElseThrow(() -> new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND));

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

    final var repo =
        this.repoRepository
            .findById(repoId)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

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

  /**
   * What deleting the group removes: its artifacts and the versions of all of them (RPS-1288).
   *
   * @throws ItemNotFoundException {@code groupNotFound} when the repo has no artifact of the group
   */
  public MavenGroupSummary getGroupSummary(final UUID repoId, final String groupName) {

    final var artifactCount = this.artifactRepository.countByRepoIdAndGroupName(repoId, groupName);

    if (artifactCount == 0) {
      throw new ItemNotFoundException("groupNotFound");
    }

    return MavenGroupSummary.builder()
        .groupName(groupName)
        .artifactCount(artifactCount)
        .versionCount(this.artifactVersionRepository.countByRepoIdAndGroupName(repoId, groupName))
        .build();
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

    // A non-unique snapshot is redeployed under its literal name by design (sbt, Ivy): it is not
    // an override. An existing timestamped build and a release are still immutable (RPS-1328).
    if (ArtifactUtils.isNonUniqueSnapshotFile(gav)) {
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
        this.storageStrategy.listStorageItems(storagePath),
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

  /**
   * Records the verified signature of the file {@code signaturePath} signs and updates {@code
   * signed}: set directly on a repo that only verifies the POM signature, recomputed from all the
   * files of the version on one that verifies every signature (RPS-1188).
   */
  private void processSignedFileProcess(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath signaturePath) {

    final var signedStoragePath = this.getNonSignedStoragePath(signaturePath);

    final var gav = ArtifactUtils.convertPathToGav(signedStoragePath.getRelativePath().getPath());

    if (null == gav) {
      throw new ItemNotFoundException("itemNotFound");
    }

    final var artifactVersion = this.findArtifactVersion(repoInfo.getStorageKey(), gav);

    // verifySignature refuses a signature without a registered version before it is stored, so
    // this is a defensive check for a version that vanished in between.
    if (artifactVersion == null) {
      throw new ItemNotFoundException(ERR_ARTIFACT_VERSION_NOT_FOUND);
    }

    if (repoInfo.isPgpVerifyAllSignaturesEnabled()) {
      // A copy of this signature parked earlier goes, after any check of it that is running.
      this.pendingSignatureService.claim(
          repoInfo.getStorageKey(), PendingSignatureService.pathOf(signedStoragePath));
    }

    // The version's lock is taken before its signature is recorded, and the setting is read after
    // it: repoInfo is from the start of the request, and a toggle may have committed since. The
    // recomputation that toggle started waits for this lock, so it comes after what is written here
    // (RPS-1323). The lock comes after the claim above, as it always did, so the two locks are
    // taken in the same order as by the checks of parked signatures.
    final var verifyAll = this.versionSignatureService.lockAndIsVerifyAll(artifactVersion);

    this.versionSignatureService.recordVerified(
        artifactVersion, signedStoragePath.getRelativePath().getFileName());

    // The rule of the setting it is now: every file has a verified signature, or the POM's has.
    this.versionSignatureService.refreshSigned(
        repoInfo.getStorageKey(), artifactVersion, versionPathOf(signedStoragePath), verifyAll);
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
   * <p>On a repo that verifies every signature (RPS-1188) a signature whose file is not stored, or
   * whose version is not registered, is parked instead of refused: Maven uploads in parallel, so it
   * may overtake either. It is parked in a transaction of its own that commits first, and the file
   * and the version are looked at once more after that, so a file that landed in between is not
   * missed: the file's own upload finds the parked row if it comes later, this request finds the
   * file if it came earlier.
   *
   * @throws ItemNotFoundException {@code itemNotFound} when the signed file is not stored
   * @throws ItemNotFoundException {@code artifactVersionNotFound} when the POM is stored but its
   *     version is not registered (RPS-1191)
   */
  @Override
  public SignatureOutcome verifySignature(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath signedStoragePath,
      final Resource signature) {

    final var nonSignedStoragePath = this.getNonSignedStoragePath(signedStoragePath);
    final var storedFile = this.storageStrategy.get(nonSignedStoragePath, repoInfo.getName());

    if (storedFile.isPresent() && this.isVersionRegistered(repoInfo, nonSignedStoragePath)) {
      this.verifyAgainst(repoInfo, storedFile.get(), signature);

      return SignatureOutcome.VERIFIED;
    }

    if (!repoInfo.isPgpVerifyAllSignaturesEnabled()) {
      // A POM stored before RPS-1193, or whose rows are gone, can lack a registered version (a POM
      // of another group was stored but skipped by checkExtractedInfos). A signature could then
      // not be recorded, so it is refused here, before the key lookup and before anything is
      // stored (RPS-1191).
      throw new ItemNotFoundException(
          storedFile.isPresent() ? ERR_ARTIFACT_VERSION_NOT_FOUND : "itemNotFound");
    }

    return this.parkOrVerify(repoInfo, nonSignedStoragePath, signature);
  }

  private boolean isVersionRegistered(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath nonSignedStoragePath) {

    final var gav =
        ArtifactUtils.convertPathToGav(nonSignedStoragePath.getRelativePath().getPath());

    return gav != null && this.findArtifactVersion(repoInfo.getStorageKey(), gav) != null;
  }

  private void verifyAgainst(
      final BaseRepoInfo<UUID> repoInfo, final Resource storedFile, final Resource signature) {

    final var sources =
        this.keyStoreService.findPublicKeySources(
            repoInfo.getStorageKey(), repoInfo.isPgpKeyServerLookupEnabled());

    this.pgpVerifierService.verify(storedFile, signature, sources);
  }

  /**
   * Parks the signature, then looks for the file and the version once more: if both are there now,
   * the signature is verified after all and the request stores it like any other (its parked copy
   * is dropped when it is recorded, or here when it does not verify).
   */
  private SignatureOutcome parkOrVerify(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath nonSignedStoragePath,
      final Resource signature) {

    final var filePath = PendingSignatureService.pathOf(nonSignedStoragePath);

    this.pendingSignatureService.park(repoInfo.getStorageKey(), filePath, readBytes(signature));

    final var storedFile = this.storageStrategy.get(nonSignedStoragePath, repoInfo.getName());

    if (storedFile.isEmpty() || !this.isVersionRegistered(repoInfo, nonSignedStoragePath)) {
      return SignatureOutcome.PARKED;
    }

    try {
      this.verifyAgainst(repoInfo, storedFile.get(), signature);
    } catch (final RuntimeException e) {
      this.pendingSignatureService.discard(repoInfo.getStorageKey(), filePath);

      throw e;
    }

    return SignatureOutcome.VERIFIED;
  }

  private static byte[] readBytes(final Resource signature) {

    try {
      return signature.getContentAsByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
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
        this.storageStrategy.listStorageItems(versionStoragePath),
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

  private @Nullable Artifact getArtifact(
      final UUID repoId, final String artifactName, final String groupName) {

    final var artifactOptional =
        this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            repoId, groupName, artifactName);

    return artifactOptional.orElse(null);
  }

  /**
   * The POM file name of a snapshot version. The version-level {@code maven-metadata.xml} names it
   * when there is one that lists a {@code pom} (what a Maven client resolves). Without it, or when
   * it is unusable (no {@code <versioning>}, no {@code pom}, or content that cannot be parsed at
   * all, RPS-1421), the newest POM stored in the version directory is used (RPS-1370): sbt and Ivy
   * deploy a snapshot under its literal name and upload no metadata at all. A literal POM counts as
   * older than a timestamped one, see {@link ArtifactUtils#newestSnapshotPomName}. Unparsable
   * metadata is treated like absent metadata here, on this read only: an upload of it still answers
   * 400 {@code malformedMetadataFile}.
   */
  private @Nullable String getSnapshotArtifactVersionPomFileName(
      final Path artifactBasePath,
      final RepoInfo repoInfo,
      final String versionName,
      final String artifactName)
      throws IOException, XmlPullParserException {

    // artifactBasePath is a storage path with a leading slash: only the names of the files in the
    // version directory are ever matched, never their paths.
    final var versionDirPath = artifactBasePath.resolve(versionName);
    final var versionPath =
        StoragePath.of(
            repoInfo.getStorageKey(), versionDirPath.resolve(METADATA_FILENAME).toString());

    final var resourceOpt = this.storageStrategy.get(versionPath, repoInfo.getName());

    final var metadata =
        resourceOpt.isPresent()
            ? readSnapshotMetadataQuietly(resourceOpt.get(), versionPath)
            : null;

    if (metadata != null) {
      final var metadataPomName = pomNameOfSnapshotMetadata(metadata, artifactName);

      if (metadataPomName != null) {
        return metadataPomName;
      }
    }

    final var storedPomName =
        ArtifactUtils.newestSnapshotPomName(
            artifactName,
            versionName,
            this.fileNamesOf(StoragePath.of(repoInfo.getStorageKey(), versionDirPath.toString())));

    if (storedPomName != null) {
      return storedPomName;
    }

    // Metadata that lists no pom keeps answering without one; without any usable metadata (absent
    // or unparsable, RPS-1421) a version with no POM behaves like a release without one (404 when
    // the file is read).
    return metadata != null ? null : artifactName + "-" + versionName + ".pom";
  }

  /**
   * The parsed version-level metadata, or {@code null} when it cannot be parsed: {@link
   * ArtifactUtils#readMetadata} refuses it with the unchecked {@link BadRequestException}, which
   * would answer the panel's version detail with a 400 although the POM is in the directory
   * (RPS-1421).
   */
  private static @Nullable Metadata readSnapshotMetadataQuietly(
      final Resource metadataResource, final StoragePath metadataPath) throws IOException {

    try {
      return ArtifactUtils.readMetadata(metadataResource.getContentAsByteArray());
    } catch (final BadRequestException e) {
      log.warn(
          "Ignoring the unparsable snapshot metadata at {}, the stored POM is used instead: {}",
          metadataPath,
          e.getMessage());
      return null;
    }
  }

  private static @Nullable String pomNameOfSnapshotMetadata(
      final Metadata metadata, final String artifactName) {

    final var versioning = metadata.getVersioning();

    if (versioning == null) {
      return null;
    }

    for (final SnapshotVersion sv : versioning.getSnapshotVersions()) {
      if ("pom".equals(sv.getExtension())) {
        return artifactName + "-" + sv.getVersion() + ".pom";
      }
    }

    return null;
  }

  /** The names of the files directly in a directory, none when the directory does not exist. */
  private List<String> fileNamesOf(final StoragePath directory) {

    try {
      return this.storageStrategy.listDirectoryContents(directory).stream()
          .filter(item -> !item.isDirectory())
          .map(StorageItemInfo::getName)
          .toList();
    } catch (final ItemNotFoundException _) {
      return List.of();
    }
  }

  /**
   * The prefix a plugin's POM registers with, {@code null} when the POM is not a plugin's or the
   * prefix does not fit its column. It is the {@code goalPrefix} of {@code
   * META-INF/maven/plugin.xml} in the plugin's jar when the jar of that exact version is stored
   * already (Gradle's {@code maven-publish}, sbt and Ivy send it before the POM, and a plugin that
   * sets its own {@code goalPrefix} is otherwise not found by it, RPS-1458), and the one derived
   * from the artifactId otherwise. Only a plugin's POM costs the read of one stored file. A jar
   * that arrives after the POM is not looked at: the prefix stays the derived one until the POM is
   * stored again, like {@code hasSources}. Reading never fails the upload, whatever the jar is.
   */
  private @Nullable String resolvePluginPrefix(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath pomPath,
      final Gav gav,
      final @Nullable Model pomModel) {

    if (pomModel == null || !ArtifactUtils.artifactIsPlugin(pomModel)) {
      return null;
    }

    final var pom = pomPath.getRelativePath().getPath();
    final var jarPath = pom.substring(0, pom.length() - ".pom".length()) + ".jar";

    try {
      final var jar =
          this.storageStrategy.get(
              StoragePath.of(repoInfo.getStorageKey(), jarPath), repoInfo.getName());

      if (jar.isPresent()) {
        try (final var in = jar.get().getInputStream()) {
          final var goalPrefix = PluginDescriptorReader.goalPrefix(in, gav.getArtifactId());

          if (goalPrefix != null) {
            return goalPrefix;
          }
        }
      }
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "The goalPrefix of the plugin jar {} could not be read, the derived one is used: {}",
          jarPath,
          e.toString());
    }

    return derivedPluginPrefix(pomModel);
  }

  /** The plugin prefix of the POM's artifactId, or {@code null} if it is longer than its column. */
  private static @Nullable String derivedPluginPrefix(final Model pomModel) {

    return MavenPublishLimits.dropIfTooLong(
        ArtifactUtils.getPrefixFromArtifactId(pomModel.getArtifactId()),
        MavenPublishLimits.MAX_PREFIX_LENGTH);
  }

  private void setArtifactProperties(
      final Model pomModel, final @Nullable String pluginPrefix, final Artifact artifact) {

    artifact.setName(pomModel.getName());
    artifact.setPackaging(pomModel.getPackaging());

    if (ArtifactUtils.artifactIsPlugin(pomModel)) {
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
   * reflected until the POM is stored again. The same goes for the plugin prefix: it is the {@code
   * goalPrefix} of a plugin jar only if the jar was stored before the POM (RPS-1458).
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
      hasSources = hasSources || ArtifactUtils.isClassifierJar(relativePath, SOURCES_CLASSIFIER);
      hasDocuments =
          hasDocuments || ArtifactUtils.isClassifierJar(relativePath, JAVADOC_CLASSIFIER);
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

    return ArtifactUtils.versionDirFileNames(versionPath, itemsInVersionDir).stream()
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

    if (ArtifactUtils.artifactIsPlugin(pomModel)) {
      artifactVersion.setPrefix(pluginPrefix);
    }

    if (pomModel.getParent() != null) {
      artifactVersion.setParentArtifactGroup(pomModel.getParent().getGroupId());
      artifactVersion.setParentArtifactName(pomModel.getParent().getArtifactId());
      artifactVersion.setParentArtifactVersion(pomModel.getParent().getVersion());
    }
  }
}
