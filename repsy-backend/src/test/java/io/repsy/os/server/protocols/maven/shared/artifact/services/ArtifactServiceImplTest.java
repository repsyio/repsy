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

import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType.NEW;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType.REDEPLOY;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.PLUGIN;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.RELEASE;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.SNAPSHOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

/**
 * The {@code releases} / {@code snapshots} repo settings refuse a version of that kind, whether it
 * is new or already exists (RPS-1174). Before, a redeploy returned early and skipped the rule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Maven ArtifactServiceImpl version-type rules (RPS-1174)")
class ArtifactServiceImplTest {

  private static final String SNAPSHOT_JAR =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar";
  private static final String RELEASE_JAR = "com/acme/lib/1.0/lib-1.0.jar";
  private static final String ARTIFACT_METADATA = "com/acme/lib/maven-metadata.xml";
  private static final String PLUGIN_METADATA =
      """
      <metadata>
        <groupId>com.acme</groupId>
        <artifactId>lib</artifactId>
        <plugins>
          <plugin>
            <name>Lib Plugin</name>
            <prefix>lib</prefix>
            <artifactId>lib-maven-plugin</artifactId>
          </plugin>
        </plugins>
      </metadata>
      """;

  @Mock RepoRepository repoRepository;
  @Mock ArtifactRepository artifactRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock VersionDeveloperRepository versionDeveloperRepository;
  @Mock VersionLicenseRepository versionLicenseRepository;
  @Mock ArtifactConverter artifactConverter;
  @Mock PGPVerifierService pgpVerifierService;
  @Mock KeyStoreService keyStoreService;
  @Mock ArtifactUpsertHelper artifactUpsertHelper;
  @Mock ArtifactVersionWriteService artifactVersionWriteService;
  @Mock StorageStrategy storageStrategy;

  @InjectMocks ArtifactServiceImpl artifactService;

  private static RepoInfo repo(
      final UUID id, final boolean releases, final boolean snapshots, final boolean allowOverride) {
    return RepoInfo.builder()
        .id(id)
        .storageKey(id)
        .name("mvn")
        .releases(releases)
        .snapshots(snapshots)
        .allowOverride(allowOverride)
        .build();
  }

  private static MutablePair<ArtifactDeployType, ArtifactVersionType> pair(
      final ArtifactDeployType deployType, final ArtifactVersionType versionType) {
    return new MutablePair<>(deployType, versionType);
  }

  private Artifact stubArtifact(final UUID repoId) {
    final var artifact = new Artifact();
    artifact.setId(UUID.randomUUID());

    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(repoId, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));

    return artifact;
  }

  private void stubVersion(
      final Artifact artifact, final String versionName, final boolean exists) {
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(
            artifact.getId(), versionName))
        .thenReturn(exists ? Optional.of(new ArtifactVersion()) : Optional.empty());
  }

  @Test
  @DisplayName("a timestamped snapshot file of an existing version is a snapshot redeploy")
  void classifiesAnExistingSnapshotFileAsSnapshotRedeploy() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    this.stubVersion(artifact, "1.0-SNAPSHOT", true);

    final var result =
        this.artifactService.getDeployAndVersionType(
            repo(id, true, true, true), StoragePath.of(id, SNAPSHOT_JAR));

    assertThat(result).isEqualTo(pair(REDEPLOY, SNAPSHOT));
  }

  @Test
  @DisplayName("a snapshot redeploy is refused while snapshots are off")
  void refusesASnapshotRedeployWhenSnapshotsAreOff() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, false, true);
    final var path = StoragePath.of(id, SNAPSHOT_JAR);

    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(repo, pair(REDEPLOY, SNAPSHOT), path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
  }

  @Test
  @DisplayName("a release redeploy is refused while releases are off")
  void refusesAReleaseRedeployWhenReleasesAreOff() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, true, true);
    final var path = StoragePath.of(id, RELEASE_JAR);

    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(repo, pair(REDEPLOY, RELEASE), path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");
  }

  @Test
  @DisplayName("a redeploy is allowed while its kind is on")
  void allowsARedeployWhileItsKindIsOn() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, true, true);
    final var path = StoragePath.of(id, SNAPSHOT_JAR);

    assertThatCode(
            () -> this.artifactService.checkDeploymentRules(repo, pair(REDEPLOY, SNAPSHOT), path))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the override rule is checked first, so its message wins")
  void overrideRuleStillWins() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, false, false);
    final var path = StoragePath.of(id, SNAPSHOT_JAR);
    when(this.storageStrategy.get(path, "mvn"))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(repo, pair(REDEPLOY, SNAPSHOT), path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("artifactOverrideIsProhibited");
  }

  @Test
  @DisplayName("plugin metadata is a new deploy of a plugin and no version-type rule applies")
  void pluginMetadataIsNotSubjectToTheRule() throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result =
        this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
            repo, PLUGIN_METADATA.getBytes(StandardCharsets.UTF_8), "maven-metadata.xml");

    assertThat(result).isEqualTo(pair(NEW, PLUGIN));
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo, result, StoragePath.of(id, ARTIFACT_METADATA)))
        .doesNotThrowAnyException();
  }
}
