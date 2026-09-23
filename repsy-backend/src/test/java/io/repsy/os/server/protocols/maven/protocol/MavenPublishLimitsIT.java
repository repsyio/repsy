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
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * RPS-1138: a groupId, artifactId, version or POM packaging longer than its column used to fail the
 * row insert with SQLSTATE 22001, which answered a generic 400 naming no field after the file had
 * been stored. The coordinates and the packaging are refused with a 400 naming the field before
 * anything is stored; every descriptive value of the POM (name, url, description, organization, SCM
 * url, parent, license and developer fields) is dropped or cut instead, and the version is
 * registered.
 *
 * <p>Runs without a test transaction, like {@link MavenPomGroupIdIT}: an accepted POM inserts its
 * artifact row in its own transaction, which cannot see an uncommitted repo row. It deletes the
 * repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven upload length guards (RPS-1138)")
class MavenPublishLimitsIT extends AbstractIntegrationTest {

  private static final String LONG = "x".repeat(256);

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
      final String groupId, final String artifactId, final String version, final String extra) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
          %s
        </project>
        """
        .formatted(groupId, artifactId, version, extra);
  }

  private static String pomPath(final String group, final String artifactId, final String version) {
    return "%s/%s/%s/%s-%s.pom"
        .formatted(group.replace('.', '/'), artifactId, version, artifactId, version);
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-limits");
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

  private int rowCount(final String table, final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from %s where repo_id = ?".formatted(table), Integer.class, repo.getId());
  }

  private static Stream<Arguments> refusedUploads() {
    return Stream.of(
        Arguments.of(
            "an over-long groupId",
            pomPath(LONG, "lib", "1.0"),
            pom(LONG, "lib", "1.0", ""),
            "groupIdTooLong",
            "The groupId in the path is longer than 255 characters."),
        Arguments.of(
            "an over-long groupId of a jar",
            LONG + "/lib/1.0/lib-1.0.jar",
            "jar",
            "groupIdTooLong",
            "The groupId in the path is longer than 255 characters."),
        Arguments.of(
            "an over-long artifactId",
            "com/acme/" + LONG + "/1.0/" + LONG + "-1.0.pom",
            pom("com.acme", LONG, "1.0", ""),
            "artifactIdTooLong",
            "The artifactId in the path is longer than 255 characters."),
        Arguments.of(
            "an over-long version",
            "com/acme/lib/" + LONG + "/lib-" + LONG + ".pom",
            pom("com.acme", "lib", LONG, ""),
            "mavenVersionTooLong",
            "The version in the path is longer than 255 characters."),
        Arguments.of(
            "an over-long packaging",
            pomPath("com.acme", "lib", "1.0"),
            pom("com.acme", "lib", "1.0", "<packaging>" + "p".repeat(51) + "</packaging>"),
            "pomPackagingTooLong",
            "The packaging of the POM is longer than 50 characters."));
  }

  @ParameterizedTest(name = "{0} is refused and stores nothing")
  @MethodSource("refusedUploads")
  @DisplayName("answers 400 naming the field, and leaves no file and no row behind")
  void refusesAnOverLongIdentityBeforeStoringAnything(
      final String label,
      final String path,
      final String body,
      final String msgId,
      final String text)
      throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    expectError(this.upload(repo, admin, path, body), HttpStatus.BAD_REQUEST, msgId, msgId, text);

    // Not `resolve(path)`: a component of 256 characters makes the file system answer
    // ENAMETOOLONG, which is neither "exists" nor "does not exist" for the assertion.
    assertThat(storageDirOf(repo)).isEmptyDirectory();
    assertThat(this.rowCount("maven_artifact", repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName(
      "registers a POM whose coordinates and packaging are exactly as long as their columns")
  void registersCoordinatesAtTheLimit() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    // The file name is kept short of the file system's 255 bytes; the group is the longest part.
    final var group = "g".repeat(255);
    final var packaging = "p".repeat(50);

    this.uploadOk(
        repo,
        admin,
        pomPath(group, "lib", "1.0"),
        pom(group, "lib", "1.0", "<packaging>" + packaging + "</packaging>"));

    final var row =
        this.jdbcTemplate.queryForMap(
            """
            select a.group_name, a.packaging, a.latest, v.packaging as version_packaging
              from maven_artifact a join maven_artifact_version v on v.artifact_id = a.id
             where a.repo_id = ?""",
            repo.getId());
    assertThat(row)
        .containsEntry("group_name", group)
        .containsEntry("packaging", packaging)
        .containsEntry("version_packaging", packaging)
        .containsEntry("latest", "1.0");
  }

  @Test
  @DisplayName("registers a version of 240 characters, which latest and release also hold")
  void registersALongVersion() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var version = "1".repeat(240);

    this.uploadOk(
        repo, admin, pomPath("com.acme", "a", version), pom("com.acme", "a", version, ""));

    final Map<String, Object> row =
        this.jdbcTemplate.queryForMap(
            "select latest, release from maven_artifact where repo_id = ?", repo.getId());
    assertThat(row).containsEntry("latest", version).containsEntry("release", version);
  }

  @Test
  @DisplayName("registers a POM with every descriptive value over-long, dropping or cutting them")
  void dropsOverLongDescriptiveValuesInsteadOfFailingTheInsert() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var extra =
        """
        <name>%1$s</name><url>%1$s</url><description>%2$s</description>
        <organization><name>%1$s</name></organization><scm><url>%1$s</url></scm>
        <parent><groupId>com.acme</groupId><artifactId>par</artifactId><version>%1$s</version></parent>
        <licenses>
          <license><name>Apache-2.0</name><url>%1$s</url></license>
          <license><name>%1$s</name></license>
          <license><url>https://example.com/license</url></license>
        </licenses>
        <developers>
          <developer><name>Jane</name><email>%1$s</email></developer>
          <developer><name>%1$s</name></developer>
        </developers>
        """
            .formatted(LONG, "word ".repeat(400));
    final var path = pomPath("com.acme", "lib", "1.0");

    this.uploadOk(repo, admin, path, pom("com.acme", "lib", "1.0", extra));

    final var version =
        this.jdbcTemplate.queryForMap(
            """
            select v.id, v.name, v.url, v.organization, v.source_code_url, v.description,
                   v.parent_artifact_group, v.parent_artifact_name, v.parent_artifact_version
              from maven_artifact_version v join maven_artifact a on a.id = v.artifact_id
             where a.repo_id = ?""",
            repo.getId());
    assertThat(version)
        .containsEntry("name", null)
        .containsEntry("url", null)
        .containsEntry("organization", null)
        .containsEntry("source_code_url", null)
        .containsEntry("parent_artifact_group", null)
        .containsEntry("parent_artifact_name", null)
        .containsEntry("parent_artifact_version", null);
    assertThat((String) version.get("description")).hasSizeLessThanOrEqualTo(1024).endsWith("word");

    final var versionId = version.get("id");
    assertThat(
            this.jdbcTemplate.queryForList(
                "select name || '|' || coalesce(url, '-') from maven_version_license"
                    + " where artifact_version_id = ?",
                String.class,
                versionId))
        .containsExactly("Apache-2.0|-");
    assertThat(
            this.jdbcTemplate.queryForList(
                "select name || '|' || coalesce(email, '-') from maven_version_developer"
                    + " where artifact_version_id = ?",
                String.class,
                versionId))
        .containsExactly("Jane|-");
  }
}
