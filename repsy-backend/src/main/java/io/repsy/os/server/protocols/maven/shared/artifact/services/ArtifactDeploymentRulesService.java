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
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.utils.MavenFileNameUtils;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.MavenMetadataUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.SignatureFileUtils;
import io.repsy.protocols.maven.shared.utils.SnapshotNameUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The judgement of a Maven upload before anything is stored (RPS-2167, split out of {@link
 * ArtifactDeploymentService}): the layout and version type of a path, the version type of a
 * metadata file and the repo settings (releases, snapshots, override). It has no transaction of its
 * own: {@link ArtifactDeploymentService} calls it under its read only one, as it ran before.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
class ArtifactDeploymentRulesService {

  private static final String POM_SUFFIX = ".pom";

  private final ArtifactRepository artifactRepository;
  private final StorageStrategyRegistry storageStrategyRegistry;

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
  ArtifactVersionType getVersionType(
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
  @Nullable ArtifactVersionType getVersionTypeByMetadataTypeFiles(
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
  void checkDeploymentRules(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable ArtifactVersionType versionType,
      final StoragePath storagePath) {

    final var gav = MavenGavUtils.getGavByFile(storagePath);

    if (gav != null) {
      this.checkAllowOverride(repoInfo, gav, storagePath);
    }

    this.checkVersionTypeRules(repoInfo, versionType);
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

  private StorageStrategy mavenStorage() {
    return this.storageStrategyRegistry.get(RepoType.MAVEN);
  }
}
