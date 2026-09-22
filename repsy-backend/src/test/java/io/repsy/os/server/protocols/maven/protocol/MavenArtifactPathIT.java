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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A PUT to a path outside the Maven layout, {@code
 * <group>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<extension>}, used to answer
 * 200 and store nothing (RPS-1182): the facade dropped an upload whose path had no GAV without a
 * word, so the client believed its file was deployed. It is refused with 400 {@code
 * invalidArtifactPath} now, before anything is stored, because every rule (override, releases and
 * snapshots, the artifact rows, the scanner) is keyed on the GAV and a file without one would
 * bypass them. Sonatype Nexus refuses the same paths with 400 under its strict layout policy.
 *
 * <p>A file in a {@code SNAPSHOT} directory must also carry that directory's artifactId and base
 * version, literal or timestamped (RPS-1184); the GAV parser only checks where the marker sits.
 *
 * <p>The files that real Maven, Gradle and sbt clients send all parse to a GAV and keep being
 * stored. Runs without a test transaction, like {@link MavenMetadataUploadIT}, and deletes the
 * repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven refuses a path outside the artifact layout (RPS-1182, RPS-1184)")
class MavenArtifactPathIT extends AbstractIntegrationTest {

  private static final String LIB_DIR = "com/acme/lib/";
  private static final String RELEASE_DIR = LIB_DIR + "1.0/";
  private static final String SNAPSHOT_DIR = LIB_DIR + "1.0-SNAPSHOT/";
  private static final String INVALID_PATH_TEXT =
      "The path is not a valid Maven artifact path. Expected"
          + " <group>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<extension>.";
  private static final String SNAPSHOT_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.acme</groupId>
        <artifactId>lib</artifactId>
        <version>1.0-SNAPSHOT</version>
      </project>
      """;
  private static final String MALFORMED_POM =
      "<project><modelVersion>4.0.0</modelVersion><groupId>";

  private static final String GROUP_METADATA_PATH = "com/acme/maven-metadata.xml";
  private static final String ARTIFACT_METADATA_PATH = LIB_DIR + "maven-metadata.xml";
  private static final String GROUP_METADATA =
      """
      <metadata><plugins><plugin><name>Acme Maven Plugin</name><prefix>acme</prefix>\
      <artifactId>acme-maven-plugin</artifactId></plugin></plugins></metadata>""";
  private static final String ARTIFACT_METADATA =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>\
      <release>1.0</release><versions><version>1.0</version></versions>\
      <lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";

  /**
   * What Maven, Gradle (with Kotlin Multiplatform) and sbt deploy, checksums and signatures too.
   */
  private static final List<String> REAL_DEPLOY_FILES =
      List.of(
          RELEASE_DIR + "lib-1.0.jar",
          RELEASE_DIR + "lib-1.0.war",
          RELEASE_DIR + "lib-1.0.aar",
          RELEASE_DIR + "lib-1.0.zip",
          RELEASE_DIR + "lib-1.0.klib",
          RELEASE_DIR + "lib-1.0.tar.gz",
          RELEASE_DIR + "lib-1.0.module",
          RELEASE_DIR + "lib-1.0-sources.jar",
          RELEASE_DIR + "lib-1.0-javadoc.jar",
          RELEASE_DIR + "lib-1.0-tests.jar",
          RELEASE_DIR + "lib-1.0-kotlin-tooling-metadata.json",
          RELEASE_DIR + "lib-1.0.jar.asc",
          RELEASE_DIR + "lib-1.0.jar.sha1",
          RELEASE_DIR + "lib-1.0.jar.md5",
          RELEASE_DIR + "lib-1.0.jar.sha256",
          RELEASE_DIR + "lib-1.0.jar.sha512",
          RELEASE_DIR + "lib-1.0.module.sha512",
          "com/acme/lib_2.13/1.0/lib_2.13-1.0.jar",
          SNAPSHOT_DIR + "lib-1.0-20260921.101010-1.jar",
          SNAPSHOT_DIR + "lib-1.0-SNAPSHOT.jar",
          SNAPSHOT_DIR + "lib-1.0-20260921.101010-1-sources.jar",
          SNAPSHOT_DIR + "lib-1.0-20260921.101010-1.module",
          SNAPSHOT_DIR + "lib-1.0-SNAPSHOT-sources.jar",
          SNAPSHOT_DIR + "lib-1.0-20260921.101010-1.jar.sha1");

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
    final var name = uniqueRepoName("mvn-path");
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
      final Repo repo,
      final User admin,
      final String path,
      final String body,
      final MediaType contentType)
      throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(contentType)
            .content(body)
            .with(protocolPort()));
  }

  private int status(final Repo repo, final User admin, final String path, final String body)
      throws Exception {
    return this.upload(repo, admin, path, body, MediaType.APPLICATION_OCTET_STREAM)
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int downloadStatus(final Repo repo, final User admin, final String path)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/{path}", repo.getName(), path)
                .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private void settings(final Repo repo, final User admin) throws Exception {
    expectSuccess(
        this.mockMvc.perform(
            put("/api/repos/{name}/settings", repo.getName())
                .header(AUTHORIZATION, this.bearerTokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"privateRepo":false,"allowOverride":true,"releases":true,"snapshots":true,\
                    "securityScanEnabled":false}""")
                .with(apiPort())),
        "settingsUpdated",
        "Settings updated.");
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private long artifactRows(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from maven_artifact where repo_id = ?", Long.class, repo.getId());

    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("the files a real Maven, Gradle or sbt deploy sends are all stored and counted")
  void filesOfARealDeployAreStored() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin);

    for (final var path : REAL_DEPLOY_FILES) {
      assertThat(this.status(repo, admin, path, "content of " + path))
          .as("PUT %s", path)
          .isEqualTo(200);
      assertThat(stored(repo, path)).as("stored %s", path).exists();
    }

    verify(this.usageUpdateService, times(REAL_DEPLOY_FILES.size()))
        .updateUsage(any(UsageChangedInfo.class));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "io/stray.txt",
        "stray.txt",
        "com/acme/lib/1.0/other-1.0.jar",
        "com/acme/lib/1.0/lib-2.0.jar",
        "com/acme/lib/1.0/Lib-1.0.jar",
        "com/acme/lib/1.0/lib-1.0",
        "com/acme/lib/1.0-SNAPSHOT/stray.txt",
        // M2GavCalculator throws IndexOutOfBoundsException for this one instead of answering null.
        "com/acme/lib/1.0-SNAPSHOT/b-1.0-SNAPSHOT.jar",
        // RPS-1184: a file of a SNAPSHOT directory named for another artifactId or version.
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-20260921.101010-1.jar",
        "com/acme/lib/1.0-SNAPSHOT/lob-1.0-SNAPSHOT.jar",
        "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOTX.jar",
        // RPS-1183: the checksum of a refused path is refused like its file.
        "io/stray.txt.sha1",
        "com/acme/lib/1.0/other-1.0.jar.sha1",
        "com/acme/lib/1.0-SNAPSHOT/lib-2.0-SNAPSHOT.jar.sha1"
      })
  @DisplayName(
      "a path outside the artifact layout, and a checksum of it, is refused with 400 and nothing is"
          + " stored")
  void pathsOutsideTheLayoutAreRefusedAndNothingIsStored(final String path) throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    expectError(
        this.upload(repo, admin, path, "hello", MediaType.TEXT_PLAIN),
        HttpStatus.BAD_REQUEST,
        "invalidArtifactPath",
        "invalidArtifactPath",
        INVALID_PATH_TEXT);

    assertThat(stored(repo, path)).doesNotExist();
    assertThat(this.downloadStatus(repo, admin, path)).isEqualTo(404);
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName(
      "a file of another version is refused even when the snapshot version exists (RPS-1184)")
  void aFileOfAnotherVersionIsRefusedEvenWhenTheSnapshotVersionExists() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin);

    final var seeded =
        this.upload(
            repo,
            admin,
            SNAPSHOT_DIR + "lib-1.0-20260921.101010-1.pom",
            SNAPSHOT_POM,
            MediaType.APPLICATION_XML);
    assertThat(seeded.andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(this.artifactRows(repo)).isOne();

    final var other = SNAPSHOT_DIR + "lib-2.0-SNAPSHOT.jar";

    expectError(
        this.upload(repo, admin, other, "hello", MediaType.APPLICATION_OCTET_STREAM),
        HttpStatus.BAD_REQUEST,
        "invalidArtifactPath",
        "invalidArtifactPath",
        INVALID_PATH_TEXT);

    assertThat(stored(repo, other)).doesNotExist();
    assertThat(this.downloadStatus(repo, admin, other)).isEqualTo(404);
    assertThat(this.artifactRows(repo)).isOne();
  }

  @Test
  @DisplayName("a stray POM is refused as a path before it is parsed")
  void aStrayPomIsRefusedAsAPathBeforeItIsParsed() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    expectError(
        this.upload(repo, admin, "io/stray.pom", MALFORMED_POM, MediaType.APPLICATION_XML),
        HttpStatus.BAD_REQUEST,
        "invalidArtifactPath",
        "invalidArtifactPath",
        INVALID_PATH_TEXT);

    assertThat(stored(repo, "io/stray.pom")).doesNotExist();
    assertThat(this.artifactRows(repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("metadata files and their checksums are not judged by the layout check")
  void metadataFilesKeepWorking() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    for (final var upload :
        List.of(
            List.of(GROUP_METADATA_PATH, GROUP_METADATA),
            List.of(ARTIFACT_METADATA_PATH, ARTIFACT_METADATA))) {
      final var path = upload.get(0);

      assertThat(this.status(repo, admin, path, upload.get(1))).as("PUT %s", path).isEqualTo(200);
      assertThat(this.status(repo, admin, path + ".sha1", "0123456789abcdef0123456789abcdef"))
          .as("PUT %s.sha1", path)
          .isEqualTo(200);
      assertThat(stored(repo, path)).exists();
      assertThat(stored(repo, path + ".sha1")).exists();
    }
  }
}
