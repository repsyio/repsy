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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactMapper;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.maven.shared.keystore.services.PgpVerifierService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pins the write path of a stored POM that {@code ArtifactDeploymentServiceTest} leaves open
 * (RPS-2167): the recovery from a concurrent insert of the artifact or of the version row, and the
 * update of a version that is already registered. The write path is split into focused services in
 * the same change, so these hold on the code before and after it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Maven POM registration write path (RPS-2167)")
class ArtifactWritePathTest {

  private static final String ARTIFACT_CONSTRAINT = "ux_maven_artifact__repo_id_group_artifact";
  private static final String VERSION_CONSTRAINT =
      "ux_maven_artifact_version__artifact_id_version_name";
  private static final String POM_PATH = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String POM =
      """
      <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>\
      <artifactId>lib</artifactId><version>1.0</version><name>Lib</name></project>""";

  @Mock RepoTxService repoTxService;
  @Mock ArtifactRepository artifactRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock VersionDeveloperRepository versionDeveloperRepository;
  @Mock VersionLicenseRepository versionLicenseRepository;
  @Mock ArtifactMapper artifactConverter;
  @Mock PgpVerifierService pgpVerifierService;
  @Mock KeyStoreService keyStoreService;
  @Mock ArtifactUpsertHelper artifactUpsertHelper;
  @Mock ArtifactVersionWriteService artifactVersionWriteService;
  @Mock VersionSignatureRepository versionSignatureRepository;
  @Mock PendingSignatureService pendingSignatureService;
  @Mock PendingSignatureRepository pendingSignatureRepository;
  @Mock StorageStrategy storageStrategy;
  @Mock StorageStrategyRegistry storageStrategyRegistry;

  ArtifactDeploymentService artifactService;

  private final UUID repoId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    lenient()
        .when(this.storageStrategyRegistry.get(RepoType.MAVEN))
        .thenReturn(this.storageStrategy);

    final var queryService =
        new ArtifactQueryService(
            this.artifactRepository,
            this.artifactVersionRepository,
            this.artifactConverter,
            this.storageStrategyRegistry);
    final var signatureService =
        new ArtifactSignatureService(
            this.versionSignatureRepository,
            this.artifactVersionRepository,
            this.storageStrategyRegistry,
            this.keyStoreService,
            this.pgpVerifierService,
            queryService,
            this.pendingSignatureService);
    final var pluginMetadataService =
        new MavenPluginMetadataService(
            this.artifactRepository,
            this.artifactVersionRepository,
            queryService,
            this.storageStrategyRegistry);

    this.artifactService =
        new ArtifactDeploymentService(
            this.repoTxService,
            this.artifactRepository,
            this.artifactVersionRepository,
            this.versionDeveloperRepository,
            this.versionLicenseRepository,
            this.artifactUpsertHelper,
            this.artifactVersionWriteService,
            this.pendingSignatureService,
            this.pendingSignatureRepository,
            queryService,
            signatureService,
            pluginMetadataService,
            this.storageStrategyRegistry);

    final var repo = new Repo();
    repo.setId(this.repoId);
    repo.setName("mvn");
    lenient().when(this.repoTxService.requireRepo("mvn", RepoType.MAVEN)).thenReturn(repo);
  }

  private void registerPom() {
    final var repoInfo =
        RepoInfo.builder()
            .id(this.repoId)
            .storageKey(this.repoId)
            .name("mvn")
            .releases(true)
            .snapshots(true)
            .allowOverride(true)
            .build();

    this.artifactService.createOrUpdateArtifact(
        repoInfo,
        StoragePath.of(this.repoId, POM_PATH),
        new ByteArrayResource(POM.getBytes(UTF_8)));
  }

  private static DataIntegrityViolationException uniqueViolation(final String constraint) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new SQLException(
            "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"",
            "23505"));
  }

  private Artifact existingArtifact() {
    final var artifact = new Artifact();
    artifact.setId(UUID.randomUUID());
    artifact.setGroupName("com.acme");
    artifact.setArtifactName("lib");
    final var repo = new Repo();
    repo.setId(this.repoId);
    artifact.setRepo(repo);

    return artifact;
  }

  @Test
  @DisplayName("a concurrent artifact insert updates the row the other upload committed")
  void aConcurrentArtifactInsertUpdatesTheCommittedRow() {
    final var existing = this.existingArtifact();
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenThrow(uniqueViolation(ARTIFACT_CONSTRAINT));
    when(this.artifactRepository.save(existing)).thenReturn(existing);

    this.registerPom();

    verify(this.artifactRepository).save(existing);
    assertThat(existing.getName()).isEqualTo("Lib");
    verify(this.artifactUpsertHelper).insertArtifactVersion(any(), any(), any());
  }

  @Test
  @DisplayName("an artifact insert that fails on another constraint is not swallowed")
  void anotherArtifactConstraintIsRethrown() {
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.empty());
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenThrow(uniqueViolation("ux_something_else"));

    assertThatThrownBy(this::registerPom).isInstanceOf(DataIntegrityViolationException.class);

    verify(this.artifactUpsertHelper, never()).insertArtifactVersion(any(), any(), any());
  }

  @Test
  @DisplayName("a concurrent artifact insert whose row cannot be found is rethrown")
  void aVanishedArtifactRowIsRethrown() {
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.empty());
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenThrow(uniqueViolation(ARTIFACT_CONSTRAINT));

    assertThatThrownBy(this::registerPom).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("a concurrent version insert updates the row the other upload committed")
  void aConcurrentVersionInsertUpdatesTheCommittedRow() {
    final var artifact = this.existingArtifact();
    final var existingVersion = new ArtifactVersion();
    existingVersion.setId(UUID.randomUUID());
    existingVersion.setVersionName("1.0");
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));
    when(this.artifactRepository.save(artifact)).thenReturn(artifact);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.empty(), Optional.of(existingVersion));
    doThrow(uniqueViolation(VERSION_CONSTRAINT))
        .when(this.artifactUpsertHelper)
        .insertArtifactVersion(any(), any(), any());

    this.registerPom();

    verify(this.versionDeveloperRepository).deleteAllByArtifactVersionId(existingVersion.getId());
    verify(this.versionLicenseRepository).deleteAllByArtifactVersionId(existingVersion.getId());
    verify(this.artifactVersionWriteService).createVersionDevelopers(any(), any());
    verify(this.artifactVersionWriteService).createVersionLicenses(any(), any());
    verify(this.artifactVersionRepository).save(existingVersion);
    assertThat(existingVersion.getName()).isEqualTo("Lib");
  }

  @Test
  @DisplayName("a version insert that fails on another constraint is not swallowed")
  void anotherVersionConstraintIsRethrown() {
    final var artifact = this.existingArtifact();
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));
    when(this.artifactRepository.save(artifact)).thenReturn(artifact);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.empty());
    doThrow(uniqueViolation("ux_something_else"))
        .when(this.artifactUpsertHelper)
        .insertArtifactVersion(any(), any(), any());

    assertThatThrownBy(this::registerPom).isInstanceOf(DataIntegrityViolationException.class);

    verify(this.artifactVersionRepository, never()).save(any());
  }

  @Test
  @DisplayName("a registered version is updated in place and its developers and licenses rewritten")
  void aRegisteredVersionIsUpdatedInPlace() {
    final var artifact = this.existingArtifact();
    final var existingVersion = new ArtifactVersion();
    existingVersion.setId(UUID.randomUUID());
    existingVersion.setVersionName("1.0");
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            this.repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));
    when(this.artifactRepository.save(artifact)).thenReturn(artifact);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(existingVersion));
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(List.of());

    this.registerPom();

    verify(this.versionDeveloperRepository).deleteAllByArtifactVersionId(existingVersion.getId());
    verify(this.versionLicenseRepository).deleteAllByArtifactVersionId(existingVersion.getId());
    verify(this.artifactVersionWriteService).createVersionDevelopers(any(), any());
    verify(this.artifactVersionWriteService).createVersionLicenses(any(), any());
    verify(this.artifactVersionRepository).save(existingVersion);
    verify(this.artifactUpsertHelper, never()).insertArtifactVersion(any(), any(), any());
    verify(this.artifactUpsertHelper, never()).insertArtifact(any());
    assertThat(existingVersion.getName()).isEqualTo("Lib");
  }
}
