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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import java.io.IOException;
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
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
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

    final var resolvedVersion =
        ArtifactUtils.isSnapshot(artifactVersion)
            ? this.resolveSnapshotBuildVersion(
                repoId, repoName, groupId, artifactId, artifactVersion, extension)
            : artifactVersion;

    if (resolvedVersion == null) {
      return Optional.empty();
    }

    final var gav =
        new Gav(
            groupId,
            artifactId,
            resolvedVersion,
            null,
            extension,
            null,
            null,
            null,
            false,
            null,
            false,
            null);

    return Optional.of(new M2GavCalculator().gavToPath(gav));
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

  private static @Nullable Metadata readMetadataQuietly(final @NonNull Resource resource) {
    try (final var inputStream = resource.getInputStream()) {
      return ArtifactUtils.readMetadata(inputStream.readAllBytes());
    } catch (final IOException | XmlPullParserException exception) {
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
