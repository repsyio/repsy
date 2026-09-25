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
package io.repsy.os.server.protocols.maven.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Pins the two data-loss bugs found while implementing RPS-1190 and RPS-1197:
 *
 * <ul>
 *   <li>a version name that does not exist used to reach {@code hasOnlyOneVersion} as true and
 *       cascade into deleting the whole artifact (and, when it was the group's only artifact, the
 *       whole group) instead of answering 404;
 *   <li>deleting a group's only artifact used to move the group's whole directory to trash,
 *       destroying a nested sibling group's files (e.g. {@code com.acme.sub} living inside {@code
 *       com/acme}) even though its DB rows survived untouched.
 * </ul>
 *
 * <p>Also covers the idempotent version delete (a version row whose storage directory is already
 * gone answers 200 instead of 500) and the stale {@code maven-metadata.xml.asc} cleanup on a
 * metadata rewrite.
 */
@DisplayName("Maven artifact deletion safety (RPS-1190, RPS-1197, RPS-1349)")
class MavenArtifactDeletionSafetyIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenApiFacade mavenApiFacade;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  @Value("${storage-gateway.fs.base-path}")
  private String storageBasePath;

  private Repo repo;
  private User admin;
  private String repoName;

  @BeforeEach
  void setUp() {
    this.admin = this.createUser(uniqueUsername("mvndel"), UserRole.ADMIN);
    this.repoName = uniqueRepoName("mvndel");
    final var repoInfo =
        this.repoTxService.createRepo(this.repoName, RepoType.MAVEN, false, "Maven delete IT");
    this.repo = this.repoRepository.findById(repoInfo.getId()).orElseThrow();
    this.mavenApiFacade.createRepo(this.repo.getId());
  }

  private String bearerToken() {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
            this.admin.getId(), this.admin.getUsername(), Duration.ofMinutes(30));
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture seeding: a release-only artifact, its DB rows and its storage layout.
  // ---------------------------------------------------------------------------------------------

  private void seedArtifact(final String group, final String artifactName, final String... versions)
      throws IOException {

    final var latest = versions[versions.length - 1];

    var artifact = new Artifact();
    artifact.setRepo(this.repo);
    artifact.setGroupName(group);
    artifact.setArtifactName(artifactName);
    artifact.setName(artifactName);
    artifact.setPackaging("jar");
    artifact.setPlugin(false);
    artifact.setLatest(latest);
    artifact.setRelease(latest);
    artifact.setCreatedAt(Instant.now());
    artifact.setLastUpdatedAt(Instant.now());
    artifact = this.artifactRepository.saveAndFlush(artifact);

    for (final var version : versions) {
      final var artifactVersion = new ArtifactVersion();
      artifactVersion.setArtifact(artifact);
      artifactVersion.setCreatedAt(Instant.now());
      artifactVersion.setLastUpdatedAt(Instant.now());
      artifactVersion.setType(ArtifactVersionType.RELEASE);
      artifactVersion.setVersionName(version);
      artifactVersion.setName(artifactName);
      artifactVersion.setPackaging("jar");
      artifactVersion.setHasSources(false);
      artifactVersion.setHasDocuments(false);
      artifactVersion.setHasModules(false);
      this.artifactVersionRepository.saveAndFlush(artifactVersion);

      final var versionDir = this.versionDir(group, artifactName, version);
      Files.createDirectories(versionDir);
      Files.writeString(
          versionDir.resolve(artifactName + "-" + version + ".pom"),
          pomXml(group, artifactName, version),
          StandardCharsets.UTF_8);
    }

    Files.writeString(
        this.artifactDir(group, artifactName).resolve("maven-metadata.xml"),
        metadataXml(group, artifactName, List.of(versions), latest),
        StandardCharsets.UTF_8);
  }

  private Path artifactDir(final String group, final String artifactName) {
    return Path.of(
        this.storageBasePath,
        "maven",
        this.repo.getId().toString(),
        group.replace('.', '/'),
        artifactName);
  }

  private Path versionDir(final String group, final String artifactName, final String version) {
    return this.artifactDir(group, artifactName).resolve(version);
  }

  private static String pomXml(
      final String group, final String artifactName, final String version) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + group
        + "</groupId><artifactId>"
        + artifactName
        + "</artifactId><version>"
        + version
        + "</version><packaging>jar</packaging><name>"
        + artifactName
        + "</name></project>";
  }

  private static String metadataXml(
      final String group,
      final String artifactName,
      final List<String> versions,
      final String latest) {

    final var versionsXml =
        versions.stream().map(v -> "<version>" + v + "</version>").collect(Collectors.joining());

    return "<metadata><groupId>"
        + group
        + "</groupId><artifactId>"
        + artifactName
        + "</artifactId><versioning><latest>"
        + latest
        + "</latest><release>"
        + latest
        + "</release><versions>"
        + versionsXml
        + "</versions></versioning></metadata>";
  }

  /** Recursively deletes a directory, used to simulate storage that has already gone missing. */
  private static void deleteRecursively(final Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      walk.sorted(Comparator.reverseOrder()).forEach(MavenArtifactDeletionSafetyIT::deleteQuietly);
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.delete(path);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Assertions
  // ---------------------------------------------------------------------------------------------

  private void assertArtifactExists(final String group, final String artifactName) {
    assertThat(
            this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
                this.repo.getId(), group, artifactName))
        .as("artifact row %s:%s", group, artifactName)
        .isPresent();
    assertThat(this.artifactDir(group, artifactName))
        .as("artifact directory %s:%s", group, artifactName)
        .exists();
  }

  private void assertArtifactGone(final String group, final String artifactName) {
    assertThat(
            this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
                this.repo.getId(), group, artifactName))
        .as("artifact row %s:%s", group, artifactName)
        .isEmpty();
    assertThat(this.artifactDir(group, artifactName))
        .as("artifact directory %s:%s", group, artifactName)
        .doesNotExist();
  }

  private void assertVersionExists(
      final String group, final String artifactName, final String version) {
    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(this.repo.getId(), group, artifactName)
            .orElseThrow();
    assertThat(
            this.artifactVersionRepository.findByArtifactIdAndVersionName(
                artifact.getId(), version))
        .as("version row %s:%s:%s", group, artifactName, version)
        .isPresent();
    assertThat(this.versionDir(group, artifactName, version))
        .as("version directory %s:%s:%s", group, artifactName, version)
        .exists();
  }

  private void assertVersionGone(final Artifact artifact, final String version) {
    assertThat(
            this.artifactVersionRepository.findByArtifactIdAndVersionName(
                artifact.getId(), version))
        .as("version row %s of artifact %s", version, artifact.getArtifactName())
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Bug 1: a wrong version name must not cascade into deleting the whole artifact.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a version name that does not exist answers 404 and deletes nothing, even when it is the"
          + " artifact's only real version")
  void wrongVersionNameIsRefusedWithoutDeletingTheRealVersion() throws Exception {
    final var group = "io.repsy.wrongver";
    final var artifactName = "demo";
    this.seedArtifact(group, artifactName, "1.0");

    this.mockMvc
        .perform(
            delete(
                    "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                    this.repoName,
                    group,
                    artifactName,
                    "9.9")
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.msgId").value("artifactVersionNotFound"))
        .andExpect(jsonPath("$.data").value("artifactVersionNotFound"));

    this.assertArtifactExists(group, artifactName);
    this.assertVersionExists(group, artifactName, "1.0");
  }

  @Test
  @DisplayName(
      "deleting the only version of an artifact that has a sibling artifact in the same group"
          + " leaves the group and the sibling untouched")
  void onlyVersionCascadesToArtifactOnlyWhenSiblingArtifactExists() throws Exception {
    final var group = "io.repsy.siblings";
    this.seedArtifact(group, "alpha", "1.0");
    this.seedArtifact(group, "beta", "2.0");

    this.mockMvc
        .perform(
            delete(
                    "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                    this.repoName,
                    group,
                    "alpha",
                    "1.0")
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("artifactVersionDeleted"))
        .andExpect(jsonPath("$.data").value("ARTIFACT"));

    this.assertArtifactGone(group, "alpha");
    this.assertArtifactExists(group, "beta");
    this.assertVersionExists(group, "beta", "2.0");
  }

  // ---------------------------------------------------------------------------------------------
  // Bug 2: deleting a group's only artifact must not wipe a nested sibling group's files.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "deleting the only artifact of a group leaves a parent group, a nested child group and an"
          + " unrelated group untouched")
  void deletingOnlyArtifactOfGroupLeavesParentNestedAndUnrelatedGroupsIntact() throws Exception {
    final var parentGroup = "com.acme";
    final var targetGroup = "com.acme.foo";
    final var nestedChildGroup = "com.acme.foo.sub";
    final var unrelatedGroup = "org.x";

    this.seedArtifact(parentGroup, "parentart", "1.0");
    this.seedArtifact(targetGroup, "target", "1.0");
    this.seedArtifact(nestedChildGroup, "childart", "1.0");
    this.seedArtifact(unrelatedGroup, "otherart", "1.0");

    this.mockMvc
        .perform(
            delete(
                    "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                    this.repoName,
                    targetGroup,
                    "target",
                    "1.0")
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("artifactVersionDeleted"))
        .andExpect(jsonPath("$.data").value("GROUP"));

    // The deleted group and its artifact are gone.
    this.assertArtifactGone(targetGroup, "target");

    // The parent group, the nested child group, and the unrelated group all survive: DB rows and
    // storage directories alike. This is the RPS-1190 data-loss scenario: deleteGroup used to move
    // the whole com/acme/foo directory to trash, which included com/acme/foo/sub (the nested child
    // group's own directory).
    this.assertArtifactExists(parentGroup, "parentart");
    this.assertVersionExists(parentGroup, "parentart", "1.0");
    this.assertArtifactExists(nestedChildGroup, "childart");
    this.assertVersionExists(nestedChildGroup, "childart", "1.0");
    this.assertArtifactExists(unrelatedGroup, "otherart");
    this.assertVersionExists(unrelatedGroup, "otherart", "1.0");
  }

  // ---------------------------------------------------------------------------------------------
  // RPS-1190 part 2: idempotent delete when the storage directory is already gone.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "deleting a version whose storage directory is already gone answers 200 and removes the"
          + " row instead of a 500")
  void deletingVersionWithMissingStorageDirectorySucceeds() throws Exception {
    final var group = "io.repsy.missingdir";
    final var artifactName = "demo";
    this.seedArtifact(group, artifactName, "1.0", "2.0");

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(this.repo.getId(), group, artifactName)
            .orElseThrow();

    // Simulate an earlier partial delete or a manual cleanup: the row for 1.0 still exists, but
    // its storage directory is already gone.
    deleteRecursively(this.versionDir(group, artifactName, "1.0"));
    assertThat(this.versionDir(group, artifactName, "1.0")).doesNotExist();

    this.mockMvc
        .perform(
            delete(
                    "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                    this.repoName,
                    group,
                    artifactName,
                    "1.0")
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("artifactVersionDeleted"))
        .andExpect(jsonPath("$.data").value("VERSION"));

    this.assertVersionGone(artifact, "1.0");
    this.assertVersionExists(group, artifactName, "2.0");
  }

  // ---------------------------------------------------------------------------------------------
  // RPS-1197: a stale maven-metadata.xml.asc must not survive a metadata rewrite.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "deleting a version removes a stored maven-metadata.xml.asc and its checksum siblings, since"
          + " they no longer sign the rewritten metadata")
  void deletingVersionRemovesStaleMetadataSignatureFamily() throws Exception {
    final var group = "io.repsy.ascstale";
    final var artifactName = "demo";
    this.seedArtifact(group, artifactName, "1.0", "2.0");

    final var artifactDir = this.artifactDir(group, artifactName);
    final var ascPath = artifactDir.resolve("maven-metadata.xml.asc");
    final var ascSha1Path = artifactDir.resolve("maven-metadata.xml.asc.sha1");
    final var ascMd5Path = artifactDir.resolve("maven-metadata.xml.asc.md5");
    Files.writeString(ascPath, "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n");
    Files.writeString(ascSha1Path, "deadbeef");
    Files.writeString(ascMd5Path, "cafebabe");

    this.mockMvc
        .perform(
            delete(
                    "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                    this.repoName,
                    group,
                    artifactName,
                    "1.0")
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("artifactVersionDeleted"));

    assertThat(ascPath).as("stale maven-metadata.xml.asc").doesNotExist();
    assertThat(ascSha1Path).as("stale maven-metadata.xml.asc.sha1").doesNotExist();
    assertThat(ascMd5Path).as("stale maven-metadata.xml.asc.md5").doesNotExist();
    assertThat(artifactDir.resolve("maven-metadata.xml")).as("rewritten metadata").exists();

    this.assertVersionExists(group, artifactName, "2.0");
  }

  // ---------------------------------------------------------------------------------------------
  // RPS-1349: the "root group" delete (the shortest group prefixing all others).
  // ---------------------------------------------------------------------------------------------

  /** Every regular file of the repo's storage directory, relative to it, with '/' separators. */
  private Set<String> storedFiles() throws IOException {
    final var repoDir = Path.of(this.storageBasePath, "maven", this.repo.getId().toString());

    if (!Files.exists(repoDir)) {
      return Set.of();
    }

    try (var walk = Files.walk(repoDir)) {
      return walk.filter(Files::isRegularFile)
          .map(file -> repoDir.relativize(file).toString().replace('\\', '/'))
          .collect(Collectors.toSet());
    }
  }

  /** Puts a jar next to the POM of each version, so a version is more than one file. */
  private void seedJars(final String group, final String artifactName, final String... versions)
      throws IOException {
    for (final var version : versions) {
      Files.writeString(
          this.versionDir(group, artifactName, version)
              .resolve(artifactName + "-" + version + ".jar"),
          "jar of " + group + ":" + artifactName + ":" + version,
          StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName(
      "deleting the root group removes exactly the artifacts and versions its confirmation counts,"
          + " in the database and in storage, and leaves the nested group's files alone")
  void deletingRootGroupRemovesWhatTheSummarySaysAndKeepsTheNestedGroup() throws Exception {
    final var rootGroup = "com.acme";
    final var nestedGroup = "com.acme.sub";

    this.seedArtifact(rootGroup, "alpha", "1.0", "2.0");
    this.seedArtifact(rootGroup, "beta", "3.0");
    this.seedArtifact(nestedGroup, "gamma", "1.0");
    this.seedArtifact(nestedGroup, "delta", "1.0", "2.0");
    this.seedJars(rootGroup, "alpha", "1.0", "2.0");
    this.seedJars(rootGroup, "beta", "3.0");
    this.seedJars(nestedGroup, "gamma", "1.0");
    this.seedJars(nestedGroup, "delta", "1.0", "2.0");

    final var nestedFilesBefore =
        this.storedFiles().stream()
            .filter(file -> file.startsWith("com/acme/sub/"))
            .collect(Collectors.toSet());
    // 2 artifacts x (metadata + versions x (pom, jar)): the nested group holds 2 + 6 = 8 files.
    assertThat(nestedFilesBefore).hasSize(2 + 3 * 2);

    this.mockMvc
        .perform(
            get("/api/mvn/groups/{repo}/{group}", this.repoName, rootGroup)
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.artifactCount").value(2))
        .andExpect(jsonPath("$.data.versionCount").value(3));

    this.mockMvc
        .perform(
            delete("/api/mvn/artifacts/{repo}/{group}", this.repoName, rootGroup)
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("GROUP"));

    // What the confirmation counted is gone: rows and files.
    this.assertArtifactGone(rootGroup, "alpha");
    this.assertArtifactGone(rootGroup, "beta");
    assertThat(this.artifactRepository.countByRepoIdAndGroupName(this.repo.getId(), rootGroup))
        .isZero();

    // The nested group is complete: rows, versions and every file (nothing deleted, nothing
    // moved).
    this.assertArtifactExists(nestedGroup, "gamma");
    this.assertVersionExists(nestedGroup, "gamma", "1.0");
    this.assertArtifactExists(nestedGroup, "delta");
    this.assertVersionExists(nestedGroup, "delta", "1.0");
    this.assertVersionExists(nestedGroup, "delta", "2.0");
    assertThat(this.artifactRepository.countByRepoIdAndGroupName(this.repo.getId(), nestedGroup))
        .isEqualTo(2);

    // Storage and database agree: the only files left are the nested group's, none orphaned under
    // the root group's directory.
    assertThat(this.storedFiles()).isEqualTo(nestedFilesBefore);

    // The nested group's summary is unchanged by the root group's deletion.
    this.mockMvc
        .perform(
            get("/api/mvn/groups/{repo}/{group}", this.repoName, nestedGroup)
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.artifactCount").value(2))
        .andExpect(jsonPath("$.data.versionCount").value(3));
  }

  @Test
  @DisplayName(
      "deleting the root group also removes its own group-level maven-metadata.xml and leaves the"
          + " nested group's")
  void deletingRootGroupRemovesItsGroupLevelMetadataOnly() throws Exception {
    final var rootGroup = "com.acme";
    final var nestedGroup = "com.acme.sub";

    this.seedArtifact(rootGroup, "alpha", "1.0");
    this.seedArtifact(nestedGroup, "gamma", "1.0");

    final var rootMetadata = this.artifactDir(rootGroup, "alpha").getParent();
    final var nestedMetadata = this.artifactDir(nestedGroup, "gamma").getParent();
    for (final var dir : List.of(rootMetadata, nestedMetadata)) {
      Files.writeString(dir.resolve("maven-metadata.xml"), "<metadata/>", StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("maven-metadata.xml.sha1"), "abc", StandardCharsets.UTF_8);
    }

    this.mockMvc
        .perform(
            delete("/api/mvn/artifacts/{repo}/{group}", this.repoName, rootGroup)
                .header(AUTHORIZATION, this.bearerToken())
                .with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("GROUP"));

    assertThat(rootMetadata.resolve("maven-metadata.xml")).as("root group metadata").doesNotExist();
    assertThat(rootMetadata.resolve("maven-metadata.xml.sha1"))
        .as("root group metadata checksum")
        .doesNotExist();
    assertThat(nestedMetadata.resolve("maven-metadata.xml")).as("nested metadata").exists();
    assertThat(nestedMetadata.resolve("maven-metadata.xml.sha1")).as("nested checksum").exists();
    this.assertArtifactExists(nestedGroup, "gamma");
  }
}
