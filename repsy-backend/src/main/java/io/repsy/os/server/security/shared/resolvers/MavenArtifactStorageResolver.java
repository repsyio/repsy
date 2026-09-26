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
package io.repsy.os.server.security.shared.resolvers;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@NullMarked
public class MavenArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("MAVEN");
  private static final String DEFAULT_EXTENSION = "jar";
  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final Map<String, String> PACKAGING_TO_EXTENSION =
      Map.of(
          "maven-plugin", "jar",
          "ejb", "jar",
          "ejb-client", "jar",
          "bundle", "jar",
          "war", "war",
          "ear", "ear",
          "rar", "rar",
          "pom", "pom",
          "jar", "jar");

  private final @NonNull ArtifactRepository artifactRepository;
  private final @NonNull ArtifactVersionRepository artifactVersionRepository;
  private final @NonNull StorageStrategy mavenStorageStrategy;

  public MavenArtifactStorageResolver(
      final @NonNull ArtifactRepository artifactRepository,
      final @NonNull ArtifactVersionRepository artifactVersionRepository,
      final @Qualifier("osStorageStrategyMaven") @NonNull StorageStrategy mavenStorageStrategy) {
    this.artifactRepository = artifactRepository;
    this.artifactVersionRepository = artifactVersionRepository;
    this.mavenStorageStrategy = mavenStorageStrategy;
  }

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    final var separatorIndex = artifactName.indexOf(':');

    if (separatorIndex < 0) {
      return Optional.empty();
    }

    final var groupId = artifactName.substring(0, separatorIndex);
    final var artifactId = artifactName.substring(separatorIndex + 1);
    final var packaging = this.findPackaging(repoId, groupId, artifactId, artifactVersion);
    final var extension = resolveExtension(packaging);

    if (ArtifactUtils.isSnapshot(artifactVersion)) {
      return this.resolveSnapshot(
          repoId, repoName, groupId, artifactId, artifactVersion, extension);
    }

    final var artifactPath = mainFilePath(groupId, artifactId, artifactVersion, extension);

    return this.mavenStorageStrategy.get(StoragePath.of(repoId, artifactPath), repoName).isPresent()
        ? Optional.of(artifactPath)
        : Optional.empty();
  }

  private Optional<String> resolveSnapshot(
      final UUID repoId,
      final String repoName,
      final String groupId,
      final String artifactId,
      final String artifactVersion,
      final String extension) {

    final var buildVersion =
        this.resolveSnapshotBuildVersion(
            repoId, repoName, groupId, artifactId, artifactVersion, extension);

    if (buildVersion == null) {
      return this.findStoredSnapshotFile(
          repoId, repoName, groupId, artifactId, artifactVersion, extension);
    }

    final var artifactPath = mainFilePath(groupId, artifactId, buildVersion, extension);

    if (this.mavenStorageStrategy.get(StoragePath.of(repoId, artifactPath), repoName).isPresent()) {
      return Optional.of(artifactPath);
    }

    // The metadata names a build whose file is not stored (deleted by hand, partial upload,
    // cleanup, RPS-1447). A scan is about what is stored, so it takes the newest stored main file
    // instead of answering 404 while an older build is there.
    final var stored =
        this.findStoredSnapshotFile(
            repoId, repoName, groupId, artifactId, artifactVersion, extension);
    stored.ifPresent(
        path ->
            log.info(
                "Snapshot metadata of {}:{}:{} names {}, which is not stored: scanning {} instead",
                groupId,
                artifactId,
                artifactVersion,
                artifactPath,
                path));
    return stored;
  }

  private static String mainFilePath(
      final String groupId, final String artifactId, final String version, final String extension) {

    final var gav =
        new Gav(
            groupId,
            artifactId,
            version,
            null,
            extension,
            null,
            null,
            null,
            false,
            null,
            false,
            null);

    return new M2GavCalculator().gavToPath(gav);
  }

  private @Nullable String resolveSnapshotBuildVersion(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String groupId,
      final @NonNull String artifactId,
      final @NonNull String artifactVersion,
      final @NonNull String extension) {

    final var groupPath = groupId.replace('.', '/');
    final var metadataPath =
        groupPath + "/" + artifactId + "/" + artifactVersion + "/" + METADATA_FILENAME;
    final var storagePath = StoragePath.of(repoId, metadataPath);
    final var resource = this.mavenStorageStrategy.get(storagePath, repoName);

    if (resource.isEmpty()) {
      return null;
    }

    final var versioning = readVersioning(resource.get());

    if (versioning == null) {
      return null;
    }

    return findSnapshotVersionForExtension(versioning, extension)
        .or(() -> buildFromSnapshotTimestamp(versioning, artifactVersion))
        .orElse(null);
  }

  /**
   * The newest main file of a {@code SNAPSHOT} version directory, for a snapshot whose metadata
   * names no build (RPS-1420): sbt and Ivy deploy a snapshot under its literal name and upload no
   * {@code maven-metadata.xml}, and so does a version whose metadata cannot be read. It is also the
   * answer when the metadata names a build whose file is not stored (RPS-1447). The rule is the one
   * the panel uses for the POM, see {@link ArtifactUtils#newestSnapshotMainFileName}.
   */
  private Optional<String> findStoredSnapshotFile(
      final UUID repoId,
      final String repoName,
      final String groupId,
      final String artifactId,
      final String artifactVersion,
      final String extension) {

    final var versionDirectory =
        groupId.replace('.', '/') + "/" + artifactId + "/" + artifactVersion;
    final List<String> fileNames;

    try {
      fileNames =
          this.mavenStorageStrategy
              .listDirectoryContents(StoragePath.of(repoId, versionDirectory))
              .stream()
              .filter(item -> !item.isDirectory())
              .map(StorageItemInfo::getName)
              .toList();
    } catch (final ItemNotFoundException _) {
      return Optional.empty();
    }

    final var fileName =
        ArtifactUtils.newestSnapshotMainFileName(artifactId, artifactVersion, extension, fileNames);

    if (fileName == null) {
      return Optional.empty();
    }

    final var artifactPath = versionDirectory + "/" + fileName;

    return this.mavenStorageStrategy.get(StoragePath.of(repoId, artifactPath), repoName).isPresent()
        ? Optional.of(artifactPath)
        : Optional.empty();
  }

  private static @Nullable Metadata readMetadataQuietly(final @NonNull Resource resource) {
    try (final var inputStream = resource.getInputStream()) {
      return ArtifactUtils.readMetadata(inputStream.readAllBytes());
    } catch (final IOException | BadRequestException exception) {
      // readMetadata refuses unparsable content with the unchecked BadRequestException
      // (malformedMetadataFile), which is not an IOException: it has to be caught here too, or a
      // corrupt stored file answers the download with a 400 instead of the fallback (RPS-1180).
      log.warn("Failed to read snapshot metadata at {}: {}", resource, exception.getMessage());
      return null;
    }
  }

  private static @Nullable Versioning readVersioning(final @NonNull Resource resource) {
    final var metadata = readMetadataQuietly(resource);
    return metadata == null ? null : metadata.getVersioning();
  }

  private static @NonNull Optional<String> findSnapshotVersionForExtension(
      final @NonNull Versioning versioning, final @NonNull String extension) {

    return versioning.getSnapshotVersions().stream()
        .filter(snapshotVersion -> extension.equals(snapshotVersion.getExtension()))
        .filter(
            snapshotVersion ->
                snapshotVersion.getClassifier() == null
                    || snapshotVersion.getClassifier().isBlank())
        .map(SnapshotVersion::getVersion)
        .findFirst();
  }

  private static @NonNull Optional<String> buildFromSnapshotTimestamp(
      final @NonNull Versioning versioning, final @NonNull String artifactVersion) {

    final var snapshot = versioning.getSnapshot();

    if (snapshot == null || snapshot.getTimestamp() == null) {
      return Optional.empty();
    }

    final var baseVersion =
        artifactVersion.substring(0, artifactVersion.length() - "SNAPSHOT".length());

    return Optional.of(baseVersion + snapshot.getTimestamp() + "-" + snapshot.getBuildNumber());
  }

  private @Nullable String findPackaging(
      final @NonNull UUID repoId,
      final @NonNull String groupId,
      final @NonNull String artifactId,
      final @NonNull String artifactVersion) {

    return this.artifactRepository
        .findByRepoIdAndGroupNameAndArtifactName(repoId, groupId, artifactId)
        .flatMap(
            artifact ->
                this.artifactVersionRepository.findByArtifactIdAndVersionName(
                    artifact.getId(), artifactVersion))
        .map(ArtifactVersion::getPackaging)
        .orElse(null);
  }

  private static @NonNull String resolveExtension(final @Nullable String packaging) {

    if (packaging == null || packaging.isBlank()) {
      return DEFAULT_EXTENSION;
    }

    return PACKAGING_TO_EXTENSION.getOrDefault(packaging, packaging);
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
