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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
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
import java.nio.file.Files;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ArtifactUtils.isPomToParse} and {@code isPomSignature}, and {@code
 * ArtifactServiceImpl.createOrUpdateArtifact}, used to test for {@code .pom} as a substring of the
 * whole relative path rather than the file name's suffix. An artifactId (or a directory) that
 * merely contains {@code .pom} (for example {@code bar.pom.utils}, or a directory named {@code
 * x.pom}) made every one of its non-POM files look like a POM to parse or a POM signature to
 * verify:
 *
 * <ul>
 *   <li>a jar was parsed as XML and answered 400 {@code malformedPomFile}, nothing stored;
 *   <li>the jar's {@code .asc} was "verified" against a POM that was never stored and answered 404
 *       {@code itemNotFound};
 *   <li>the artifact-level {@code maven-metadata.xml} and its {@code .asc} were stored correctly
 *       (metadata classification reads the file name, not this predicate) and only then, after the
 *       store, misclassified by {@code createOrUpdateArtifact}: the metadata XML failed the POM
 *       parse (400 {@code malformedPomFile}) and the metadata signature was looked up as a POM
 *       signature of a path with no GAV (404 {@code itemNotFound}).
 * </ul>
 *
 * The real {@code .pom}, its checksum and its own signature already worked. This is now decided by
 * the file name's {@code .pom} suffix alone (RPS-1196), the same rule as {@code isChecksumFile}
 * (RPS-1183) and {@code isMetadataSignature} (RPS-1185).
 *
 * <p>Runs without a test transaction, like {@link MavenChecksumRulesIT}: an accepted POM inserts
 * its artifact row in its own transaction, which cannot see an uncommitted repo row. It deletes the
 * repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A Maven artifactId containing \".pom\" is judged by file name, not path (RPS-1196)")
class MavenDottedArtifactIdIT extends AbstractIntegrationTest {

  private static final String GROUP_ID = "com.acme";

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
    final var name = uniqueRepoName("mvn-dot");
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
      final Repo repo, final User admin, final String path, final byte[] body) throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body)
            .with(protocolPort()));
  }

  private int status(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    return this.upload(repo, admin, path, body).andReturn().getResponse().getStatus();
  }

  private void uploadOk(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    assertThat(this.status(repo, admin, path, body)).as("PUT %s", path).isEqualTo(200);
  }

  private static byte[] bytes(final String s) {
    return s.getBytes(UTF_8);
  }

  private static byte[] pom(final String artifactId, final String version) {
    return pomOfGroup(GROUP_ID, artifactId, version);
  }

  private static byte[] pomOfGroup(
      final String groupId, final String artifactId, final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(groupId, artifactId, version)
        .getBytes(UTF_8);
  }

  private static byte[] artifactMetadata(final String artifactId) {
    return """
        <metadata><groupId>com.acme</groupId><artifactId>%s</artifactId><versioning>\
        <release>1.0</release><versions><version>1.0</version></versions>\
        <lastUpdated>20260921101010</lastUpdated></versioning></metadata>"""
        .formatted(artifactId)
        .getBytes(UTF_8);
  }

  private static byte[] versionMetadata(final String artifactId) {
    return """
        <metadata modelVersion="1.1.0"><groupId>com.acme</groupId><artifactId>%s</artifactId>\
        <version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>20260921.101010</timestamp>\
        <buildNumber>1</buildNumber></snapshot><lastUpdated>20260921101010</lastUpdated>\
        <snapshotVersions><snapshotVersion><extension>pom</extension>\
        <value>1.0-20260921.101010-1</value><updated>20260921101010</updated></snapshotVersion>\
        <snapshotVersion><extension>jar</extension><value>1.0-20260921.101010-1</value>\
        <updated>20260921101010</updated></snapshotVersion></snapshotVersions></versioning>\
        </metadata>"""
        .formatted(artifactId)
        .getBytes(UTF_8);
  }

  private static final byte[] ARMOR_ONLY =
      bytes("-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n");

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private int versionCount(final Repo repo, final String artifactId, final String versionName) {
    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from maven_artifact_version v join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? and a.group_name = 'com.acme' and a.artifact_name = ?
            and v.version_name = ?""",
        Integer.class,
        repo.getId(),
        artifactId,
        versionName);
  }

  @Test
  @DisplayName(
      "a jar under an artifactId containing \".pom\" is stored like any other (it answered 400"
          + " malformedPomFile before the fix)")
  void aJarUnderAnArtifactIdContainingPomIsStoredLikeAnyOther() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var jar = bytes("PK\u0003\u0004binarycontentnotxmlatall1234567890");
    final var path = "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.jar";

    this.uploadOk(repo, admin, path, jar);

    assertThat(Files.readAllBytes(stored(repo, path))).isEqualTo(jar);
    verify(this.usageUpdateService, times(1)).updateUsage(any(UsageChangedInfo.class));
  }

  @ParameterizedTest(name = "a deploy of {0} round-trips exactly like a normal artifact")
  @ValueSource(strings = {"lib", "bar.pom.utils", "x.pom"})
  @DisplayName(
      "a full deploy round-trips exactly like a normal artifact, whatever the artifactId or"
          + " directory contains (RPS-1196)")
  void aDeployRoundTripsExactlyLikeANormalArtifact(final String artifactId) throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var dir = "com/acme/" + artifactId + "/1.0/";
    final var groupDir = "com/acme/" + artifactId + "/";
    final var stem = artifactId + "-1.0";
    final var pom = pom(artifactId, "1.0");
    final var jar = bytes("jar " + artifactId);
    final var sources = bytes("sources " + artifactId);
    final var metadata = artifactMetadata(artifactId);

    this.uploadOk(repo, admin, dir + stem + ".pom", pom);
    this.uploadOk(
        repo, admin, dir + stem + ".pom.sha1", bytes("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    this.uploadOk(repo, admin, dir + stem + ".pom.md5", bytes("d41d8cd98f00b204e9800998ecf8427e"));
    this.uploadOk(repo, admin, dir + stem + ".jar", jar);
    this.uploadOk(
        repo, admin, dir + stem + ".jar.sha1", bytes("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    // A jar signature is never verified (only a POM signature is), so a garbage body is stored
    // unverified (RPS-1188).
    this.uploadOk(repo, admin, dir + stem + ".jar.asc", bytes("not a real signature"));
    this.uploadOk(repo, admin, dir + stem + "-sources.jar", sources);
    this.uploadOk(repo, admin, groupDir + "maven-metadata.xml", metadata);
    this.uploadOk(
        repo,
        admin,
        groupDir + "maven-metadata.xml.sha1",
        bytes("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    this.uploadOk(repo, admin, groupDir + "maven-metadata.xml.asc", ARMOR_ONLY);

    assertThat(Files.readAllBytes(stored(repo, dir + stem + ".pom"))).isEqualTo(pom);
    assertThat(Files.readAllBytes(stored(repo, dir + stem + ".jar"))).isEqualTo(jar);
    assertThat(Files.readAllBytes(stored(repo, dir + stem + "-sources.jar"))).isEqualTo(sources);
    assertThat(Files.readAllBytes(stored(repo, groupDir + "maven-metadata.xml")))
        .isEqualTo(metadata);
    assertThat(this.versionCount(repo, artifactId, "1.0")).isEqualTo(1);
    verify(this.usageUpdateService, times(10)).updateUsage(any(UsageChangedInfo.class));
  }

  @Test
  @DisplayName(
      "a POM under an artifactId containing \".pom\" is still parsed and its signature still"
          + " verified (RPS-1196)")
  void aPomUnderSuchAnArtifactIdIsStillParsedAndItsSignatureStillVerified() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var path = "com/acme/bar.pom.utils/1.0/bar.pom.utils-1.0.pom";

    assertThat(this.status(repo, admin, path, bytes("<project><groupId>"))).isEqualTo(400);
    assertThat(stored(repo, path)).doesNotExist();

    assertThat(this.status(repo, admin, path, pomOfGroup("org.other", "bar.pom.utils", "1.0")))
        .isEqualTo(400);
    assertThat(stored(repo, path)).doesNotExist();

    final var goodPom = pom("bar.pom.utils", "1.0");
    this.uploadOk(repo, admin, path, goodPom);

    assertThat(this.status(repo, admin, path + ".asc", ARMOR_ONLY)).isEqualTo(422);
    assertThat(stored(repo, path + ".asc")).doesNotExist();
  }

  @Test
  @DisplayName("a snapshot deploy under an artifactId containing \".pom\" round-trips (RPS-1196)")
  void aSnapshotDeployUnderSuchAnArtifactIdRoundTrips() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var dir = "com/acme/bar.pom.utils/1.0-SNAPSHOT/";
    final var stem = "bar.pom.utils-1.0-20260921.101010-1";
    final var pom = pom("bar.pom.utils", "1.0-SNAPSHOT");
    final var jar = bytes("snapshot jar");

    this.uploadOk(repo, admin, dir + stem + ".pom", pom);
    this.uploadOk(repo, admin, dir + stem + ".jar", jar);
    this.uploadOk(repo, admin, dir + "maven-metadata.xml", versionMetadata("bar.pom.utils"));

    assertThat(Files.readAllBytes(stored(repo, dir + stem + ".pom"))).isEqualTo(pom);
    assertThat(Files.readAllBytes(stored(repo, dir + stem + ".jar"))).isEqualTo(jar);
    assertThat(this.versionCount(repo, "bar.pom.utils", "1.0-SNAPSHOT")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "metadata under an artifactId containing \".pom\" is stored without a POM error (it"
          + " answered 400/404 after the store, before the fix)")
  void metadataUnderSuchAnArtifactIdIsStoredWithoutAPomError() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var metadata = artifactMetadata("bar.pom.utils");
    final var path = "com/acme/bar.pom.utils/maven-metadata.xml";

    this.uploadOk(repo, admin, path, metadata);
    this.uploadOk(repo, admin, path + ".asc", ARMOR_ONLY);

    assertThat(Files.readAllBytes(stored(repo, path))).isEqualTo(metadata);
    assertThat(Files.readAllBytes(stored(repo, path + ".asc"))).isEqualTo(ARMOR_ONLY);
  }
}
