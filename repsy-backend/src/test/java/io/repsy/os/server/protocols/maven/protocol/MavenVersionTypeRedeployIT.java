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
 * The {@code releases} and {@code snapshots} repo settings refuse a version of that kind, whether
 * it is new or already exists (RPS-1174). {@code ArtifactServiceImpl} used to skip the check for a
 * redeploy, so after {@code snapshots} was switched off an existing snapshot could still be
 * overwritten while a new snapshot coordinate got a 403.
 *
 * <p>Runs without a test transaction, like {@link MavenPomStorageConsistencyIT}: an accepted POM
 * inserts its artifact row in its own transaction, which cannot see an uncommitted repo row. It
 * deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven releases/snapshots settings also refuse redeploys (RPS-1174)")
class MavenVersionTypeRedeployIT extends AbstractIntegrationTest {

  private static final String SNAPSHOT_POM = "com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom";
  private static final String SNAPSHOT_JAR =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar";
  private static final String RELEASE_POM = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String NEXT_SNAPSHOT_POM = "com/acme/lib/1.1-SNAPSHOT/lib-1.1-SNAPSHOT.pom";

  private static final String SNAPSHOTS_PROHIBITED =
      "Snapshot versions are prohibited in this repository!";
  private static final String RELEASES_PROHIBITED =
      "Release versions are prohibited in this repository!";
  private static final String OVERRIDE_PROHIBITED =
      "Artifact override is prohibited in this repository!";

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
    final var name = uniqueRepoName("mvn-vt");
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

  private static void expectRefused(
      final ResultActions result, final String msgId, final String text) throws Exception {
    expectError(result, HttpStatus.FORBIDDEN, msgId, msgId, text);
  }

  @Test
  @DisplayName("an existing snapshot cannot be redeployed once snapshots are off")
  void snapshotRedeployIsRefusedOnceSnapshotsAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var original = pom("1.0-SNAPSHOT", "original");
    assertThat(
            this.upload(repo, admin, SNAPSHOT_POM, original).andReturn().getResponse().getStatus())
        .isEqualTo(200);

    this.settings(repo, admin, true, false, true);

    expectRefused(
        this.upload(repo, admin, SNAPSHOT_POM, pom("1.0-SNAPSHOT", "overwritten")),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    expectRefused(
        this.upload(repo, admin, SNAPSHOT_JAR, "jar bytes"),
        "snapshotVersionsAreProhibited",
        SNAPSHOTS_PROHIBITED);
    assertThat(Files.readString(stored(repo, SNAPSHOT_POM))).isEqualTo(original);
    assertThat(stored(repo, SNAPSHOT_JAR)).doesNotExist();
    assertThat(
            this.upload(repo, admin, RELEASE_POM, pom("1.0", "release"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("an existing release cannot be redeployed once releases are off")
  void releaseRedeployIsRefusedOnceReleasesAreOff() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var original = pom("1.0", "original");
    assertThat(
            this.upload(repo, admin, RELEASE_POM, original).andReturn().getResponse().getStatus())
        .isEqualTo(200);

    this.settings(repo, admin, false, true, true);

    expectRefused(
        this.upload(repo, admin, RELEASE_POM, pom("1.0", "overwritten")),
        "releaseVersionsAreProhibited",
        RELEASES_PROHIBITED);
    assertThat(Files.readString(stored(repo, RELEASE_POM))).isEqualTo(original);
    assertThat(
            this.upload(repo, admin, NEXT_SNAPSHOT_POM, pom("1.1-SNAPSHOT", "snapshot"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("the override rule is checked first, so its message wins")
  void overrideRuleIsCheckedFirst() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var original = pom("1.0", "original");
    assertThat(
            this.upload(repo, admin, RELEASE_POM, original).andReturn().getResponse().getStatus())
        .isEqualTo(200);

    this.settings(repo, admin, false, true, false);

    expectRefused(
        this.upload(repo, admin, RELEASE_POM, pom("1.0", "overwritten")),
        "artifactOverrideIsProhibited",
        OVERRIDE_PROHIBITED);
    assertThat(Files.readString(stored(repo, RELEASE_POM))).isEqualTo(original);
  }

  @Test
  @DisplayName("a redeploy stays allowed while its kind is on")
  void redeployStaysAllowedWhileItsKindIsOn() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    assertThat(
            this.upload(repo, admin, SNAPSHOT_POM, pom("1.0-SNAPSHOT", "original"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    this.settings(repo, admin, false, true, true);

    final var redeployed = pom("1.0-SNAPSHOT", "redeployed");
    assertThat(
            this.upload(repo, admin, SNAPSHOT_POM, redeployed)
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(Files.readString(stored(repo, SNAPSHOT_POM))).isEqualTo(redeployed);
  }
}
