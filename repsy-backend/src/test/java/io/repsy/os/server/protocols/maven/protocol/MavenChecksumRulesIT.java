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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
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
 * A checksum is judged by the file it belongs to (RPS-1183). It used to skip every deployment rule:
 * with {@code releases} switched off, {@code lib-3.5.jar} answered 403 while {@code
 * lib-3.5.jar.sha1} answered 200 and created the directory of the version, and the checksum of a
 * path outside the layout was stored although its file was refused with 400. The GAV calculator
 * strips the checksum suffix, so the layout check, the version type and the override rule of the
 * base file now apply to its {@code .sha1}, {@code .md5}, {@code .sha256} and {@code .sha512}.
 *
 * <p>A {@code maven-metadata.xml} checksum holds a hash, not XML, so it is judged by its directory
 * alone: a file of a {@code SNAPSHOT} version directory is a snapshot, the artifact-level and
 * group-level ones are not judged.
 *
 * <p>A checksum whose file is not stored is accepted, so a client that sends the checksum first (as
 * Ivy and sbt do not, but a hand-written script may) keeps working. Runs without a test
 * transaction, like {@link MavenMetadataUploadIT}, and deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven checksums are judged by the file they belong to (RPS-1183)")
class MavenChecksumRulesIT extends AbstractIntegrationTest {

  private static final String LIB_DIR = "com/acme/lib/";
  private static final String RELEASE_DIR = LIB_DIR + "3.5/";
  private static final String RELEASE_JAR = RELEASE_DIR + "lib-3.5.jar";
  private static final String SNAPSHOT_DIR = LIB_DIR + "1.0-SNAPSHOT/";
  private static final String SNAPSHOT_POM = SNAPSHOT_DIR + "lib-1.0-20260921.101010-1.pom";
  private static final String SNAPSHOT_VERSION_METADATA = SNAPSHOT_DIR + "maven-metadata.xml";
  private static final String ARTIFACT_METADATA = LIB_DIR + "maven-metadata.xml";

  private static final String RELEASES_PROHIBITED =
      "Release versions are prohibited in this repository!";
  private static final String SNAPSHOTS_PROHIBITED =
      "Snapshot versions are prohibited in this repository!";
  private static final String OVERRIDE_PROHIBITED =
      "Artifact override is prohibited in this repository!";

  private static final String HASH = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

  private static final String VERSION_METADATA =
      """
      <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>lib</artifactId>\
      <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
      <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
      <snapshotVersions><snapshotVersion><extension>pom</extension>\
      <value>1.0-20260921.101010-1</value><updated>20260921101010</updated></snapshotVersion>\
      </snapshotVersions></versioning></metadata>""";

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
    final var name = uniqueRepoName("mvn-cs");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("mvn-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
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

  /** Uploads what a client sends for the path: a hash for a checksum, XML for metadata. */
  private ResultActions upload(final Repo repo, final User admin, final String path)
      throws Exception {
    final var checksum = path.endsWith(".sha1") || path.endsWith(".md5");
    final var metadata = path.endsWith("maven-metadata.xml");
    final var mediaType =
        checksum
            ? MediaType.TEXT_PLAIN
            : metadata ? MediaType.APPLICATION_XML : MediaType.APPLICATION_OCTET_STREAM;
    final var body = checksum ? HASH : metadata ? VERSION_METADATA : "bytes";

    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(mediaType)
            .content(body)
            .with(protocolPort()));
  }

  private int status(final Repo repo, final User admin, final String path) throws Exception {
    return this.upload(repo, admin, path).andReturn().getResponse().getStatus();
  }

  private static void expectRefused(
      final ResultActions result, final String msgId, final String text) throws Exception {
    expectError(result, HttpStatus.FORBIDDEN, msgId, msgId, text);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  @Test
  @DisplayName(
      "the checksum of a new release is refused while releases are off, and nothing is kept")
  void checksumOfANewReleaseIsRefusedWhileReleasesAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, false, true, true);

    expectRefused(
        this.upload(repo, admin, RELEASE_JAR + ".sha1"),
        "releaseVersionsAreProhibited",
        RELEASES_PROHIBITED);
    expectRefused(
        this.upload(repo, admin, RELEASE_JAR), "releaseVersionsAreProhibited", RELEASES_PROHIBITED);

    assertThat(stored(repo, RELEASE_JAR + ".sha1")).doesNotExist();
    assertThat(stored(repo, RELEASE_DIR)).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("the checksums of a snapshot are refused while snapshots are off")
  void checksumsOfASnapshotAreRefusedWhileSnapshotsAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false, true);

    expectRefused(
        this.upload(repo, admin, SNAPSHOT_POM + ".sha1"),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    expectRefused(
        this.upload(repo, admin, SNAPSHOT_VERSION_METADATA + ".sha1"),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);

    assertThat(this.status(repo, admin, ARTIFACT_METADATA + ".sha1")).isEqualTo(200);
    assertThat(stored(repo, ARTIFACT_METADATA + ".sha1")).exists();
    assertThat(stored(repo, SNAPSHOT_DIR)).doesNotExist();
    verify(this.usageUpdateService, times(1)).updateUsage(any(UsageChangedInfo.class));
  }

  @Test
  @DisplayName("the checksum of a redeploy is refused once its kind is switched off")
  void checksumOfARedeployIsRefusedOnceItsKindIsOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, true);

    assertThat(this.status(repo, admin, LIB_DIR + "1.0/lib-1.0.jar")).isEqualTo(200);
    assertThat(this.status(repo, admin, LIB_DIR + "1.0/lib-1.0.jar.sha1")).isEqualTo(200);
    this.settings(repo, admin, false, true, true);

    expectRefused(
        this.upload(repo, admin, LIB_DIR + "1.0/lib-1.0.jar.sha1"),
        "releaseVersionsAreProhibited",
        RELEASES_PROHIBITED);
    expectRefused(
        this.upload(repo, admin, LIB_DIR + "1.0/lib-1.0.jar.md5"),
        "releaseVersionsAreProhibited",
        RELEASES_PROHIBITED);
    assertThat(stored(repo, LIB_DIR + "1.0/lib-1.0.jar.md5")).doesNotExist();
  }

  @Test
  @DisplayName("the checksum of an accepted file is stored, before or after the file")
  void checksumOfAnAcceptedFileIsStoredInEitherOrder() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, true);
    final var jar = LIB_DIR + "1.0/lib-1.0.jar";

    for (final var path :
        List.of(
            jar + ".sha1",
            jar,
            jar + ".md5",
            SNAPSHOT_VERSION_METADATA,
            SNAPSHOT_VERSION_METADATA + ".sha1")) {
      assertThat(this.status(repo, admin, path)).as("PUT %s", path).isEqualTo(200);
      assertThat(stored(repo, path)).as("stored %s", path).exists();
    }

    verify(this.usageUpdateService, times(5)).updateUsage(any(UsageChangedInfo.class));
  }

  @Test
  @DisplayName("a checksum is an override only when the checksum file itself exists")
  void checksumIsAnOverrideOnlyWhenTheChecksumFileExists() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, false);
    final var jar = LIB_DIR + "1.0/lib-1.0.jar";

    assertThat(this.status(repo, admin, jar)).isEqualTo(200);
    assertThat(this.status(repo, admin, jar + ".sha1")).isEqualTo(200);

    expectRefused(
        this.upload(repo, admin, jar + ".sha1"),
        "artifactOverrideIsProhibited",
        OVERRIDE_PROHIBITED);
    assertThat(this.status(repo, admin, jar + ".md5")).isEqualTo(200);
    assertThat(stored(repo, jar + ".md5")).exists();
  }

  /**
   * Known, accepted gap (RPS-1195): a release version-level {@code maven-metadata.xml} checksum
   * carries no {@code &lt;version&gt;} (its body is a hash) and its directory does not end with
   * {@code SNAPSHOT}, so {@link
   * io.repsy.protocols.maven.shared.utils.ArtifactUtils#isSnapshotVersionDirectoryFile} cannot tell
   * it from an artifact-level checksum of an artifact literally named {@code 1.0}. It is accepted
   * rather than fixed: telling the two apart would need reading the stored base {@code
   * maven-metadata.xml} back from storage and parsing it for every checksum, which is a storage
   * read (and an order dependence on the base file already being stored) this class does not
   * otherwise need, and it conflicts with the delete-safety work landed alongside it. Maven itself
   * never writes this shape (only a snapshot deploy writes version-level metadata), so only a
   * hand-crafted PUT reaches it.
   */
  @Test
  @DisplayName(
      "known gap: a release version-level metadata checksum is not judged by the releases"
          + " setting and still creates its directory (RPS-1195)")
  void releaseVersionLevelMetadataChecksumIsAKnownGap() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, false, true, true);

    final var path = LIB_DIR + "1.0/maven-metadata.xml.sha1";

    assertThat(this.status(repo, admin, path)).isEqualTo(200);
    assertThat(stored(repo, path)).exists();
  }

  @Test
  @DisplayName(
      "an upper-case checksum suffix on a metadata file is never treated as its checksum"
          + " (RPS-1195): maven-metadata.xml.SHA1 is read as metadata content and rejected as"
          + " malformed, instead of being judged by its directory like maven-metadata.xml.sha1")
  void upperCaseMetadataChecksumSuffixIsReadAsMetadata() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, true, true);

    expectError(
        this.upload(repo, admin, ARTIFACT_METADATA + ".SHA1"),
        HttpStatus.BAD_REQUEST,
        "malformedMetadataFile",
        "malformedMetadataFile",
        "Metadata file is malformed or incomplete, please retry the deployment.");
    assertThat(stored(repo, ARTIFACT_METADATA + ".SHA1")).doesNotExist();
  }
}
