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
 * With {@code allowOverride} off, a non-unique snapshot (the literal {@code lib-1.0-SNAPSHOT.*}
 * names that sbt and Ivy deploy every time) can still be deployed again, while an existing
 * timestamped build and an existing release stay immutable (RPS-1328). Before, the second publish
 * of an sbt snapshot got a 403 {@code artifactOverrideIsProhibited}, and Maven could redeploy the
 * same snapshot under a new timestamped name while sbt could not.
 *
 * <p>Runs without a test transaction, like {@link MavenVersionTypeRedeployIT}: an accepted POM
 * inserts its artifact row in its own transaction, which cannot see an uncommitted repo row. It
 * deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven allowOverride:false lets a literal SNAPSHOT be redeployed (RPS-1328)")
class MavenLiteralSnapshotRedeployIT extends AbstractIntegrationTest {

  private static final String DIR = "com/acme/lib/1.0-SNAPSHOT/";
  private static final String LITERAL_POM = DIR + "lib-1.0-SNAPSHOT.pom";
  private static final String LITERAL_JAR = DIR + "lib-1.0-SNAPSHOT.jar";
  private static final String LITERAL_JAR_SHA1 = DIR + "lib-1.0-SNAPSHOT.jar.sha1";
  private static final String TIMESTAMPED_POM = DIR + "lib-1.0-20260921.101010-1.pom";
  private static final String TIMESTAMPED_JAR = DIR + "lib-1.0-20260921.101010-1.jar";
  private static final String RELEASE_POM = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String RELEASE_JAR = "com/acme/lib/1.0/lib-1.0.jar";

  private static final String OVERRIDE_PROHIBITED =
      "Artifact override is prohibited in this repository!";
  private static final String SNAPSHOTS_PROHIBITED =
      "Snapshot versions are prohibited in this repository!";

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
    final var name = uniqueRepoName("mvn-lit");
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

  private int uploadStatus(final Repo repo, final User admin, final String path, final String body)
      throws Exception {
    return this.upload(repo, admin, path, body).andReturn().getResponse().getStatus();
  }

  private void settings(
      final Repo repo, final User admin, final boolean snapshots, final boolean allowOverride)
      throws Exception {
    final var body =
        """
        {"privateRepo":false,"allowOverride":%s,"releases":true,"snapshots":%s,\
        "securityScanEnabled":false}"""
            .formatted(allowOverride, snapshots);

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

  private static String pom(final String version, final String name) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
          <name>%s</name>
        </project>
        """
        .formatted(version, name);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private static void expectOverrideRefused(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.FORBIDDEN,
        "artifactOverrideIsProhibited",
        "artifactOverrideIsProhibited",
        OVERRIDE_PROHIBITED);
  }

  @Test
  @DisplayName("the literal pom, jar and checksum of a snapshot are accepted twice")
  void literalSnapshotFilesAreAcceptedTwice() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false);

    final var second = pom("1.0-SNAPSHOT", "second");

    assertThat(this.uploadStatus(repo, admin, LITERAL_POM, pom("1.0-SNAPSHOT", "first")))
        .isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, LITERAL_JAR, "first jar")).isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, LITERAL_JAR_SHA1, "first sha")).isEqualTo(200);

    assertThat(this.uploadStatus(repo, admin, LITERAL_POM, second)).isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, LITERAL_JAR, "second jar")).isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, LITERAL_JAR_SHA1, "second sha")).isEqualTo(200);

    assertThat(Files.readString(stored(repo, LITERAL_POM))).isEqualTo(second);
    assertThat(Files.readString(stored(repo, LITERAL_JAR))).isEqualTo("second jar");
    assertThat(Files.readString(stored(repo, LITERAL_JAR_SHA1))).isEqualTo("second sha");
  }

  @Test
  @DisplayName("an existing timestamped build is still refused")
  void timestampedBuildStaysImmutable() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false);
    final var original = pom("1.0-SNAPSHOT", "original");

    assertThat(this.uploadStatus(repo, admin, TIMESTAMPED_POM, original)).isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, TIMESTAMPED_JAR, "jar")).isEqualTo(200);

    expectOverrideRefused(
        this.upload(repo, admin, TIMESTAMPED_POM, pom("1.0-SNAPSHOT", "overwritten")));
    expectOverrideRefused(this.upload(repo, admin, TIMESTAMPED_JAR, "other jar"));
    assertThat(Files.readString(stored(repo, TIMESTAMPED_POM))).isEqualTo(original);
    assertThat(Files.readString(stored(repo, TIMESTAMPED_JAR))).isEqualTo("jar");
  }

  @Test
  @DisplayName("an existing release is still refused")
  void releaseStaysImmutable() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false);
    final var original = pom("1.0", "original");

    assertThat(this.uploadStatus(repo, admin, RELEASE_POM, original)).isEqualTo(200);
    assertThat(this.uploadStatus(repo, admin, RELEASE_JAR, "jar")).isEqualTo(200);

    expectOverrideRefused(this.upload(repo, admin, RELEASE_POM, pom("1.0", "overwritten")));
    expectOverrideRefused(this.upload(repo, admin, RELEASE_JAR, "other jar"));
    assertThat(Files.readString(stored(repo, RELEASE_POM))).isEqualTo(original);
    assertThat(Files.readString(stored(repo, RELEASE_JAR))).isEqualTo("jar");
  }

  @Test
  @DisplayName("sbt can follow a Maven timestamped deploy of the same snapshot")
  void literalPomFollowsATimestampedDeploy() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false);

    assertThat(this.uploadStatus(repo, admin, TIMESTAMPED_POM, pom("1.0-SNAPSHOT", "maven")))
        .isEqualTo(200);

    final var literal = pom("1.0-SNAPSHOT", "sbt");

    assertThat(this.uploadStatus(repo, admin, LITERAL_POM, literal)).isEqualTo(200);
    assertThat(Files.readString(stored(repo, LITERAL_POM))).isEqualTo(literal);
  }

  @Test
  @DisplayName("a literal snapshot is still refused while snapshots are off")
  void literalSnapshotIsRefusedWhileSnapshotsAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.settings(repo, admin, true, false);
    assertThat(this.uploadStatus(repo, admin, LITERAL_POM, pom("1.0-SNAPSHOT", "first")))
        .isEqualTo(200);

    this.settings(repo, admin, false, false);

    expectError(
        this.upload(repo, admin, LITERAL_POM, pom("1.0-SNAPSHOT", "second")),
        HttpStatus.FORBIDDEN,
        "snapshotVersionsAreProhibited",
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
  }
}
