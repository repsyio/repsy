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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
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
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * The {@code releases} / {@code snapshots} repo settings refuse a version of that kind, whether it
 * is new or already exists (RPS-1174). Before, a redeploy returned early and skipped the rule.
 *
 * <p>{@code maven-metadata.xml} is judged only at version level, by its {@code <version>}
 * (RPS-1176). Before, every parseable metadata file was classified as plugin metadata and no
 * version-type rule applied to a metadata upload.
 *
 * <p>A path that does not parse to a GAV is refused with {@code invalidArtifactPath} (RPS-1182).
 * Before, the upload was dropped silently and answered 200.
 *
 * <p>A stored POM that declares another groupId than its path's is not registered (RPS-1193). The
 * facade refuses such a POM before it is stored; the skip stays for one stored before that.
 *
 * <p>A checksum is judged by the file it belongs to: its layout check, version type and override
 * rule are those of that file. A metadata checksum, whose body is a hash, is judged by its
 * directory alone (RPS-1183). Before, every checksum was let through unjudged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
    "Maven ArtifactServiceImpl version-type rules (RPS-1174, RPS-1176, RPS-1182, RPS-1183)")
class ArtifactServiceImplTest {

  private static final String SNAPSHOT_JAR =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar";
  private static final String RELEASE_JAR = "com/acme/lib/1.0/lib-1.0.jar";
  private static final String ARTIFACT_METADATA = "com/acme/lib/maven-metadata.xml";
  private static final String GROUP_METADATA_PATH = "com/acme/maven-metadata.xml";
  private static final String SNAPSHOT_VERSION_METADATA =
      "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
  private static final String RELEASE_VERSION_METADATA = "com/acme/lib/1.0/maven-metadata.xml";

  private static final String GROUP_METADATA =
      """
      <metadata><plugins><plugin><name>Acme Maven Plugin</name><prefix>acme</prefix>\
      <artifactId>acme-maven-plugin</artifactId></plugin></plugins></metadata>""";
  private static final String ARTIFACT_METADATA_MIXED =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
      <release>1.0</release><versions><version>1.1-SNAPSHOT</version><version>1.0</version>\
      </versions><lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";
  private static final String VERSION_METADATA =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>%s</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      <snapshotVersions><snapshotVersion><extension>jar</extension>\
      <value>1.0-20260921.101010-1</value><updated>20260921101010</updated></snapshotVersion>\
      <snapshotVersion><extension>pom</extension><value>1.0-20260921.101010-1</value>\
      <updated>20260921101010</updated></snapshotVersion></snapshotVersions></versioning>\
      </metadata>""";

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

  private MutablePair<ArtifactDeployType, ArtifactVersionType> classify(
      final RepoInfo repo, final String path, final String body) throws Exception {
    return this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
        repo, body.getBytes(StandardCharsets.UTF_8), StoragePath.of(repo.getId(), path));
  }

  @Test
  @DisplayName("plugin metadata is classified as a plugin and no version-type rule applies to it")
  void pluginMetadataIsNotSubjectToTheRule() throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, GROUP_METADATA_PATH, GROUP_METADATA);

    assertThat(result).isEqualTo(pair(null, PLUGIN));
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo, result, StoragePath.of(id, GROUP_METADATA_PATH)))
        .doesNotThrowAnyException();
    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @Test
  @DisplayName("version-level metadata of a snapshot is judged as a snapshot by its version")
  void versionLevelSnapshotMetadataIsJudgedAsASnapshot() throws Exception {
    final var id = UUID.randomUUID();
    final var path = StoragePath.of(id, SNAPSHOT_VERSION_METADATA);
    final var xml = VERSION_METADATA.formatted("1.0-SNAPSHOT");

    final var refusing = repo(id, true, false, true);
    final var result = this.classify(refusing, SNAPSHOT_VERSION_METADATA, xml);

    assertThat(result).isEqualTo(pair(null, SNAPSHOT));
    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(refusing, result, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(repo(id, true, true, true), result, path))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("version-level metadata of a release version is judged as a release")
  void versionLevelMetadataOfAReleaseVersionIsARelease() throws Exception {
    final var id = UUID.randomUUID();
    final var path = StoragePath.of(id, RELEASE_VERSION_METADATA);
    final var xml = VERSION_METADATA.formatted("1.0");

    final var refusing = repo(id, false, true, true);
    final var result = this.classify(refusing, RELEASE_VERSION_METADATA, xml);

    assertThat(result).isEqualTo(pair(null, RELEASE));
    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(refusing, result, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(repo(id, true, true, true), result, path))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("artifact-level metadata lists versions of both kinds and is not judged")
  void artifactLevelMetadataIsNotJudged() throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, ARTIFACT_METADATA, ARTIFACT_METADATA_MIXED);

    assertThat(result).isEqualTo(pair(null, null));
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo, result, StoragePath.of(id, ARTIFACT_METADATA)))
        .doesNotThrowAnyException();
    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @Test
  @DisplayName("an artifact-level metadata checksum is stored without parsing the file")
  void metadataChecksumIsStoredWithoutParsing() throws Exception {
    final var repo = repo(UUID.randomUUID(), false, false, true);

    final var result = this.classify(repo, ARTIFACT_METADATA + ".sha1", "garbage");

    assertThat(result).isEqualTo(pair(null, null));
  }

  @Test
  @DisplayName(
      "a version-level snapshot metadata checksum is judged as a snapshot by its directory")
  void versionLevelMetadataChecksumIsJudgedAsASnapshotByItsDirectory() throws Exception {
    final var id = UUID.randomUUID();
    final var path = StoragePath.of(id, SNAPSHOT_VERSION_METADATA + ".sha1");

    final var refusing = repo(id, true, false, true);
    final var result = this.classify(refusing, SNAPSHOT_VERSION_METADATA + ".sha1", "garbage");

    assertThat(result).isEqualTo(pair(null, SNAPSHOT));
    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(refusing, result, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(repo(id, true, true, true), result, path))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0} is not judged")
  @ValueSource(
      strings = {
        "com/acme/maven-metadata.xml.sha1",
        "com/acme/lib/maven-metadata.xml.sha1",
        "com/acme/lib/maven-metadata.xml.md5",
        "com/acme/lib/1.0/maven-metadata.xml.sha1"
      })
  @DisplayName("the other metadata checksums are not judged, the file being a hash (RPS-1183)")
  void otherMetadataChecksumsAreNotJudged(final String path) throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, path, "garbage");

    assertThat(result).isEqualTo(pair(null, null));
    assertThatCode(
            () -> this.artifactService.checkDeploymentRules(repo, result, StoragePath.of(id, path)))
        .doesNotThrowAnyException();
    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @Test
  @DisplayName("the version-type rule applies even when the path does not parse to a GAV")
  void versionTypeRuleAppliesWithoutAGav() {
    final var id = UUID.randomUUID();
    final var noGav = StoragePath.of(id, GROUP_METADATA_PATH);

    assertThat(ArtifactUtils.getGavByFile(noGav)).isNull();
    assertThatThrownBy(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo(id, true, false, true), pair(null, SNAPSHOT), noGav))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo(id, true, true, true), pair(null, SNAPSHOT), noGav))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the version-type rule applies to a path that parses to a GAV")
  void versionTypeRuleAppliesToVersionLevelMetadataPath() {
    final var id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo(id, true, false, true),
                    pair(null, SNAPSHOT),
                    StoragePath.of(id, SNAPSHOT_VERSION_METADATA)))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
  }

  @ParameterizedTest(name = "{0} is refused")
  @ValueSource(
      strings = {
        "io/stray.txt",
        "stray.txt",
        "com/acme/lib/1.0/other-1.0.jar",
        "com/acme/lib/1.0/lib-2.0.jar",
        "com/acme/lib/1.0/Lib-1.0.jar",
        "com/acme/lib/1.0/lib-1.0",
        "com/acme/lib/1.0/jars/lib.jar",
        "com/acme/lib/1.0-SNAPSHOT/stray.txt",
        "com/acme/lib/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-20260921.101010-1.pom",
        "com/acme/lib/1.0-SNAPSHOT/lob-1.0-SNAPSHOT.jar",
        "io/stray.txt.sha1",
        "com/acme/lib/1.0/other-1.0.jar.sha1",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar.sha1"
      })
  @DisplayName(
      "a path outside the artifact layout is refused before any query, a checksum of it too"
          + " (RPS-1182, RPS-1183, RPS-1184)")
  void refusesAPathOutsideTheArtifactLayoutBeforeAnyQuery(final String path) {
    final var id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                this.artifactService.getDeployAndVersionType(
                    repo(id, true, true, true), StoragePath.of(id, path)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalidArtifactPath");
    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @ParameterizedTest(name = "{0} is a {1}")
  @CsvSource({
    "com/acme/lib/1.0/lib-1.0.jar, RELEASE",
    "com/acme/lib/1.0/lib-1.0.pom, RELEASE",
    "com/acme/lib/1.0/lib-1.0-sources.jar, RELEASE",
    "com/acme/lib/1.0/lib-1.0-javadoc.jar, RELEASE",
    "com/acme/lib/1.0/lib-1.0-tests.jar, RELEASE",
    "com/acme/lib/1.0/lib-1.0.tar.gz, RELEASE",
    "com/acme/lib/1.0/lib-1.0.module, RELEASE",
    "com/acme/lib/1.0/lib-1.0-kotlin-tooling-metadata.json, RELEASE",
    "com/acme/lib/1.0/lib-1.0.klib, RELEASE",
    "com/acme/lib/1.0/lib-1.0.jar.asc, RELEASE",
    "com/acme/lib_2.13/1.0/lib_2.13-1.0.jar, RELEASE",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar, SNAPSHOT",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1-sources.jar, SNAPSHOT",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar, SNAPSHOT",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-sources.jar, SNAPSHOT",
    "com/acme/lib/1.0-beta-SNAPSHOT/lib-1.0-beta-20260921.101010-1.jar, SNAPSHOT",
    "com/acme/lib/1.0/lib-1.0.jar.sha1, RELEASE",
    "com/acme/lib/1.0/lib-1.0.jar.md5, RELEASE",
    "com/acme/lib/1.0/lib-1.0.jar.sha256, RELEASE",
    "com/acme/lib/1.0/lib-1.0.jar.sha512, RELEASE",
    "com/acme/lib/1.0/lib-1.0.pom.sha1, RELEASE",
    "com/acme/lib/1.0/lib-1.0.jar.asc.sha1, RELEASE",
    "com/acme/lib/1.0/lib-1.0.module.sha512, RELEASE",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.sha1, SNAPSHOT",
    "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.md5, SNAPSHOT"
  })
  @DisplayName(
      "the files real Maven, Gradle and sbt clients send are classified, checksums by their file"
          + " (RPS-1182, RPS-1183)")
  void classifiesTheFilesRealClientsSend(final String path, final ArtifactVersionType versionType) {
    final var id = UUID.randomUUID();

    final var result =
        this.artifactService.getDeployAndVersionType(
            repo(id, true, true, true), StoragePath.of(id, path));

    assertThat(result).isEqualTo(pair(NEW, versionType));
  }

  @Test
  @DisplayName("a checksum of a kind that is switched off is refused like the file it belongs to")
  void checksumOfARefusedKindIsRefusedByTheVersionTypeRule() {
    final var id = UUID.randomUUID();
    final var releasesOff = repo(id, false, true, true);
    final var release = StoragePath.of(id, "com/acme/lib/3.5/lib-3.5.jar.sha1");

    final var releaseResult = this.artifactService.getDeployAndVersionType(releasesOff, release);

    assertThat(releaseResult).isEqualTo(pair(NEW, RELEASE));
    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(releasesOff, releaseResult, release))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");

    final var snapshotsOff = repo(id, true, false, true);
    final var snapshot =
        StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.sha1");

    final var snapshotResult = this.artifactService.getDeployAndVersionType(snapshotsOff, snapshot);

    assertThat(snapshotResult).isEqualTo(pair(NEW, SNAPSHOT));
    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(snapshotsOff, snapshotResult, snapshot))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
  }

  @Test
  @DisplayName("a checksum is an override only when the checksum file itself already exists")
  void checksumOfAnExistingVersionIsOnlyAnOverrideWhenTheChecksumExists() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, true, false);
    final var path = StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.sha1");
    final var pair = this.artifactService.getDeployAndVersionType(repo, path);

    assertThatCode(() -> this.artifactService.checkDeploymentRules(repo, pair, path))
        .doesNotThrowAnyException();
    verify(this.artifactRepository, never())
        .existsByRepoIdAndArtifactNameAndGroupNameAndArtifactVersionsVersionName(
            any(), any(), any(), any());

    when(this.storageStrategy.get(path, "mvn"))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, pair, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("artifactOverrideIsProhibited");
  }

  private static StoragePath pathOf(final String relativePath) {
    return argThat(path -> path.getRelativePath().getPath().equals(relativePath));
  }

  @Test
  @DisplayName("marks the version signed without verifying the signature again (RPS-1186)")
  void markSignedWithoutVerifyingAgain() {
    final var id = UUID.randomUUID();
    final var repo = new Repo();
    repo.setId(id);
    repo.setName("mvn");
    when(this.repoRepository.findByNameAndType("mvn", RepoType.MAVEN))
        .thenReturn(Optional.of(repo));
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        new ByteArrayResource(new byte[0]));

    assertThat(version.isSigned()).isTrue();
    verify(this.artifactVersionRepository).save(version);
    verifyNoInteractions(this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("answers itemNotFound for a POM signature whose version is not registered")
  void markSignedAnswersItemNotFoundForANonLayoutPath() {
    final var id = UUID.randomUUID();
    final var repo = new Repo();
    repo.setId(id);
    repo.setName("mvn");
    when(this.repoRepository.findByNameAndType("mvn", RepoType.MAVEN))
        .thenReturn(Optional.of(repo));

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repo(id, true, true, true),
                    StoragePath.of(id, "io/stray.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");

    verifyNoInteractions(this.pgpVerifierService);
  }

  @Test
  @DisplayName("verifies a signature against the stored POM with the hosts of the repo key store")
  void verifiesTheSignatureAgainstTheStoredPom() {
    final var id = UUID.randomUUID();
    final var pom = new ByteArrayResource("<project/>".getBytes(StandardCharsets.UTF_8));
    final var signature = new ByteArrayResource("signature".getBytes(StandardCharsets.UTF_8));
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.pom"), eq("mvn")))
        .thenReturn(Optional.of(pom));
    this.stubVersion(this.stubArtifact(id), "1.0", true);
    when(this.keyStoreService.findHostsByRepoId(id)).thenReturn(List.of("keys.acme.com"));

    this.artifactService.verifySignature(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        signature);

    verify(this.pgpVerifierService).verify(pom, signature, List.of("keys.acme.com"));
  }

  @Test
  @DisplayName("lets a refused signature through as a SignatureNotVerifiedException, unchanged")
  void aRefusedSignatureIsNotSwallowed() {
    final var id = UUID.randomUUID();
    final Resource pom = new ByteArrayResource(new byte[0]);
    final Resource signature = new ByteArrayResource(new byte[0]);
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.pom"), eq("mvn")))
        .thenReturn(Optional.of(pom));
    this.stubVersion(this.stubArtifact(id), "1.0", true);
    when(this.keyStoreService.findHostsByRepoId(id)).thenReturn(List.of());
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(pom, signature, List.of());

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
                    signature))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("artifactSignatureNotVerified");
  }

  @Test
  @DisplayName("answers itemNotFound when the POM a signature belongs to is not stored (RPS-1186)")
  void verifySignatureAnswersItemNotFoundWhenThePomIsNotStored() {
    final var id = UUID.randomUUID();
    when(this.storageStrategy.get(any(StoragePath.class), any(String.class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");

    verifyNoInteractions(this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("refuses a signature of a stored POM whose artifact is not registered (RPS-1191)")
  void verifySignatureAnswersArtifactVersionNotFoundWhenTheArtifactIsNotRegistered() {
    final var id = UUID.randomUUID();
    this.stubStoredPom("com/acme/lib/1.0/lib-1.0.pom");
    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(id, "com.acme", "lib"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");

    verifyNoInteractions(this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("refuses a signature of a stored POM whose version is not registered (RPS-1191)")
  void verifySignatureAnswersArtifactVersionNotFoundWhenTheVersionIsNotRegistered() {
    final var id = UUID.randomUUID();
    this.stubStoredPom("com/acme/lib/1.0/lib-1.0.pom");
    this.stubVersion(this.stubArtifact(id), "1.0", false);

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");

    verifyNoInteractions(this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("looks up the version of a snapshot POM signature under its base version")
  void verifySignatureLooksUpASnapshotUnderItsBaseVersion() {
    final var id = UUID.randomUUID();
    final var pom = this.stubStoredPom("com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom");
    final var signature = new ByteArrayResource(new byte[0]);
    this.stubVersion(this.stubArtifact(id), "1.0-SNAPSHOT", true);
    when(this.keyStoreService.findHostsByRepoId(id)).thenReturn(List.of());

    this.artifactService.verifySignature(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.asc"),
        signature);

    verify(this.pgpVerifierService).verify(pom, signature, List.of());
  }

  @Test
  @DisplayName("stores no signature mark for a version that is not registered (RPS-1191)")
  void markSignedAnswersArtifactVersionNotFoundWhenTheVersionIsNotRegistered() {
    final var id = UUID.randomUUID();
    final var repo = new Repo();
    repo.setId(id);
    repo.setName("mvn");
    when(this.repoRepository.findByNameAndType("mvn", RepoType.MAVEN))
        .thenReturn(Optional.of(repo));
    this.stubVersion(this.stubArtifact(id), "1.0", false);

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");

    verify(this.artifactVersionRepository, never()).save(any());
  }

  private static final String POM_OF_GROUP =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        %s
        <artifactId>lib</artifactId>
        <version>1.0</version>
      </project>
      """;

  private Repo stubRepo(final UUID id) {
    final var repo = new Repo();
    repo.setId(id);
    repo.setName("mvn");
    repo.setReleases(true);
    repo.setSnapshots(true);
    when(this.repoRepository.findByNameAndType("mvn", RepoType.MAVEN))
        .thenReturn(Optional.of(repo));

    return repo;
  }

  @ParameterizedTest(name = "a stored POM declaring {0} under com/acme is not registered")
  @ValueSource(
      strings = {
        "<groupId>org.other</groupId>",
        "<groupId>com.Acme</groupId>",
        "<parent><groupId>org.other</groupId><artifactId>par</artifactId><version>1</version>"
            + "</parent>"
      })
  @DisplayName("skips a stored POM whose groupId is not the one of its path (RPS-1193)")
  void createOrUpdateArtifactSkipsAStoredPomOfAnotherGroup(final String groupElements) {
    final var id = UUID.randomUUID();
    this.stubRepo(id);

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
        new ByteArrayResource(
            POM_OF_GROUP.formatted(groupElements).getBytes(StandardCharsets.UTF_8)));

    verifyNoInteractions(this.artifactUpsertHelper, this.artifactRepository);
  }

  @ParameterizedTest(
      name = "a stored POM declaring {0} under com/acme is registered as com.acme:lib")
  @ValueSource(
      strings = {
        "<groupId>com.acme</groupId>",
        "<parent><groupId>com.acme</groupId><artifactId>par</artifactId><version>1</version>"
            + "</parent>",
        ""
      })
  @DisplayName("registers a stored POM whose own or inherited groupId is the one of its path")
  void createOrUpdateArtifactRegistersAPomWithAnInheritedGroupId(final String groupElements) {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
        new ByteArrayResource(
            POM_OF_GROUP.formatted(groupElements).getBytes(StandardCharsets.UTF_8)));

    verify(this.artifactUpsertHelper)
        .insertArtifact(
            argThat(
                artifact ->
                    "com.acme".equals(artifact.getGroupName())
                        && "lib".equals(artifact.getArtifactName())));
    verify(this.artifactUpsertHelper)
        .insertArtifactVersion(
            argThat(version -> "1.0".equals(version.getVersionName())), any(), any());
  }

  private Resource stubStoredPom(final String relativePath) {
    final Resource pom = new ByteArrayResource("<project/>".getBytes(StandardCharsets.UTF_8));
    when(this.storageStrategy.get(pathOf(relativePath), eq("mvn"))).thenReturn(Optional.of(pom));

    return pom;
  }
}
