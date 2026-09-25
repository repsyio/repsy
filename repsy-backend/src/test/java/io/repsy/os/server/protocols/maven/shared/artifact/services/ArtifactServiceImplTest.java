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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactConverter;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.PublicKeySources;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
 *
 * <p>The {@code .asc} signature of a metadata file is classified like a metadata checksum: never
 * parsed, judged by its directory, and neither verified nor registered (RPS-1185). Before, it was
 * parsed as XML and refused with {@code malformedMetadataFile}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
    "Maven ArtifactServiceImpl version-type rules (RPS-1174, RPS-1176, RPS-1182, RPS-1183,"
        + " RPS-1185)")
class ArtifactServiceImplTest {

  private static final String SNAPSHOT_JAR =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar";
  private static final String RELEASE_JAR = "com/acme/lib/1.0/lib-1.0.jar";
  private static final String ARTIFACT_METADATA = "com/acme/lib/maven-metadata.xml";
  private static final String GROUP_METADATA_PATH = "com/acme/maven-metadata.xml";
  private static final String SNAPSHOT_VERSION_METADATA =
      "com/acme/lib/1.0-SNAPSHOT/maven-metadata.xml";
  private static final String RELEASE_VERSION_METADATA = "com/acme/lib/1.0/maven-metadata.xml";
  private static final String ARMORED_SIGNATURE =
      "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n";

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
  @Mock VersionSignatureService versionSignatureService;
  @Mock PendingSignatureService pendingSignatureService;
  @Mock PendingSignatureRepository pendingSignatureRepository;
  @Mock StorageStrategy storageStrategy;

  @InjectMocks ArtifactServiceImpl artifactService;

  /**
   * The setting as it is committed once the version is locked (RPS-1323): on unless a test says
   * otherwise, as almost all the tests here are about a repo that verifies every signature.
   */
  @BeforeEach
  void theSettingReadUnderTheLockIsOn() {
    lenient().when(this.versionSignatureService.lockAndIsVerifyAll(any())).thenReturn(true);
  }

  private static RepoInfo repoVerifyingAllSignatures(final UUID id) {
    return RepoInfo.builder()
        .id(id)
        .storageKey(id)
        .name("mvn")
        .releases(true)
        .snapshots(true)
        .allowOverride(true)
        .pgpVerifyAllSignaturesEnabled(true)
        .build();
  }

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
  @DisplayName("classifying an upload reads the path only, it queries no artifact or version")
  void classificationQueriesNoArtifactOrVersion() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, true, true);

    assertThat(this.artifactService.getVersionType(repo, StoragePath.of(id, SNAPSHOT_JAR)))
        .isEqualTo(SNAPSHOT);
    assertThat(this.artifactService.getVersionType(repo, StoragePath.of(id, RELEASE_JAR)))
        .isEqualTo(RELEASE);
    assertThat(
            this.artifactService.getVersionType(
                repo, StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc")))
        .isEqualTo(RELEASE);

    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @ParameterizedTest(name = "{0} is refused with both kinds off")
  @ValueSource(strings = {RELEASE_JAR, SNAPSHOT_JAR, "com/acme/lib/1.0/lib-1.0.pom"})
  @DisplayName("a repo with releases and snapshots both off refuses every version (RPS-1181)")
  void bothKindsOffRefusesEveryVersion(final String path) {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);
    final var storagePath = StoragePath.of(id, path);
    final var versionType = this.artifactService.getVersionType(repo, storagePath);
    final var expected =
        versionType == SNAPSHOT ? "snapshotVersionsAreProhibited" : "releaseVersionsAreProhibited";

    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(repo, versionType, storagePath))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage(expected);
  }

  @Test
  @DisplayName("a snapshot redeploy is refused while snapshots are off")
  void refusesASnapshotRedeployWhenSnapshotsAreOff() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, false, true);
    final var path = StoragePath.of(id, SNAPSHOT_JAR);

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, SNAPSHOT, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
  }

  @Test
  @DisplayName("a release redeploy is refused while releases are off")
  void refusesAReleaseRedeployWhenReleasesAreOff() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, true, true);
    final var path = StoragePath.of(id, RELEASE_JAR);

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, RELEASE, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");
  }

  @Test
  @DisplayName("a redeploy is allowed while its kind is on")
  void allowsARedeployWhileItsKindIsOn() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, true, true);
    final var path = StoragePath.of(id, SNAPSHOT_JAR);

    assertThatCode(() -> this.artifactService.checkDeploymentRules(repo, SNAPSHOT, path))
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

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, SNAPSHOT, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("artifactOverrideIsProhibited");
  }

  private @Nullable ArtifactVersionType classify(
      final RepoInfo repo, final String path, final String body) throws Exception {
    return this.artifactService.getVersionTypeByMetadataTypeFiles(
        repo, body.getBytes(StandardCharsets.UTF_8), StoragePath.of(repo.getId(), path));
  }

  @Test
  @DisplayName("plugin metadata is classified as a plugin and no version-type rule applies to it")
  void pluginMetadataIsNotSubjectToTheRule() throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, GROUP_METADATA_PATH, GROUP_METADATA);

    assertThat(result).isEqualTo(PLUGIN);
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

    assertThat(result).isEqualTo(SNAPSHOT);
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

    assertThat(result).isEqualTo(RELEASE);
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

    assertThat(result).isNull();
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

    assertThat(result).isNull();
  }

  @Test
  @DisplayName(
      "a version-level snapshot metadata checksum is judged as a snapshot by its directory")
  void versionLevelMetadataChecksumIsJudgedAsASnapshotByItsDirectory() throws Exception {
    final var id = UUID.randomUUID();
    final var path = StoragePath.of(id, SNAPSHOT_VERSION_METADATA + ".sha1");

    final var refusing = repo(id, true, false, true);
    final var result = this.classify(refusing, SNAPSHOT_VERSION_METADATA + ".sha1", "garbage");

    assertThat(result).isEqualTo(SNAPSHOT);
    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(refusing, result, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(repo(id, true, true, true), result, path))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an artifact-level metadata signature is stored without parsing the file (RPS-1185)")
  void metadataSignatureIsStoredWithoutParsing() throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, ARTIFACT_METADATA + ".asc", ARMORED_SIGNATURE);

    assertThat(result).isNull();
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo, result, StoragePath.of(id, ARTIFACT_METADATA + ".asc")))
        .doesNotThrowAnyException();
    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @Test
  @DisplayName(
      "a version-level snapshot metadata signature is judged as a snapshot by its directory"
          + " (RPS-1185)")
  void versionLevelMetadataSignatureIsJudgedAsASnapshotByItsDirectory() throws Exception {
    final var id = UUID.randomUUID();
    final var path = StoragePath.of(id, SNAPSHOT_VERSION_METADATA + ".asc");

    final var refusing = repo(id, true, false, true);
    final var result =
        this.classify(refusing, SNAPSHOT_VERSION_METADATA + ".asc", ARMORED_SIGNATURE);

    assertThat(result).isEqualTo(SNAPSHOT);
    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(refusing, result, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(repo(id, true, true, true), result, path))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a metadata signature is neither verified nor registered (RPS-1185)")
  void metadataSignatureIsNeitherVerifiedNorRegistered() {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, SNAPSHOT_VERSION_METADATA + ".asc"),
        new ByteArrayResource(ARMORED_SIGNATURE.getBytes(StandardCharsets.UTF_8)));

    verifyNoInteractions(
        this.repoRepository,
        this.pgpVerifierService,
        this.keyStoreService,
        this.artifactRepository,
        this.artifactVersionRepository,
        this.artifactUpsertHelper);
  }

  @ParameterizedTest(name = "{0} is not judged")
  @ValueSource(
      strings = {
        "com/acme/maven-metadata.xml.sha1",
        "com/acme/lib/maven-metadata.xml.sha1",
        "com/acme/lib/maven-metadata.xml.md5",
        "com/acme/lib/1.0/maven-metadata.xml.sha1",
        "com/acme/maven-metadata.xml.asc",
        "com/acme/lib/maven-metadata.xml.asc",
        "com/acme/lib/1.0/maven-metadata.xml.asc",
        "com/acme/lib/maven-metadata.xml.asc.sha1"
      })
  @DisplayName(
      "the other metadata checksums and signatures are not judged, the file being a hash or armored"
          + " text (RPS-1183, RPS-1185)")
  void otherMetadataChecksumsAreNotJudged(final String path) throws Exception {
    final var id = UUID.randomUUID();
    final var repo = repo(id, false, false, true);

    final var result = this.classify(repo, path, "garbage");

    assertThat(result).isNull();
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
                    repo(id, true, false, true), SNAPSHOT, noGav))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
    assertThatCode(
            () ->
                this.artifactService.checkDeploymentRules(
                    repo(id, true, true, true), SNAPSHOT, noGav))
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
                    SNAPSHOT,
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
                this.artifactService.getVersionType(
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
        this.artifactService.getVersionType(repo(id, true, true, true), StoragePath.of(id, path));

    assertThat(result).isEqualTo(versionType);
  }

  @Test
  @DisplayName("a checksum of a kind that is switched off is refused like the file it belongs to")
  void checksumOfARefusedKindIsRefusedByTheVersionTypeRule() {
    final var id = UUID.randomUUID();
    final var releasesOff = repo(id, false, true, true);
    final var release = StoragePath.of(id, "com/acme/lib/3.5/lib-3.5.jar.sha1");

    final var releaseResult = this.artifactService.getVersionType(releasesOff, release);

    assertThat(releaseResult).isEqualTo(RELEASE);
    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(releasesOff, releaseResult, release))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("releaseVersionsAreProhibited");

    final var snapshotsOff = repo(id, true, false, true);
    final var snapshot =
        StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.sha1");

    final var snapshotResult = this.artifactService.getVersionType(snapshotsOff, snapshot);

    assertThat(snapshotResult).isEqualTo(SNAPSHOT);
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
    final var versionType = this.artifactService.getVersionType(repo, path);

    assertThatCode(() -> this.artifactService.checkDeploymentRules(repo, versionType, path))
        .doesNotThrowAnyException();
    verify(this.artifactRepository, never())
        .existsByRepoIdAndArtifactNameAndGroupNameAndArtifactVersionsVersionName(
            any(), any(), any(), any());

    when(this.storageStrategy.get(path, "mvn"))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, versionType, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("artifactOverrideIsProhibited");
  }

  @ParameterizedTest(name = "{0} may be deployed again")
  @ValueSource(
      strings = {
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT-sources.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom.sha1",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar.asc"
      })
  @DisplayName("a literal snapshot file is no override, whatever is stored (RPS-1328)")
  void literalSnapshotRedeployIsNotAnOverride(final String path) {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, true, false);
    final var storagePath = StoragePath.of(id, path);
    final var versionType = this.artifactService.getVersionType(repo, storagePath);

    assertThat(versionType).isEqualTo(SNAPSHOT);
    assertThatCode(() -> this.artifactService.checkDeploymentRules(repo, versionType, storagePath))
        .doesNotThrowAnyException();

    verifyNoInteractions(this.storageStrategy, this.artifactRepository);
  }

  @Test
  @DisplayName("a literal snapshot is still refused while snapshots are off (RPS-1328, RPS-1174)")
  void literalSnapshotIsStillRefusedWhileSnapshotsAreOff() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, false, false);
    final var path = StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar");

    assertThatThrownBy(() -> this.artifactService.checkDeploymentRules(repo, SNAPSHOT, path))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("snapshotVersionsAreProhibited");
  }

  @ParameterizedTest(name = "{0} is still an override")
  @ValueSource(
      strings = {
        SNAPSHOT_JAR,
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar.sha1",
        RELEASE_JAR,
        "com/acme/lib/1.0/lib-1.0.pom"
      })
  @DisplayName("a stored timestamped build and a stored release stay immutable (RPS-1328)")
  void timestampedBuildAndReleaseStayImmutable(final String path) {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, true, false);
    final var storagePath = StoragePath.of(id, path);
    final var versionType = this.artifactService.getVersionType(repo, storagePath);
    when(this.storageStrategy.get(storagePath, "mvn"))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[] {1})));

    assertThatThrownBy(
            () -> this.artifactService.checkDeploymentRules(repo, versionType, storagePath))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("artifactOverrideIsProhibited");
  }

  @Test
  @DisplayName("a registered snapshot version does not refuse its literal POM (RPS-1328)")
  void registeredSnapshotVersionDoesNotRefuseItsLiteralPom() {
    final var id = UUID.randomUUID();
    final var repo = repo(id, true, true, false);
    final var path = StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom");

    assertThatCode(() -> this.artifactService.checkDeploymentRules(repo, SNAPSHOT, path))
        .doesNotThrowAnyException();

    verify(this.artifactRepository, never())
        .existsByRepoIdAndArtifactNameAndGroupNameAndArtifactVersionsVersionName(
            any(), any(), any(), any());
  }

  private static StorageItemInfo item(final String name, final boolean directory) {
    return StorageItemInfo.builder().name(name).directory(directory).build();
  }

  private String pomFilenameOf(final String versionName) throws Exception {
    return this.artifactService.getArtifactVersionPomFilename(
        repo(UUID.randomUUID(), true, true, true),
        Path.of("/com.acme/lib"),
        SNAPSHOT,
        "lib",
        versionName);
  }

  private void stubVersionDir(final String... names) {
    when(this.storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenReturn(Arrays.stream(names).map(name -> item(name, name.endsWith("/"))).toList());
  }

  private void stubNoVersionMetadata() {
    when(this.storageStrategy.get(any(StoragePath.class), eq("mvn"))).thenReturn(Optional.empty());
  }

  private void stubVersionMetadata(final String xml) {
    when(this.storageStrategy.get(any(StoragePath.class), eq("mvn")))
        .thenReturn(Optional.of(new ByteArrayResource(xml.getBytes(UTF_8))));
  }

  @Test
  @DisplayName("without version metadata the literal POM of a snapshot is the POM (RPS-1370)")
  void snapshotWithoutMetadataUsesItsLiteralPom() throws Exception {
    this.stubNoVersionMetadata();
    this.stubVersionDir(
        "lib-2.0-SNAPSHOT.jar", "lib-2.0-SNAPSHOT.pom", "lib-2.0-SNAPSHOT.pom.sha1");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("without version metadata the newest timestamped POM wins over the others")
  void snapshotWithoutMetadataUsesTheNewestBuild() throws Exception {
    this.stubNoVersionMetadata();
    this.stubVersionDir(
        "lib-2.0-SNAPSHOT.pom",
        "lib-2.0-20260921.101010-1.pom",
        "lib-2.0-20260921.101010-2.pom",
        "lib-2.0-20260920.235959-9.pom");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-20260921.101010-2.pom");
  }

  @Test
  @DisplayName(
      "without version metadata and without a POM the literal name is answered (404 on read)")
  void snapshotWithoutMetadataAndPomAnswersTheLiteralName() throws Exception {
    this.stubNoVersionMetadata();
    this.stubVersionDir("lib-2.0-SNAPSHOT.jar", "sub/");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("a version directory that is gone answers the literal name, it does not throw")
  void snapshotWithoutMetadataInAMissingDirectory() throws Exception {
    this.stubNoVersionMetadata();
    when(this.storageStrategy.listDirectoryContents(any(StoragePath.class)))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("version metadata that lists a pom still decides, as a Maven client resolves it")
  void snapshotMetadataStillDecides() throws Exception {
    this.stubVersionMetadata(VERSION_METADATA.formatted("1.0-SNAPSHOT"));

    assertThat(this.pomFilenameOf("1.0-SNAPSHOT")).isEqualTo("lib-1.0-20260921.101010-1.pom");

    verify(this.storageStrategy, never()).listDirectoryContents(any(StoragePath.class));
  }

  @Test
  @DisplayName("metadata without versioning falls back to the stored POM instead of failing")
  void snapshotMetadataWithoutVersioningFallsBack() throws Exception {
    this.stubVersionMetadata(
        "<metadata><groupId>com.acme</groupId><artifactId>lib</artifactId></metadata>");
    this.stubVersionDir("lib-2.0-SNAPSHOT.pom");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-SNAPSHOT.pom");
  }

  @Test
  @DisplayName("metadata without a pom entry falls back to the stored POM, or to no POM at all")
  void snapshotMetadataWithoutPomEntryFallsBack() throws Exception {
    this.stubVersionMetadata(
        """
        <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
        <snapshot><timestamp>20260921.101010</timestamp><buildNumber>3</buildNumber></snapshot>\
        <snapshotVersions><snapshotVersion><extension>jar</extension>\
        <value>2.0-20260921.101010-3</value></snapshotVersion></snapshotVersions>\
        </versioning></metadata>""");
    this.stubVersionDir("lib-2.0-20260921.101010-3.pom", "lib-2.0-20260921.101010-3.jar");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-20260921.101010-3.pom");

    this.stubVersionDir("lib-2.0-20260921.101010-3.jar");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isNull();
  }

  @Test
  @DisplayName(
      "metadata that cannot be parsed is treated like absent metadata, it is not a 400 (RPS-1421)")
  void snapshotMetadataThatCannotBeParsedFallsBack() throws Exception {
    this.stubVersionMetadata("<metadata><versioning>");
    this.stubVersionDir("lib-2.0-20260921.101010-3.pom", "lib-2.0-SNAPSHOT.pom");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-20260921.101010-3.pom");

    // no POM stored: the literal name, as for absent metadata (the read then answers 404), not the
    // null of metadata that parsed and lists no pom
    this.stubVersionDir("lib-2.0-SNAPSHOT.jar");

    assertThat(this.pomFilenameOf("2.0-SNAPSHOT")).isEqualTo("lib-2.0-SNAPSHOT.pom");
  }

  private static StoragePath pathOf(final String relativePath) {
    return argThat(path -> path.getRelativePath().getPath().equals(relativePath));
  }

  /** Like {@link #pathOf}, for a second stubbing of the same method: it is handed a null then. */
  private static StoragePath pathOfNullSafe(final String relativePath) {
    return argThat(path -> path != null && path.getRelativePath().getPath().equals(relativePath));
  }

  @Test
  @DisplayName("marks the version signed without verifying the signature again (RPS-1186)")
  void markSignedWithoutVerifyingAgain() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.versionSignatureService.lockAndIsVerifyAll(version)).thenReturn(false);

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        new ByteArrayResource(new byte[0]));

    // Signed by the POM rule of the setting, from the row that was just recorded.
    verify(this.versionSignatureService).recordVerified(version, "lib-1.0.pom");
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", false);
    verify(this.artifactVersionRepository, never()).save(any());
    verifyNoInteractions(this.repoRepository, this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("a signature request reads the setting under the version's lock, not at its start")
  void aSignatureRequestReadsTheSettingAfterTheLock() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    // repoInfo, read when the request started, says on; a toggle committed since.
    when(this.versionSignatureService.lockAndIsVerifyAll(version)).thenReturn(false);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0-sources.jar.asc"),
        new ByteArrayResource(new byte[0]));

    final var order = inOrder(this.pendingSignatureService, this.versionSignatureService);
    order.verify(this.pendingSignatureService).claim(id, "com/acme/lib/1.0/lib-1.0-sources.jar");
    order.verify(this.versionSignatureService).lockAndIsVerifyAll(version);
    order.verify(this.versionSignatureService).recordVerified(version, "lib-1.0-sources.jar");
    order
        .verify(this.versionSignatureService)
        .refreshSigned(id, version, "com/acme/lib/1.0", false);
  }

  @Test
  @DisplayName("a POM signature of a repo that was toggled on since the request began is by all")
  void aPomSignatureIsRecomputedByTheSettingThatIsCommitted() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    // repoInfo says off; the toggle committed after it was read.
    when(this.versionSignatureService.lockAndIsVerifyAll(version)).thenReturn(true);

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
    verify(this.artifactVersionRepository, never()).save(any());
  }

  @Test
  @DisplayName("a stored file of a repo that was toggled off since the request began is not judged")
  void aStoredFileIsNotForgottenWhenTheSettingIsOffByNow() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.versionSignatureService.lockAndIsVerifyAll(version)).thenReturn(false);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0-sources.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService, never()).forget(any(), any());
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", false);
  }

  @Test
  @DisplayName("stores an artifact signature as sent, touching no row, when not verifying all")
  void anArtifactSignatureIsIgnoredWhenNotVerifyingAll() {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
        new ByteArrayResource(new byte[0]));
    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verifyNoInteractions(
        this.artifactRepository,
        this.artifactVersionRepository,
        this.versionSignatureService,
        this.storageStrategy,
        this.repoRepository);
  }

  @Test
  @DisplayName("records an artifact signature and recomputes signed when verifying all (RPS-1188)")
  void anArtifactSignatureIsRecordedWhenVerifyingAll() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0-sources.jar.asc"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService).recordVerified(version, "lib-1.0-sources.jar");
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
    verify(this.artifactVersionRepository, never()).save(any());
  }

  @Test
  @DisplayName("a POM signature recomputes signed instead of setting it when verifying all")
  void aPomSignatureRecomputesSignedWhenVerifyingAll() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        new ByteArrayResource(new byte[0]));

    assertThat(version.isSigned()).isFalse();
    verify(this.versionSignatureService).recordVerified(version, "lib-1.0.pom");
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
  }

  @Test
  @DisplayName("a signable file stored again loses its verified signature when verifying all")
  void aStoredSignableFileForgetsItsSignatureWhenVerifyingAll() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0-sources.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService).forget(version, "lib-1.0-sources.jar");
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
  }

  @Test
  @DisplayName(
      "a signature recorded for the stored bytes while the file was being registered is kept")
  void aRecordedSignatureThatStillVerifiesIsKept() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.versionSignatureService.isRecorded(version, "lib-1.0.jar")).thenReturn(true);
    final var jar = new ByteArrayResource("jar".getBytes(UTF_8));
    final var signature = new ByteArrayResource(ARMORED_SIGNATURE.getBytes(UTF_8));
    when(this.storageStrategy.get(pathOfNullSafe("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.of(jar));
    when(this.storageStrategy.get(pathOfNullSafe("com/acme/lib/1.0/lib-1.0.jar.asc"), eq("mvn")))
        .thenReturn(Optional.of(signature));
    final var sources = new PublicKeySources(List.of(), List.of(), true);
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.pgpVerifierService).verify(jar, signature, sources);
    verify(this.versionSignatureService, never()).forget(any(), any());
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
  }

  @Test
  @DisplayName("a recorded signature that no longer verifies the stored bytes is forgotten")
  void aRecordedSignatureThatDoesNotVerifyIsForgotten() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.versionSignatureService.isRecorded(version, "lib-1.0.jar")).thenReturn(true);
    final var jar = new ByteArrayResource("another jar".getBytes(UTF_8));
    final var signature = new ByteArrayResource(ARMORED_SIGNATURE.getBytes(UTF_8));
    when(this.storageStrategy.get(pathOfNullSafe("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.of(jar));
    when(this.storageStrategy.get(pathOfNullSafe("com/acme/lib/1.0/lib-1.0.jar.asc"), eq("mvn")))
        .thenReturn(Optional.of(signature));
    final var sources = new PublicKeySources(List.of(), List.of(), true);
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(jar, signature, sources);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService).forget(version, "lib-1.0.jar");
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
  }

  @Test
  @DisplayName("a recorded signature whose file or signature is gone from storage is forgotten")
  void aRecordedSignatureOfAMissingFileIsForgotten() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.versionSignatureService.isRecorded(version, "lib-1.0.jar")).thenReturn(true);
    when(this.storageStrategy.get(any(StoragePath.class), eq("mvn"))).thenReturn(Optional.empty());

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService).forget(version, "lib-1.0.jar");
    verifyNoInteractions(this.pgpVerifierService);
  }

  @Test
  @DisplayName("a checksum, or a file of a version that is not registered yet, changes nothing")
  void aChecksumOrAnUnregisteredVersionChangesNothingWhenVerifyingAll() {
    final var id = UUID.randomUUID();
    this.stubVersion(this.stubArtifact(id), "1.0", false);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.sha1"),
        new ByteArrayResource(new byte[0]));
    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verifyNoInteractions(this.versionSignatureService);
  }

  @Test
  @DisplayName("answers itemNotFound for a POM signature whose version is not registered")
  void markSignedAnswersItemNotFoundForANonLayoutPath() {
    final var id = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repo(id, true, true, true),
                    StoragePath.of(id, "io/stray.pom.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");

    verifyNoInteractions(this.repoRepository, this.pgpVerifierService);
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
    final var sources = new PublicKeySources(List.of(), List.of("keys.acme.com"), true);
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);

    this.artifactService.verifySignature(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom.asc"),
        signature);

    verify(this.pgpVerifierService).verify(pom, signature, sources);
  }

  @Test
  @DisplayName("verifies a jar signature against the stored jar and passes the lookup switch on")
  void verifiesAnArtifactSignatureWithTheLookupSwitchOfTheRepo() {
    final var id = UUID.randomUUID();
    final var jar = new ByteArrayResource("jar".getBytes(StandardCharsets.UTF_8));
    final var signature = new ByteArrayResource("signature".getBytes(StandardCharsets.UTF_8));
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.of(jar));
    this.stubVersion(this.stubArtifact(id), "1.0", true);
    final var sources = new PublicKeySources(List.of(), List.of(), false);
    when(this.keyStoreService.findPublicKeySources(id, false)).thenReturn(sources);
    final var lookupOff =
        RepoInfo.builder()
            .id(id)
            .storageKey(id)
            .name("mvn")
            .pgpVerifyAllSignaturesEnabled(true)
            .pgpKeyServerLookupEnabled(false)
            .build();

    this.artifactService.verifySignature(
        lookupOff, StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"), signature);

    verify(this.pgpVerifierService).verify(jar, signature, sources);
  }

  @Test
  @DisplayName("parks a jar signature whose version is not registered yet when verifying all")
  void aJarSignatureBeforeThePomIsParkedWhenVerifyingAll() {
    final var id = UUID.randomUUID();
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[0])));
    this.stubVersion(this.stubArtifact(id), "1.0", false);

    final var outcome =
        this.artifactService.verifySignature(
            repoVerifyingAllSignatures(id),
            StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
            new ByteArrayResource("sig".getBytes(UTF_8)));

    assertThat(outcome).isEqualTo(SignatureOutcome.PARKED);
    verify(this.pendingSignatureService)
        .park(id, "com/acme/lib/1.0/lib-1.0.jar", "sig".getBytes(UTF_8));
    verifyNoInteractions(this.pgpVerifierService, this.keyStoreService);
  }

  @Test
  @DisplayName("without verify-all a jar signature before its POM is still refused")
  void aJarSignatureBeforeThePomIsRefusedWhenNotVerifyingAll() {
    final var id = UUID.randomUUID();
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[0])));
    this.stubVersion(this.stubArtifact(id), "1.0", false);

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");

    verifyNoInteractions(
        this.pgpVerifierService, this.keyStoreService, this.pendingSignatureService);
  }

  @Test
  @DisplayName(
      "parks a signature whose file is not stored yet when verifying all, refuses it if not")
  void aSignatureBeforeItsFileIsParkedWhenVerifyingAllAndRefusedOtherwise() {
    final var id = UUID.randomUUID();
    when(this.storageStrategy.get(any(StoragePath.class), any(String.class)))
        .thenReturn(Optional.empty());
    final var path = StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc");
    final var signature = new ByteArrayResource("sig".getBytes(UTF_8));

    assertThat(
            this.artifactService.verifySignature(repoVerifyingAllSignatures(id), path, signature))
        .isEqualTo(SignatureOutcome.PARKED);
    verify(this.pendingSignatureService)
        .park(id, "com/acme/lib/1.0/lib-1.0.jar", "sig".getBytes(UTF_8));

    assertThatThrownBy(
            () -> this.artifactService.verifySignature(repo(id, true, true, true), path, signature))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
    verify(this.pendingSignatureService, org.mockito.Mockito.times(1)).park(any(), any(), any());
  }

  @Test
  @DisplayName("a parked signature is verified at once when the file and the version showed up")
  void aParkedSignatureIsVerifiedWhenTheFileLandedMeanwhile() {
    final var id = UUID.randomUUID();
    final var jar = new ByteArrayResource("jar".getBytes(UTF_8));
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.empty(), Optional.of(jar));
    final var artifact = this.stubArtifact(id);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(new ArtifactVersion()));
    final var sources = PublicKeySources.none();
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);
    final var signature = new ByteArrayResource("sig".getBytes(UTF_8));

    final var outcome =
        this.artifactService.verifySignature(
            repoVerifyingAllSignatures(id),
            StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
            signature);

    assertThat(outcome).isEqualTo(SignatureOutcome.VERIFIED);
    verify(this.pendingSignatureService).park(any(), any(), any());
    verify(this.pgpVerifierService).verify(jar, signature, sources);
    verify(this.pendingSignatureService, never()).discard(any(), any());
  }

  @Test
  @DisplayName("a parked signature that does not verify in that second look is dropped again")
  void aParkedSignatureThatFailsTheSecondLookIsDiscarded() {
    final var id = UUID.randomUUID();
    final var jar = new ByteArrayResource("jar".getBytes(UTF_8));
    when(this.storageStrategy.get(pathOf("com/acme/lib/1.0/lib-1.0.jar"), eq("mvn")))
        .thenReturn(Optional.empty(), Optional.of(jar));
    final var artifact = this.stubArtifact(id);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(new ArtifactVersion()));
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(PublicKeySources.none());
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(any(), any(), any());

    assertThatThrownBy(
            () ->
                this.artifactService.verifySignature(
                    repoVerifyingAllSignatures(id),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
                    new ByteArrayResource("sig".getBytes(UTF_8))))
        .isInstanceOf(SignatureNotVerifiedException.class);

    verify(this.pendingSignatureService).discard(id, "com/acme/lib/1.0/lib-1.0.jar");
  }

  @Test
  @DisplayName("a signature that is stored takes its parked copy out of the way when verifying all")
  void aStoredSignatureClaimsItsParkedCopy() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(new ArtifactVersion()));

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar.asc"),
        new ByteArrayResource(new byte[0]));

    verify(this.pendingSignatureService).claim(id, "com/acme/lib/1.0/lib-1.0.jar");
  }

  @Test
  @DisplayName("a stored file whose signature was recorded from the parked one keeps that record")
  void aFileWithAReconciledSignatureIsNotForgotten() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.pendingSignatureService.reconcileFile(any(), eq("com/acme/lib/1.0/lib-1.0.jar")))
        .thenReturn(true);

    this.artifactService.createOrUpdateArtifact(
        repoVerifyingAllSignatures(id),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verify(this.versionSignatureService, never()).forget(any(), any());
    verify(this.versionSignatureService).refreshSigned(id, version, "com/acme/lib/1.0", true);
  }

  @Test
  @DisplayName("a bad parked signature fails the file's upload before anything else is recomputed")
  void aBadParkedSignatureFailsTheFile() {
    final var id = UUID.randomUUID();
    when(this.pendingSignatureService.reconcileFile(any(), any()))
        .thenThrow(new SignatureNotVerifiedException("pendingSignatureNotVerified"));

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repoVerifyingAllSignatures(id),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
                    new ByteArrayResource(new byte[0])))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage("pendingSignatureNotVerified");

    verifyNoInteractions(this.versionSignatureService);
  }

  @Test
  @DisplayName(
      "a POM is checked against the parked signatures before, and reconciled after, it registers")
  void aPomChecksTheParkedSignaturesAroundItsRegistration() {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    final var repoInfo = repoVerifyingAllSignatures(id);
    when(this.pendingSignatureService.reconcileDirectory(repoInfo, "com/acme/lib/1.0"))
        .thenReturn(java.util.Set.of("com/acme/lib/1.0/lib-1.0.pom"));

    this.artifactService.createOrUpdateArtifact(
        repoInfo,
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
        new ByteArrayResource(POM_OF_GROUP.formatted("").getBytes(UTF_8)));

    final var order =
        org.mockito.Mockito.inOrder(this.pendingSignatureService, this.artifactUpsertHelper);
    order.verify(this.pendingSignatureService).verifyDirectory(repoInfo, "com/acme/lib/1.0");
    order.verify(this.artifactUpsertHelper).insertArtifactVersion(any(), any(), any());
    order.verify(this.pendingSignatureService).reconcileDirectory(repoInfo, "com/acme/lib/1.0");
    verify(this.versionSignatureService, never()).forget(any(), eq("lib-1.0.pom"));
  }

  @Test
  @DisplayName("a bad parked signature stops the POM before it registers anything")
  void aBadParkedSignatureStopsThePomBeforeItRegisters() {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    final var repoInfo = repoVerifyingAllSignatures(id);
    doThrow(new SignatureNotVerifiedException("pendingSignatureNotVerified"))
        .when(this.pendingSignatureService)
        .verifyDirectory(repoInfo, "com/acme/lib/1.0");

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repoInfo,
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
                    new ByteArrayResource(POM_OF_GROUP.formatted("").getBytes(UTF_8))))
        .isInstanceOf(SignatureNotVerifiedException.class);

    verifyNoInteractions(this.artifactUpsertHelper);
  }

  @Test
  @DisplayName("without verify-all nothing about parked signatures is looked at")
  void withoutVerifyAllNoPendingSignatureIsLookedAt() {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
        new ByteArrayResource(POM_OF_GROUP.formatted("").getBytes(UTF_8)));
    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.jar"),
        new ByteArrayResource(new byte[0]));

    verifyNoInteractions(this.pendingSignatureService);
  }

  @Test
  @DisplayName("deleting a version, an artifact or a group drops the signatures parked under it")
  void deletingDropsTheParkedSignatures() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    artifact.setArtifactName("lib");
    final var version = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(version));
    when(this.artifactRepository.findAllByRepoIdAndGroupName(id, "com.acme"))
        .thenReturn(List.of(artifact));
    final var repo = new Repo();
    repo.setId(id);
    when(this.repoRepository.findById(id)).thenReturn(Optional.of(repo));

    this.artifactService.deleteArtifactVersion(
        repo(id, true, true, true), "com.acme", "lib", "1.0");
    this.artifactService.deleteArtifact(id, "com.acme", "lib");
    this.artifactService.deleteGroup(id, "com.acme");

    verify(this.pendingSignatureRepository)
        .deleteByRepoIdAndSignedFilePathStartingWith(id, "com/acme/lib/1.0/");
    verify(this.pendingSignatureRepository, org.mockito.Mockito.times(2))
        .deleteByRepoIdAndSignedFilePathStartingWith(id, "com/acme/lib/");
  }

  @Test
  @DisplayName(
      "deleting a version recomputes latest and release from the remaining rows (RPS-1331)")
  void deletingAVersionRecomputesLatestAndReleaseFromTheRows() {
    final var id = UUID.randomUUID();
    final var artifact = this.stubArtifact(id);
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "3.0"))
        .thenReturn(Optional.of(new ArtifactVersion()));

    this.artifactService.deleteArtifactVersion(
        repo(id, true, true, true), "com.acme", "lib", "3.0");

    verify(this.artifactVersionWriteService).updateReleaseAndLatestVersion(artifact);
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
    final var sources = PublicKeySources.none();
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);
    doThrow(new SignatureNotVerifiedException("artifactSignatureNotVerified"))
        .when(this.pgpVerifierService)
        .verify(pom, signature, sources);

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
    final var sources = PublicKeySources.none();
    when(this.keyStoreService.findPublicKeySources(id, true)).thenReturn(sources);

    this.artifactService.verifySignature(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom.asc"),
        signature);

    verify(this.pgpVerifierService).verify(pom, signature, sources);
  }

  @Test
  @DisplayName("stores no signature mark for a version that is not registered (RPS-1191)")
  void markSignedAnswersArtifactVersionNotFoundWhenTheVersionIsNotRegistered() {
    final var id = UUID.randomUUID();
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
    verifyNoInteractions(this.repoRepository);
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

  private static final String POM_OF_BAR_POM_UTILS =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.acme</groupId>
        <artifactId>bar.pom.utils</artifactId>
        <version>1.0</version>
      </project>
      """;

  @Test
  @DisplayName("ignores a jar of an artifactId containing \".pom\" (RPS-1196)")
  void createOrUpdateArtifactIgnoresAJarOfAnArtifactIdContainingPom() {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar"),
        new ByteArrayResource(new byte[] {'P', 'K', 3, 4, 0, 0}));

    verifyNoInteractions(
        this.repoRepository,
        this.artifactRepository,
        this.artifactVersionRepository,
        this.artifactUpsertHelper);
  }

  @Test
  @DisplayName("ignores maven-metadata.xml of an artifactId containing \".pom\" (RPS-1196)")
  void createOrUpdateArtifactIgnoresMetadataOfAnArtifactIdContainingPom() {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/bar.pom.utils/maven-metadata.xml"),
        new ByteArrayResource(ARTIFACT_METADATA_MIXED.getBytes(StandardCharsets.UTF_8)));

    verifyNoInteractions(
        this.repoRepository,
        this.artifactRepository,
        this.artifactVersionRepository,
        this.artifactUpsertHelper);
  }

  @Test
  @DisplayName("ignores the metadata signature of an artifactId containing \".pom\" (RPS-1196)")
  void createOrUpdateArtifactIgnoresAMetadataSignatureOfAnArtifactIdContainingPom() {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/bar.pom.utils/maven-metadata.xml.asc"),
        new ByteArrayResource(ARMORED_SIGNATURE.getBytes(StandardCharsets.UTF_8)));

    verify(this.artifactVersionRepository, never()).save(any());
    verifyNoInteractions(this.repoRepository, this.artifactRepository, this.artifactUpsertHelper);
  }

  @Test
  @DisplayName("registers a POM of an artifactId containing \".pom\" (RPS-1196)")
  void createOrUpdateArtifactRegistersAPomOfAnArtifactIdContainingPom() {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom"),
        new ByteArrayResource(POM_OF_BAR_POM_UTILS.getBytes(StandardCharsets.UTF_8)));

    verify(this.artifactUpsertHelper)
        .insertArtifact(
            argThat(
                artifact ->
                    "com.acme".equals(artifact.getGroupName())
                        && "bar.pom.utils".equals(artifact.getArtifactName())));
    verify(this.artifactUpsertHelper)
        .insertArtifactVersion(
            argThat(version -> "1.0".equals(version.getVersionName())), any(), any());
  }

  @ParameterizedTest(name = "{0} does not load the repo row")
  @ValueSource(
      strings = {
        RELEASE_JAR,
        "com/acme/lib/1.0/lib-1.0-sources.jar",
        "com/acme/lib/1.0/lib-1.0.jar.sha1",
        "com/acme/lib/1.0/lib-1.0.pom.sha1",
        "com/acme/lib/1.0/lib-1.0.jar.asc",
        ARTIFACT_METADATA
      })
  @DisplayName("a file that registers nothing does not load the repo row (RPS-1179)")
  void aFileThatRegistersNothingDoesNotLoadTheRepo(final String path) {
    final var id = UUID.randomUUID();

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, path),
        new ByteArrayResource(new byte[] {1}));

    verifyNoInteractions(
        this.repoRepository,
        this.artifactRepository,
        this.artifactVersionRepository,
        this.artifactUpsertHelper);
  }

  @Test
  @DisplayName("a POM still needs its repo row and answers repoNotFound without it (RPS-1179)")
  void aPomOfAMissingRepoAnswersRepoNotFound() {
    final var id = UUID.randomUUID();
    when(this.repoRepository.findByNameAndType("mvn", RepoType.MAVEN)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.artifactService.createOrUpdateArtifact(
                    repo(id, true, true, true),
                    StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
                    new ByteArrayResource(POM_OF_GROUP.formatted("").getBytes(UTF_8))))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("repoNotFound");
  }

  private static StorageItemInfo file(final String physicalPath) {
    return StorageItemInfo.builder()
        .name(physicalPath.substring(physicalPath.lastIndexOf('/') + 1))
        .directory(false)
        .path(physicalPath)
        .build();
  }

  /** Registers the POM of {@code versionPath}, with the version directory holding {@code items}. */
  private ArtifactVersion registerPomWithFiles(
      final String versionPath, final String artifactId, final List<StorageItemInfo> items) {
    final var id = UUID.randomUUID();
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(items);

    final var pom =
        """
        <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>\
        <artifactId>%s</artifactId><version>1.0</version></project>"""
            .formatted(artifactId);
    final var pomPath = versionPath + "/" + artifactId + "-1.0.pom";

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, pomPath),
        new ByteArrayResource(pom.getBytes(UTF_8)));

    final var captor = ArgumentCaptor.forClass(ArtifactVersion.class);
    verify(this.artifactUpsertHelper).insertArtifactVersion(captor.capture(), any(), any());

    return captor.getValue();
  }

  @Test
  @DisplayName("an artifactId that contains -sources does not flag sources or documents (RPS-1198)")
  void anArtifactIdContainingSourcesDoesNotFlagSources() {
    final var dir = "/data/storage/8c4f/com/acme/foo-sources/1.0/";

    final var version =
        this.registerPomWithFiles(
            "com/acme/foo-sources/1.0",
            "foo-sources",
            List.of(
                file(dir + "foo-sources-1.0.pom"),
                file(dir + "foo-sources-1.0.jar"),
                file(dir + "foo-sources-1.0.jar.sha1")));

    assertThat(version.isHasSources()).isFalse();
    assertThat(version.isHasDocuments()).isFalse();
  }

  @Test
  @DisplayName("an artifactId that contains -javadoc does not flag documents (RPS-1198)")
  void anArtifactIdContainingJavadocDoesNotFlagDocuments() {
    final var dir = "/data/storage/8c4f/com/acme/lib-javadoc/1.0/";

    final var version =
        this.registerPomWithFiles(
            "com/acme/lib-javadoc/1.0",
            "lib-javadoc",
            List.of(file(dir + "lib-javadoc-1.0.pom"), file(dir + "lib-javadoc-1.0.jar")));

    assertThat(version.isHasDocuments()).isFalse();
    assertThat(version.isHasSources()).isFalse();
  }

  @Test
  @DisplayName("a sources jar and a javadoc jar flag the version (RPS-1198)")
  void aRealSourcesAndJavadocJarFlagTheVersion() {
    final var dir = "/data/storage/8c4f/com/acme/lib/1.0/";

    final var version =
        this.registerPomWithFiles(
            "com/acme/lib/1.0",
            "lib",
            List.of(
                file(dir + "lib-1.0.pom"),
                file(dir + "lib-1.0.jar"),
                file(dir + "lib-1.0-sources.jar"),
                file(dir + "lib-1.0-javadoc.jar")));

    assertThat(version.isHasSources()).isTrue();
    assertThat(version.isHasDocuments()).isTrue();
  }

  @Test
  @DisplayName("the checksum or signature of a sources jar alone does not flag the version")
  void aChecksumOrSignatureOfASourcesJarAloneDoesNotFlagTheVersion() {
    final var dir = "/data/storage/8c4f/com/acme/lib/1.0/";

    final var version =
        this.registerPomWithFiles(
            "com/acme/lib/1.0",
            "lib",
            List.of(
                file(dir + "lib-1.0.pom"),
                file(dir + "lib-1.0-sources.jar.sha1"),
                file(dir + "lib-1.0-sources.jar.asc"),
                file(dir + "lib-1.0-javadoc.jar.md5")));

    assertThat(version.isHasSources()).isFalse();
    assertThat(version.isHasDocuments()).isFalse();
  }

  @Test
  @DisplayName("a storage directory named -sources and nested directories do not flag the version")
  void aStorageDirectoryOrNestedFileDoesNotFlagTheVersion() {
    final var dir = "/data/repos-sources/8c4f/com/acme/lib/1.0/";

    final var version =
        this.registerPomWithFiles(
            "com/acme/lib/1.0",
            "lib",
            List.of(
                file(dir + "lib-1.0.pom"),
                file(dir + "lib-1.0.jar"),
                StorageItemInfo.builder()
                    .name("x-sources")
                    .directory(true)
                    .path(dir + "x-sources")
                    .build(),
                file(dir + "x-sources/a/lib-1.0-sources.jar")));

    assertThat(version.isHasSources()).isFalse();
  }

  @ParameterizedTest(name = "{0} is refused with {1}")
  @CsvSource({
    "GROUP, groupIdTooLong",
    "ARTIFACT, artifactIdTooLong",
    "VERSION, mavenVersionTooLong",
    "SNAPSHOT_BASE_VERSION, mavenVersionTooLong"
  })
  @DisplayName("refuses a path whose coordinates do not fit their columns (RPS-1138)")
  void refusesOverLongCoordinates(final String which, final String messageId) {
    final var id = UUID.randomUUID();
    final var tooLong = "a".repeat(256);
    final var path =
        switch (which) {
          case "GROUP" -> tooLong + "/lib/1.0/lib-1.0.jar";
          case "ARTIFACT" -> "com/acme/" + tooLong + "/1.0/" + tooLong + "-1.0.jar";
          case "VERSION" -> "com/acme/lib/" + tooLong + "/lib-" + tooLong + ".jar";
          default ->
              "com/acme/lib/" + tooLong + "-SNAPSHOT/lib-" + tooLong + "-20260921.101010-1.jar";
        };

    assertThatThrownBy(
            () ->
                this.artifactService.getVersionType(
                    repo(id, true, true, true), StoragePath.of(id, path)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage(messageId);

    verifyNoInteractions(this.artifactRepository, this.artifactVersionRepository);
  }

  @Test
  @DisplayName("accepts coordinates exactly as long as their columns (RPS-1138)")
  void acceptsCoordinatesAtTheLimit() {
    final var id = UUID.randomUUID();
    final var limit = "a".repeat(255);
    final var path = "com/" + limit + "/1.0/" + limit + "-1.0.jar";

    assertThat(
            this.artifactService.getVersionType(
                repo(id, true, true, true), StoragePath.of(id, path)))
        .isEqualTo(RELEASE);
  }

  @Test
  @DisplayName("registers a POM with its over-long descriptive values dropped (RPS-1138)")
  void registersAPomWithOverLongDescriptiveValuesDropped() {
    final var id = UUID.randomUUID();
    final var long255 = "x".repeat(256);
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(List.of());

    final var pom =
        """
        <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>
          <artifactId>lib</artifactId><version>1.0</version>
          <name>%1$s</name><url>%1$s</url>
          <organization><name>%1$s</name></organization><scm><url>%1$s</url></scm>
          <parent><groupId>com.acme</groupId><artifactId>par</artifactId>
            <version>%1$s</version></parent>
          <licenses><license><name>Apache-2.0</name><url>%1$s</url></license>
            <license><name>%1$s</name></license></licenses>
          <developers><developer><name>Jane</name><email>%1$s</email></developer>
            <developer><email>nameless@acme.com</email></developer></developers>
        </project>"""
            .formatted(long255);

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/lib/1.0/lib-1.0.pom"),
        new ByteArrayResource(pom.getBytes(UTF_8)));

    final var artifact = ArgumentCaptor.forClass(Artifact.class);
    verify(this.artifactUpsertHelper).insertArtifact(artifact.capture());
    assertThat(artifact.getValue().getName()).isNull();

    final var version = ArgumentCaptor.forClass(ArtifactVersion.class);
    final var model = ArgumentCaptor.forClass(org.apache.maven.model.Model.class);
    verify(this.artifactUpsertHelper)
        .insertArtifactVersion(version.capture(), model.capture(), any());

    assertThat(version.getValue().getName()).isNull();
    assertThat(version.getValue().getUrl()).isNull();
    assertThat(version.getValue().getOrganization()).isNull();
    assertThat(version.getValue().getSourceCodeUrl()).isNull();
    assertThat(version.getValue().getParentArtifactGroup()).isNull();
    assertThat(version.getValue().getParentArtifactName()).isNull();
    assertThat(version.getValue().getParentArtifactVersion()).isNull();
    assertThat(model.getValue().getLicenses())
        .singleElement()
        .satisfies(
            license -> {
              assertThat(license.getName()).isEqualTo("Apache-2.0");
              assertThat(license.getUrl()).isNull();
            });
    assertThat(model.getValue().getDevelopers())
        .singleElement()
        .satisfies(
            developer -> {
              assertThat(developer.getName()).isEqualTo("Jane");
              assertThat(developer.getEmail()).isNull();
            });
  }

  @Test
  @DisplayName("registers a plugin whose prefix is longer than its column without one (RPS-1138)")
  void registersAPluginWithoutAnOverLongPrefix() {
    final var id = UUID.randomUUID();
    final var artifactId = "a".repeat(160) + "-maven-plugin";
    this.stubRepo(id);
    when(this.artifactUpsertHelper.insertArtifact(any(Artifact.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(this.storageStrategy.listStorageItems(any(StoragePath.class))).thenReturn(List.of());

    final var pom =
        """
        <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>\
        <artifactId>%1$s</artifactId><version>1.0</version><packaging>maven-plugin</packaging>\
        </project>"""
            .formatted(artifactId);

    this.artifactService.createOrUpdateArtifact(
        repo(id, true, true, true),
        StoragePath.of(id, "com/acme/" + artifactId + "/1.0/" + artifactId + "-1.0.pom"),
        new ByteArrayResource(pom.getBytes(UTF_8)));

    final var artifact = ArgumentCaptor.forClass(Artifact.class);
    verify(this.artifactUpsertHelper).insertArtifact(artifact.capture());
    assertThat(artifact.getValue().isPlugin()).isTrue();
    assertThat(artifact.getValue().getPrefix()).isNull();

    final var version = ArgumentCaptor.forClass(ArtifactVersion.class);
    verify(this.artifactUpsertHelper).insertArtifactVersion(version.capture(), any(), any());
    assertThat(version.getValue().getPrefix()).isNull();
  }

  private Resource stubStoredPom(final String relativePath) {
    final Resource pom = new ByteArrayResource("<project/>".getBytes(StandardCharsets.UTF_8));
    when(this.storageStrategy.get(pathOf(relativePath), eq("mvn"))).thenReturn(Optional.of(pom));

    return pom;
  }
}
