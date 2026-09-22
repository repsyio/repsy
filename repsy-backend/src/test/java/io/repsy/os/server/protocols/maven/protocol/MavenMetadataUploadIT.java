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
package io.repsy.os.server.protocols.maven.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code maven-metadata.xml} is judged by the {@code releases} and {@code snapshots} repo settings
 * only at version level, by its {@code <version>} (RPS-1176). {@code isPluginMetadata} used to be
 * true for every parseable metadata file, so no metadata upload was ever judged: a snapshot's
 * version-level file returned 200 with snapshots switched off. The artifact-level and group-level
 * files list versions of both kinds and are never judged, and no metadata file is an override.
 *
 * <p>The {@code .asc} signature of a metadata file is stored unparsed and unverified, and judged
 * like a metadata checksum: by its directory only (RPS-1185). It used to be parsed as XML and
 * refused with {@code malformedMetadataFile}.
 *
 * <p>Each test uploads in the order {@code mvn deploy} does: the artifacts (each followed by its
 * checksums) first, then all the metadata. Runs without a test transaction, like {@link
 * MavenVersionTypeRedeployIT}, and deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven metadata classification follows the version-level file (RPS-1176, RPS-1185)")
class MavenMetadataUploadIT extends AbstractIntegrationTest {

  private static final String LIB_DIR = "com/acme/lib/";
  private static final String SNAPSHOT_DIR = LIB_DIR + "1.0-SNAPSHOT/";
  private static final String SNAPSHOT_FILE = SNAPSHOT_DIR + "lib-1.0-20260921.101010-1";
  private static final String SNAPSHOT_JAR = SNAPSHOT_FILE + ".jar";
  private static final String SNAPSHOT_POM = SNAPSHOT_FILE + ".pom";
  private static final String SNAPSHOT_VERSION_METADATA = SNAPSHOT_DIR + "maven-metadata.xml";
  private static final String RELEASE_DIR = LIB_DIR + "1.0/";
  private static final String RELEASE_JAR = RELEASE_DIR + "lib-1.0.jar";
  private static final String RELEASE_POM = RELEASE_DIR + "lib-1.0.pom";
  private static final String ARTIFACT_METADATA_PATH = LIB_DIR + "maven-metadata.xml";
  private static final String GROUP_METADATA_PATH = "com/acme/maven-metadata.xml";
  private static final String ARMORED_SIGNATURE =
      "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n";
  private static final String GARBAGE_SIGNATURE = "not a signature";
  private static final String HASH = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

  private static final String SNAPSHOTS_PROHIBITED =
      "Snapshot versions are prohibited in this repository!";
  private static final String RELEASES_PROHIBITED =
      "Release versions are prohibited in this repository!";
  private static final String OVERRIDE_PROHIBITED =
      "Artifact override is prohibited in this repository!";
  private static final String MALFORMED_METADATA =
      "Metadata file is malformed or incomplete, please retry the deployment.";

  private static final String GROUP_METADATA =
      """
      <metadata><plugins><plugin><name>Acme Maven Plugin</name><prefix>acme</prefix>\
      <artifactId>acme-maven-plugin</artifactId></plugin></plugins></metadata>""";

  private static final String ARTIFACT_METADATA_RELEASE =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
      <release>1.0</release><versions><version>1.0</version></versions>\
      <lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";

  private static final String ARTIFACT_METADATA_SNAPSHOT =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
      <versions><version>1.0-SNAPSHOT</version></versions>\
      <lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";

  private static final String VERSION_METADATA_TEMPLATE =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>%d</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      <snapshotVersions><snapshotVersion><extension>jar</extension>\
      <value>1.0-20260921.101010-%d</value><updated>20260921101010</updated></snapshotVersion>\
      <snapshotVersion><extension>pom</extension><value>1.0-20260921.101010-%d</value>\
      <updated>20260921101010</updated></snapshotVersion></snapshotVersions></versioning>\
      </metadata>""";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the artifacts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-md");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("mvn-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private ResultActions upload(
      final Repo repo, final User admin, final String path, final String body) throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_XML)
            .content(body)
            .with(protocolPort()));
  }

  /** Uploads a file and its {@code .sha1} and {@code .md5}, as Maven does, all expecting 200. */
  private void deploy(final Repo repo, final User admin, final String path, final String body)
      throws Exception {
    for (final var suffix : List.of("", ".sha1", ".md5")) {
      final var content = suffix.isEmpty() ? body : "0123456789abcdef0123456789abcdef";

      assertThat(
              this.upload(repo, admin, path + suffix, content)
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .as("PUT %s", path + suffix)
          .isEqualTo(200);
    }
  }

  private void settings(
      final Repo repo,
      final User admin,
      final boolean releases,
      final boolean snapshots,
      final boolean allowOverride)
      throws Exception {
    final var body =
        """
        {"privateRepo":false,"allowOverride":%s,"releases":%s,"snapshots":%s,\
        "securityScanEnabled":false}"""
            .formatted(allowOverride, releases, snapshots);

    expectSuccess(
        this.mockMvc.perform(
            put("/api/repos/{name}/settings", repo.getName())
                .header(AUTHORIZATION, this.bearerTokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(apiPort())),
        "settingsUpdated",
        "Settings updated.");
  }

  private static String pom(final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(version);
  }

  private static String versionMetadata(final int buildNumber) {
    return VERSION_METADATA_TEMPLATE.formatted(buildNumber, buildNumber, buildNumber);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private long versionRows(final Repo repo, final String versionName) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from maven_artifact_version v
              join maven_artifact a on a.id = v.artifact_id
             where a.repo_id = ? and v.version_name = ?""",
            Long.class,
            repo.getId(),
            versionName);

    return count == null ? 0 : count;
  }

  private static void expectRefused(
      final ResultActions result, final String msgId, final String text) throws Exception {
    expectError(result, HttpStatus.FORBIDDEN, msgId, msgId, text);
  }

  private int status(final Repo repo, final User admin, final String path, final String body)
      throws Exception {
    return this.upload(repo, admin, path, body).andReturn().getResponse().getStatus();
  }

  @Test
  @DisplayName("a snapshot deploy in Maven's order succeeds while snapshots are on")
  void snapshotDeploySequenceSucceedsWithSnapshotsOn() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, false, true, true);

    this.deploy(repo, admin, SNAPSHOT_JAR, "jar bytes");
    this.deploy(repo, admin, SNAPSHOT_POM, pom("1.0-SNAPSHOT"));
    this.deploy(repo, admin, SNAPSHOT_VERSION_METADATA, versionMetadata(1));
    this.deploy(repo, admin, ARTIFACT_METADATA_PATH, ARTIFACT_METADATA_SNAPSHOT);

    for (final var path :
        List.of(SNAPSHOT_JAR, SNAPSHOT_POM, SNAPSHOT_VERSION_METADATA, ARTIFACT_METADATA_PATH)) {
      assertThat(stored(repo, path)).exists();
      assertThat(stored(repo, path + ".sha1")).exists();
      assertThat(stored(repo, path + ".md5")).exists();
    }
    assertThat(this.versionRows(repo, "1.0-SNAPSHOT")).isEqualTo(1);
  }

  @Test
  @DisplayName("a snapshot deploy is refused on its first file while snapshots are off")
  void snapshotDeployIsRefusedOnItsFirstFileWithSnapshotsOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false, true);

    expectRefused(
        this.upload(repo, admin, SNAPSHOT_JAR, "jar bytes"),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    // A client that sends the version-level metadata first (Gradle) is stopped there.
    expectRefused(
        this.upload(repo, admin, SNAPSHOT_VERSION_METADATA, versionMetadata(1)),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    // The artifact-level file lists versions of both kinds and is not judged.
    assertThat(this.status(repo, admin, ARTIFACT_METADATA_PATH, ARTIFACT_METADATA_SNAPSHOT))
        .isEqualTo(200);

    assertThat(stored(repo, SNAPSHOT_DIR)).doesNotExist();
    assertThat(this.versionRows(repo, "1.0-SNAPSHOT")).isZero();
  }

  @Test
  @DisplayName("a release deploy in Maven's order succeeds while releases are on")
  void releaseDeploySequenceSucceedsWithReleasesOn() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false, true);

    this.deploy(repo, admin, RELEASE_JAR, "jar bytes");
    this.deploy(repo, admin, RELEASE_POM, pom("1.0"));
    this.deploy(repo, admin, ARTIFACT_METADATA_PATH, ARTIFACT_METADATA_RELEASE);

    for (final var path : List.of(RELEASE_JAR, RELEASE_POM, ARTIFACT_METADATA_PATH)) {
      assertThat(stored(repo, path)).exists();
      assertThat(stored(repo, path + ".sha1")).exists();
      assertThat(stored(repo, path + ".md5")).exists();
    }
    assertThat(this.versionRows(repo, "1.0")).isEqualTo(1);
  }

  @Test
  @DisplayName("a release deploy is refused on its first file while releases are off")
  void releaseDeployIsRefusedOnItsFirstFileWithReleasesOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, false, true, true);

    expectRefused(
        this.upload(repo, admin, RELEASE_JAR, "jar bytes"),
        "releaseVersionsAreProhibited",
        RELEASES_PROHIBITED);
    // The artifact-level file is not judged, even when it lists nothing but a release.
    assertThat(this.status(repo, admin, ARTIFACT_METADATA_PATH, ARTIFACT_METADATA_RELEASE))
        .isEqualTo(200);

    assertThat(stored(repo, RELEASE_DIR)).doesNotExist();
    assertThat(this.versionRows(repo, "1.0")).isZero();
  }

  @Test
  @DisplayName("plugin metadata is accepted whatever the settings")
  void pluginMetadataIsAcceptedWhateverTheSettings() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, false, false, true);

    assertThat(this.status(repo, admin, GROUP_METADATA_PATH, GROUP_METADATA)).isEqualTo(200);
    assertThat(stored(repo, GROUP_METADATA_PATH)).exists();
  }

  @Test
  @DisplayName("version-level metadata is never an override, the timestamped jar still is")
  void versionLevelMetadataIsNeverAnOverride() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, false);

    assertThat(this.status(repo, admin, SNAPSHOT_JAR, "jar bytes")).isEqualTo(200);
    assertThat(this.status(repo, admin, SNAPSHOT_VERSION_METADATA, versionMetadata(1)))
        .isEqualTo(200);
    assertThat(this.status(repo, admin, SNAPSHOT_VERSION_METADATA, versionMetadata(2)))
        .isEqualTo(200);

    expectRefused(
        this.upload(repo, admin, SNAPSHOT_JAR, "other bytes"),
        "artifactOverrideIsProhibited",
        OVERRIDE_PROHIBITED);
  }

  @Test
  @DisplayName("malformed metadata is refused and not stored")
  void malformedMetadataIsRefusedAndNotStored() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    expectError(
        this.upload(repo, admin, ARTIFACT_METADATA_PATH, "<metadata><versioning>"),
        HttpStatus.BAD_REQUEST,
        "malformedMetadataFile",
        "malformedMetadataFile",
        MALFORMED_METADATA);
    assertThat(stored(repo, ARTIFACT_METADATA_PATH)).doesNotExist();
  }

  @Test
  @DisplayName("a metadata signature is stored unparsed at every level, ahead of its file too")
  void metadataSignatureIsStoredUnparsedAtEveryLevel() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, true);

    final var signatures =
        List.of(
            GROUP_METADATA_PATH + ".asc",
            ARTIFACT_METADATA_PATH + ".asc",
            SNAPSHOT_VERSION_METADATA + ".asc");
    for (final var path : signatures) {
      assertThat(this.status(repo, admin, path, ARMORED_SIGNATURE))
          .as("PUT %s", path)
          .isEqualTo(200);
    }
    // The checksum of a signature is a checksum, whose body is a hash.
    assertThat(this.status(repo, admin, ARTIFACT_METADATA_PATH + ".asc.sha1", HASH))
        .as("PUT the checksum of a signature")
        .isEqualTo(200);

    for (final var path : signatures) {
      assertThat(Files.readString(stored(repo, path), StandardCharsets.UTF_8))
          .as("the stored %s", path)
          .isEqualTo(ARMORED_SIGNATURE);
    }
    assertThat(Files.readString(stored(repo, ARTIFACT_METADATA_PATH + ".asc.sha1")))
        .isEqualTo(HASH);
    // A signature registers nothing, and it may precede the file it signs.
    assertThat(this.versionRows(repo, "1.0-SNAPSHOT")).isZero();
    assertThat(stored(repo, GROUP_METADATA_PATH)).doesNotExist();
    assertThat(stored(repo, ARTIFACT_METADATA_PATH)).doesNotExist();
    assertThat(stored(repo, SNAPSHOT_VERSION_METADATA)).doesNotExist();
  }

  @Test
  @DisplayName("a version-level metadata signature is refused while snapshots are off")
  void versionLevelMetadataSignatureIsRefusedWhileSnapshotsAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false, true);

    expectRefused(
        this.upload(repo, admin, SNAPSHOT_VERSION_METADATA + ".asc", ARMORED_SIGNATURE),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    // The artifact-level file is not judged, and neither is its signature.
    assertThat(this.status(repo, admin, ARTIFACT_METADATA_PATH + ".asc", ARMORED_SIGNATURE))
        .isEqualTo(200);

    assertThat(stored(repo, SNAPSHOT_DIR)).doesNotExist();
    assertThat(stored(repo, ARTIFACT_METADATA_PATH + ".asc")).exists();
    assertThat(this.versionRows(repo, "1.0-SNAPSHOT")).isZero();
  }

  @Test
  @DisplayName("a metadata signature is never an override and is not validated")
  void metadataSignatureIsNeverAnOverrideAndIsNotValidated() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, false);
    final var path = ARTIFACT_METADATA_PATH + ".asc";

    assertThat(this.status(repo, admin, path, ARMORED_SIGNATURE)).isEqualTo(200);
    // Stored as sent and never verified: RPS-1188 is the story that may change that.
    assertThat(this.status(repo, admin, path, GARBAGE_SIGNATURE)).isEqualTo(200);

    assertThat(Files.readString(stored(repo, path), StandardCharsets.UTF_8))
        .isEqualTo(GARBAGE_SIGNATURE);
  }
}
