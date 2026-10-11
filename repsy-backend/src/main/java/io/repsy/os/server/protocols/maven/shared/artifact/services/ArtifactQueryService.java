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

import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.RELEASE;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.SNAPSHOT;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.web.utils.LikePatterns;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.generated.model.MavenGroupSummary;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactMapper;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import io.repsy.protocols.maven.shared.utils.MavenMetadataUtils;
import io.repsy.protocols.maven.shared.utils.SnapshotNameUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.index.artifact.Gav;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the Maven artifacts (RPS-2064): the panel lists and details, the paging and the
 * Maven version sort, the {@code require*} checks of the delete flow, the registered versions the
 * protocol asks for, and the lookups of an artifact and of the version a file belongs to that the
 * deploy, signature and plugin flows share, so that none of them knows the repositories' finders.
 * Nothing here writes: the class is read only.
 */
@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ArtifactQueryService {

  private static final String METADATA_FILENAME = "maven-metadata.xml";

  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final ArtifactMapper artifactConverter;

  private final StorageStrategyRegistry storageStrategyRegistry;

  /** One indexed query, see {@link ArtifactVersionRepository#findRegisteredVersions}. */
  public List<RegisteredVersion> getRegisteredVersions(
      final BaseRepoInfo<UUID> repoInfo, final String groupId, final String artifactId) {

    return this.artifactVersionRepository.findRegisteredVersions(
        repoInfo.getStorageKey(), groupId, artifactId);
  }

  /**
   * The artifact of a repo, or empty when it is not registered. Shared by the deploy path and the
   * signature and plugin flows, so that none of them knows the repository's finder.
   */
  public Optional<Artifact> findArtifact(
      final UUID repoId, final String groupName, final String artifactName) {

    return this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
        repoId, groupName, artifactName);
  }

  /**
   * The registered version a file of {@code gav} belongs to (a snapshot file belongs to its base
   * version) in the artifact, or empty when it is not registered.
   */
  public Optional<ArtifactVersion> findVersionByGav(final UUID artifactId, final Gav gav) {

    final var gavVersion = gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();

    return this.artifactVersionRepository.findByArtifactIdAndVersionName(artifactId, gavVersion);
  }

  /**
   * The registered version a file of {@code gav} belongs to in the repo, or empty when the artifact
   * or the version is not registered.
   */
  public Optional<ArtifactVersion> findRegisteredVersion(final UUID repoId, final Gav gav) {

    return this.findArtifact(repoId, gav.getGroupId(), gav.getArtifactId())
        .flatMap(artifact -> this.findVersionByGav(artifact.getId(), gav));
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
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    if (this.artifactVersionRepository
        .findByArtifactIdAndVersionName(artifact.getId(), versionName)
        .isEmpty()) {
      throw new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND);
    }
  }

  /**
   * Confirms the artifact exists, without mutating anything. Called before an artifact delete
   * reaches {@code hasOnlyOneArtifact}: an artifact name that does not exist in a group holding
   * exactly one artifact used to be routed into deleting the whole group (RPS-1573, the same shape
   * as RPS-1190 for versions).
   *
   * @throws ItemNotFoundException {@code artifactNotFound}
   */
  public void requireArtifact(
      final UUID repoId, final String groupName, final String artifactName) {

    if (this.artifactRepository
        .findByRepoIdAndGroupNameAndArtifactName(repoId, groupName, artifactName)
        .isEmpty()) {
      throw new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND);
    }
  }

  /**
   * Confirms the group holds at least one artifact, without mutating anything. A group has no row
   * of its own, so a group that is not there is one without artifacts, and deleting it is a 404
   * like every other delete of something missing (RPS-1573).
   *
   * @throws ItemNotFoundException {@code groupNotFound}
   */
  public void requireGroup(final UUID repoId, final String groupName) {

    if (this.artifactRepository.countByRepoIdAndGroupName(repoId, groupName) == 0) {
      throw new ItemNotFoundException(ProtocolErrorCodes.GROUP_NOT_FOUND);
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
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

    final ArtifactVersion version;

    if (versionName == null) {
      // No latest (the last version was deleted): no row has a null name, so it is not found. The
      // repository takes no null parameter under @NullMarked.
      final var latest = artifact.getLatest();

      version =
          (latest == null
                  ? Optional.<ArtifactVersion>empty()
                  : this.artifactVersionRepository.findByArtifactIdAndVersionName(
                      artifact.getId(), latest))
              .orElseThrow(
                  () -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND));
    } else {
      version =
          this.artifactVersionRepository
              .findByArtifactIdAndVersionName(artifact.getId(), versionName)
              .orElseThrow(
                  () -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_VERSION_NOT_FOUND));
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

    final var versionNameOrder = versionNameSort(pageable);

    if (versionNameOrder != null) {
      return this.sortByVersionAndPage(
          this.artifactVersionRepository
              .findAllByRepoIdAndGroupNameAndArtifactName(
                  repoId, groupName, artifactName, Pageable.unpaged())
              .getContent(),
          pageable,
          versionNameOrder);
    }

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

    final var versionNameOrder = versionNameSort(pageable);

    if (versionNameOrder != null) {
      return this.sortByVersionAndPage(
          this.artifactVersionRepository
              .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
                  repoId, groupName, artifactName, version, Pageable.unpaged())
              .getContent(),
          pageable,
          versionNameOrder);
    }

    return this.artifactVersionRepository
        .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
            repoId, groupName, artifactName, version, pageable)
        .map(this.artifactConverter::toArtifactVersionListItemDto);
  }

  /**
   * The direction the caller asked to sort {@code versionName} by, or {@code null} when the request
   * sorts by something else (id, lastUpdatedAt), which stays a plain database {@code ORDER BY}.
   */
  private static Sort.@Nullable Direction versionNameSort(final Pageable pageable) {

    final var order = pageable.getSort().getOrderFor("versionName");

    return order == null ? null : order.getDirection();
  }

  /**
   * Orders a whole set of a Maven artifact's versions by Maven's own version comparison (RPS-1665)
   * and slices out the requested page. A database {@code ORDER BY versionName} sorts the column as
   * a string, so {@code 1.9.0} would sit above {@code 1.10.0} and a {@code SNAPSHOT} above the
   * release it precedes; {@link VersionComparator} (backed by {@code ComparableVersion}, the same
   * comparator {@link ArtifactVersionWriteService} uses to pick the latest and release version)
   * gets that right. The set has to be sorted whole, not page by page, or paging itself would be
   * wrong: this mirrors {@link ArtifactVersionWriteService}, which already loads every version of
   * an artifact to work out its latest and release version (RPS-1331), so the same bound already
   * applies to a single artifact's versions.
   */
  private Page<io.repsy.os.generated.model.ArtifactVersionListItem> sortByVersionAndPage(
      final List<ArtifactVersionListItem> versions,
      final Pageable pageable,
      final Sort.Direction direction) {

    final Comparator<ArtifactVersionListItem> byVersion =
        Comparator.comparing(ArtifactVersionListItem::getVersionName, new VersionComparator());

    final var ordered = direction.isDescending() ? byVersion.reversed() : byVersion;

    final var sorted =
        versions.stream()
            .sorted(ordered)
            .map(this.artifactConverter::toArtifactVersionListItemDto)
            .toList();

    final var start = (int) Math.min(pageable.getOffset(), sorted.size());
    final var end = Math.min(start + pageable.getPageSize(), sorted.size());

    return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
  }

  public Page<io.repsy.os.generated.model.ArtifactListItem> getArtifactsContainsGroupName(
      final UUID repoId, final String groupName, final Pageable pageable) {

    return this.artifactRepository
        .findAllByRepoIdAndContainsGroupName(repoId, LikePatterns.of("%", groupName, "%"), pageable)
        .map(this.artifactConverter::toArtifactListItemDto);
  }

  public Page<io.repsy.os.generated.model.ArtifactListItem> getArtifactsContainsArtifactName(
      final UUID repoId,
      final String groupName,
      final String artifactName,
      final Pageable pageable) {

    return this.artifactRepository
        .findAllByRepoIdContainsArtifactName(
            repoId, groupName, LikePatterns.of("%", artifactName, "%"), pageable)
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
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.ARTIFACT_NOT_FOUND));

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
      throw new ItemNotFoundException(ProtocolErrorCodes.GROUP_NOT_FOUND);
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

  /**
   * The POM file name of a snapshot version. The version-level {@code maven-metadata.xml} names it
   * when there is one that lists a {@code pom} (what a Maven client resolves). Without it, or when
   * it is unusable (no {@code <versioning>}, no {@code pom}, or content that cannot be parsed at
   * all, RPS-1421), the newest POM stored in the version directory is used (RPS-1370): sbt and Ivy
   * deploy a snapshot under its literal name and upload no metadata at all. A literal POM counts as
   * older than a timestamped one, see {@link SnapshotNameUtils#newestSnapshotPomName}. Unparsable
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

    final var resourceOpt = this.mavenStorage().get(versionPath, repoInfo.getName());

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
        SnapshotNameUtils.newestSnapshotPomName(
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
   * MavenMetadataUtils#readMetadata} refuses it with the unchecked {@link BadRequestException},
   * which would answer the panel's version detail with a 400 although the POM is in the directory
   * (RPS-1421).
   */
  private static @Nullable Metadata readSnapshotMetadataQuietly(
      final Resource metadataResource, final StoragePath metadataPath) throws IOException {

    try {
      return MavenMetadataUtils.readMetadata(metadataResource.getContentAsByteArray());
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
      return this.mavenStorage().listDirectoryContents(directory).stream()
          .filter(item -> !item.isDirectory())
          .map(StorageItemInfo::getName)
          .toList();
    } catch (final ItemNotFoundException _) {
      return List.of();
    }
  }

  private StorageStrategy mavenStorage() {
    return this.storageStrategyRegistry.get(RepoType.MAVEN);
  }
}
