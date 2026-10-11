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

import io.repsy.core.web_error.ConstraintViolations;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.PomModelUtils;
import io.repsy.protocols.maven.shared.utils.SnapshotNameUtils;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Writes the artifact and version rows of a registered POM (RPS-2167, split out of {@link
 * ArtifactDeploymentService}): creates them, or updates the ones that exist, and recovers from a
 * concurrent upload that inserted the same row first. It has no transaction of its own, and neither
 * has the upload that calls it (RPS-2176): a transaction held across the two inserts, which go
 * through {@link ArtifactUpsertHelper} and run in transactions of their own ({@code REQUIRES_NEW},
 * unchanged), needed a second pooled connection while it held the first. Each write below is a
 * transaction of its own, and the ones that belong together are one method of {@link
 * ArtifactVersionWriteService}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
class ArtifactRowWriteService {

  private static final String SOURCES_CLASSIFIER = "sources";
  private static final String JAVADOC_CLASSIFIER = "javadoc";
  private static final String ARTIFACT_UNIQUE_CONSTRAINT =
      "ux_maven_artifact__repo_id_group_artifact";
  private static final String ARTIFACT_VERSION_UNIQUE_CONSTRAINT =
      "ux_maven_artifact_version__artifact_id_version_name";

  private final ArtifactRepository artifactRepository;
  private final ArtifactUpsertHelper artifactUpsertHelper;
  private final ArtifactVersionWriteService artifactVersionWriteService;
  private final ArtifactQueryService artifactQueryService;
  private final StorageStrategyRegistry storageStrategyRegistry;

  void createOrUpdateArtifactByPomFile(
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

    this.artifactVersionWriteService.replaceVersionDetails(pomModel, artifactVersion);

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
