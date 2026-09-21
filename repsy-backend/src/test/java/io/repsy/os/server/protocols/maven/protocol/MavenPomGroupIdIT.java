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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1193: a POM whose {@code <groupId>} (else its {@code <parent><groupId>}) is not the group of
 * its path is refused with 400 {@code pomGroupIdMismatch} before it is stored. It used to be stored
 * and answered 200 but never registered, because the artifact rows are keyed on the path: the file
 * was served, yet invisible and undeletable in the panel and out of reach of the version events and
 * the scanner.
 *
 * <p>Only the groupId is compared, and case-sensitively like the layout. The artifactId and the
 * version are not: they are never used for the registration, and a {@code ${revision}} version, an
 * inherited version or an sbt cross-versioned artifactId would be refused wrongly. A POM that
 * declares no groupId and has no parent is not checked (Maven refuses such a POM itself).
 *
 * <p>Runs without a test transaction, like {@link MavenPomStorageConsistencyIT}: an accepted POM
 * inserts its artifact row in its own transaction, which cannot see an uncommitted repo row. It
 * deletes the repos and users it commits. {@link UsageUpdateService} is mocked to see whether an
 * upload reported any usage.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven POM of another group is refused before it is stored (RPS-1193)")
class MavenPomGroupIdIT extends AbstractIntegrationTest {

  private static final String POM_PATH = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String SNAPSHOT_POM_PATH =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom";
  private static final String MISMATCH = "pomGroupIdMismatch";
  private static final String MISMATCH_TEXT =
      "The POM declares a groupId that is not the one of its path; its <groupId> (or"
          + " <parent><groupId>) must equal the directory group.";

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

  private static String pom(
      final String groupElements, final String artifactId, final String version) {
    return pom(groupElements, artifactId, version, "");
  }

  private static String pom(
      final String groupElements,
      final String artifactId,
      final String version,
      final String extraElements) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          %s
          <artifactId>%s</artifactId>
          <version>%s</version>
          %s
        </project>
        """
        .formatted(groupElements, artifactId, version, extraElements);
  }

  private static String groupId(final String groupId) {
    return "<groupId>" + groupId + "</groupId>";
  }

  private static String parent(final String groupId) {
    return "<parent><groupId>%s</groupId><artifactId>par</artifactId><version>1</version></parent>"
        .formatted(groupId);
  }

  private static Stream<Arguments> refusedPoms() {
    return Stream.of(
        Arguments.of("groupId org.other", pom(groupId("org.other"), "lib", "1.0")),
        Arguments.of("parent groupId org.other", pom(parent("org.other"), "lib", "1.0")),
        Arguments.of(
            "own groupId org.other over the parent com.acme",
            pom(groupId("org.other") + parent("com.acme"), "lib", "1.0")),
        Arguments.of("groupId com.Acme", pom(groupId("com.Acme"), "lib", "1.0")),
        Arguments.of("groupId ${g}", pom(groupId("${g}"), "lib", "1.0")),
        Arguments.of("an empty groupId", pom(groupId(""), "lib", "1.0")));
  }

  /** Each one is registered as com.acme:lib under the version named for the path it is sent to. */
  private static Stream<Arguments> acceptedPoms() {
    return Stream.of(
        Arguments.of("its own groupId", POM_PATH, pom(groupId("com.acme"), "lib", "1.0"), "1.0"),
        Arguments.of(
            "the groupId of its parent", POM_PATH, pom(parent("com.acme"), "lib", "1.0"), "1.0"),
        Arguments.of("no groupId and no parent", POM_PATH, pom("", "lib", "1.0"), "1.0"),
        Arguments.of(
            "a ${revision} version",
            POM_PATH,
            pom(groupId("com.acme"), "lib", "${revision}"),
            "1.0"),
        Arguments.of(
            "another artifactId", POM_PATH, pom(groupId("com.acme"), "other", "1.0"), "1.0"),
        Arguments.of(
            "packaging pom",
            POM_PATH,
            pom(groupId("com.acme"), "lib", "1.0", "<packaging>pom</packaging>"),
            "1.0"),
        Arguments.of(
            "a timestamped snapshot",
            SNAPSHOT_POM_PATH,
            pom(groupId("com.acme"), "lib", "1.0-SNAPSHOT"),
            "1.0-SNAPSHOT"));
  }

  private Repo mavenRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("mvn-group");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

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
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body)
            .with(protocolPort()));
  }

  private void uploadOk(final Repo repo, final User admin, final String path, final String body)
      throws Exception {
    assertThat(this.upload(repo, admin, path, body).andReturn().getResponse().getStatus())
        .as("PUT %s", path)
        .isEqualTo(200);
  }

  private void expectMismatch(
      final Repo repo, final User admin, final String path, final String body) throws Exception {
    expectError(
        this.upload(repo, admin, path, body),
        HttpStatus.BAD_REQUEST,
        MISMATCH,
        MISMATCH,
        MISMATCH_TEXT);
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

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private int artifactCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_artifact where repo_id = ?", Integer.class, repo.getId());
  }

  /** The names of the registered artifacts of the repo as {@code group:artifact}. */
  private List<String> artifactsOf(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        "select group_name || ':' || artifact_name from maven_artifact where repo_id = ?",
        String.class,
        repo.getId());
  }

  private List<String> versionsOf(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select v.version_name from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
         where a.repo_id = ? and a.group_name = 'com.acme' and a.artifact_name = 'lib'""",
        String.class,
        repo.getId());
  }

  @ParameterizedTest(name = "a POM with {0} under com/acme is refused and stores nothing")
  @MethodSource("refusedPoms")
  @DisplayName("a POM whose groupId is not its path's answers 400 and leaves nothing behind")
  void aPomOfAnotherGroupIsRefusedAndLeavesNothingBehind(final String label, final String body)
      throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();

    this.expectMismatch(repo, admin, POM_PATH, body);

    assertThat(stored(repo, POM_PATH)).doesNotExist();
    assertThat(stored(repo, POM_PATH).getParent()).doesNotExist();
    assertThat(this.downloadStatus(repo, admin, POM_PATH)).isEqualTo(404);
    assertThat(this.artifactCount(repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }

  @ParameterizedTest(name = "a POM with {0} is stored and registered as com.acme:lib")
  @MethodSource("acceptedPoms")
  @DisplayName("a POM real clients send is stored byte for byte and registered under its path")
  void aPomOfItsOwnGroupIsStoredAndRegistered(
      final String label, final String path, final String body, final String version)
      throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();

    this.uploadOk(repo, admin, path, body);

    assertThat(Files.readAllBytes(stored(repo, path))).isEqualTo(body.getBytes(UTF_8));
    assertThat(this.artifactsOf(repo)).containsExactly("com.acme:lib");
    assertThat(this.versionsOf(repo)).containsExactly(version);
  }

  @Test
  @DisplayName("a refused POM does not block the matching one uploaded after it")
  void aRefusedPomDoesNotBlockTheMatchingOneAfterIt() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    this.expectMismatch(repo, admin, POM_PATH, pom(groupId("org.other"), "lib", "1.0"));
    final var matching = pom(groupId("com.acme"), "lib", "1.0");

    this.uploadOk(repo, admin, POM_PATH, matching);

    assertThat(Files.readString(stored(repo, POM_PATH))).isEqualTo(matching);
    assertThat(this.artifactsOf(repo)).containsExactly("com.acme:lib");
    assertThat(this.versionsOf(repo)).containsExactly("1.0");
  }

  @Test
  @DisplayName("a mismatching POM deployed over a stored one leaves the stored one in place")
  void aMismatchingRedeployKeepsTheStoredPom() throws Exception {
    final var repo = this.mavenRepo(true);
    final var admin = this.admin();
    final var stored = pom(groupId("com.acme"), "lib", "1.0");
    this.uploadOk(repo, admin, POM_PATH, stored);

    this.expectMismatch(repo, admin, POM_PATH, pom(groupId("org.other"), "lib", "1.0"));

    assertThat(Files.readString(stored(repo, POM_PATH))).isEqualTo(stored);
    assertThat(this.artifactsOf(repo)).containsExactly("com.acme:lib");
    assertThat(this.versionsOf(repo)).containsExactly("1.0");
  }
}
