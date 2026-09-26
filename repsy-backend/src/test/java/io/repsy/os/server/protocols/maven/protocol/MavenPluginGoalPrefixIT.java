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
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1458: a plugin that sets its own {@code goalPrefix} is run by that prefix, which its jar
 * names in {@code META-INF/maven/plugin.xml}. When the POM of a plugin registers and the jar of
 * that version is stored already (Gradle's {@code maven-publish}, sbt and Ivy send the jar first),
 * the prefix of the artifact and of the version is the one of the descriptor, and the group-level
 * {@code maven-metadata.xml} Repsy answers lists it. A POM that arrives before its jar gets the
 * prefix derived from the artifactId until it is uploaded again.
 *
 * <p>Runs without a test transaction: registering a POM commits its rows in a transaction of its
 * own. It deletes the repos and users it commits. {@link UsageUpdateService} is mocked.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A plugin POM registers the goalPrefix of its stored jar (RPS-1458)")
class MavenPluginGoalPrefixIT extends AbstractIntegrationTest {

  private static final String GROUP = "com.example.prefixes";
  private static final String GROUP_DIR = "com/example/prefixes/";
  private static final String METADATA_PATH = GROUP_DIR + "maven-metadata.xml";
  private static final String ARTIFACT_ID = "foo-maven-plugin";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private String token;

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("goal-prefix");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    if (this.token == null) {
      final var userInfo =
          this.userTxService.create(
              uniqueUsername("maven-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
      this.createdUserIds.add(userInfo.getId());
      this.token =
          this.protocolBearerTokenFor(this.userRepository.findById(userInfo.getId()).orElseThrow());
    }

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private static byte[] jar(final String artifactId, final String goalPrefix) throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("META-INF/maven/plugin.xml"));
      zip.write(
          ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugin><name>x</name><groupId>"
                  + GROUP
                  + "</groupId><artifactId>"
                  + artifactId
                  + "</artifactId><version>1.0</version><goalPrefix>"
                  + goalPrefix
                  + "</goalPrefix><mojos><mojo><goal>hi</goal></mojo></mojos></plugin>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    return bytes.toByteArray();
  }

  /** A descriptor whose goalPrefix would be the content of a file, if the entity were resolved. */
  private static byte[] jarWithExternalEntity(final String artifactId, final Path secret)
      throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("META-INF/maven/plugin.xml"));
      zip.write(
          ("<?xml version=\"1.0\"?><!DOCTYPE plugin [<!ENTITY xxe SYSTEM \""
                  + secret.toUri()
                  + "\">]><plugin><artifactId>"
                  + artifactId
                  + "</artifactId><goalPrefix>&xxe;</goalPrefix></plugin>")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    return bytes.toByteArray();
  }

  private static byte[] jarWithoutDescriptor() throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("com/example/Mojo.class"));
      zip.write(new byte[64]);
      zip.closeEntry();
    }

    return bytes.toByteArray();
  }

  private static String pom(final String artifactId) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + GROUP
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>1.0</version><packaging>maven-plugin</packaging><name>"
        + artifactId
        + "</name></project>";
  }

  private static String versionFile(final String artifactId, final String extension) {
    return GROUP_DIR + artifactId + "/1.0/" + artifactId + "-1.0." + extension;
  }

  private MockHttpServletResponse putFile(final Repo repo, final String path, final byte[] body)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{path}", repo.getName(), path)
                .header(AUTHORIZATION, this.token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void upload(final Repo repo, final String path, final byte[] body) throws Exception {
    assertThat(putFile(repo, path, body).getStatus()).as("PUT " + path).isEqualTo(200);
  }

  private void uploadJar(final Repo repo, final String artifactId, final byte[] jar)
      throws Exception {
    upload(repo, versionFile(artifactId, "jar"), jar);
  }

  private void uploadPom(final Repo repo, final String artifactId) throws Exception {
    upload(repo, versionFile(artifactId, "pom"), pom(artifactId).getBytes(StandardCharsets.UTF_8));
  }

  private List<Plugin> groupFile(final Repo repo) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/{repo}/{path}", repo.getName(), METADATA_PATH)
                    .header(AUTHORIZATION, this.token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);

    return ArtifactUtils.readMetadata(response.getContentAsByteArray()).getPlugins();
  }

  /** The prefix of the artifact row and of its version row. */
  private List<String> prefixesOf(final Repo repo, final String artifactId) {
    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repo.getId(), GROUP, artifactId)
            .orElseThrow();
    final var version =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(artifact.getId(), "1.0")
            .orElseThrow();

    return Arrays.asList(artifact.getPrefix(), version.getPrefix());
  }

  @Test
  @DisplayName("the jar stored before the POM gives both rows and the group file its goalPrefix")
  void jarThenPom() throws Exception {
    final var repo = this.mavenRepo();

    uploadJar(repo, ARTIFACT_ID, jar(ARTIFACT_ID, "custom"));
    uploadPom(repo, ARTIFACT_ID);

    assertThat(prefixesOf(repo, ARTIFACT_ID)).containsExactly("custom", "custom");
    assertThat(groupFile(repo))
        .extracting(Plugin::getArtifactId, Plugin::getPrefix)
        .containsExactly(tuple(ARTIFACT_ID, "custom"));
  }

  @Test
  @DisplayName("the POM before the jar has the derived prefix until the POM is uploaded again")
  void pomThenJar() throws Exception {
    final var repo = this.mavenRepo();

    uploadPom(repo, ARTIFACT_ID);
    uploadJar(repo, ARTIFACT_ID, jar(ARTIFACT_ID, "custom"));

    assertThat(prefixesOf(repo, ARTIFACT_ID)).containsExactly("foo", "foo");
    assertThat(groupFile(repo)).extracting(Plugin::getPrefix).containsExactly("foo");

    uploadPom(repo, ARTIFACT_ID);

    assertThat(prefixesOf(repo, ARTIFACT_ID)).containsExactly("custom", "custom");
    assertThat(groupFile(repo)).extracting(Plugin::getPrefix).containsExactly("custom");
  }

  @Test
  @DisplayName("no descriptor, a broken jar, another plugin's or an external entity: derived")
  void unusableJars(@TempDir final Path dir) throws Exception {
    final var repo = this.mavenRepo();
    final var secret = dir.resolve("secret.txt");

    uploadJar(repo, "plain-maven-plugin", jarWithoutDescriptor());
    uploadPom(repo, "plain-maven-plugin");
    uploadJar(repo, "broken-maven-plugin", "this is not a zip".getBytes(StandardCharsets.UTF_8));
    uploadPom(repo, "broken-maven-plugin");
    uploadJar(repo, "shaded-maven-plugin", jar("other-maven-plugin", "foreign"));
    uploadPom(repo, "shaded-maven-plugin");
    Files.writeString(secret, "leaked");
    uploadJar(repo, "xxe-maven-plugin", jarWithExternalEntity("xxe-maven-plugin", secret));
    uploadPom(repo, "xxe-maven-plugin");

    assertThat(prefixesOf(repo, "plain-maven-plugin")).containsExactly("plain", "plain");
    assertThat(prefixesOf(repo, "broken-maven-plugin")).containsExactly("broken", "broken");
    assertThat(prefixesOf(repo, "shaded-maven-plugin")).containsExactly("shaded", "shaded");
    assertThat(prefixesOf(repo, "xxe-maven-plugin")).containsExactly("xxe", "xxe");
  }

  @Test
  @DisplayName("a plugin with its own prefix is appended with it to a group file Maven stored")
  void appendedToAStoredGroupFileWithItsOwnPrefix() throws Exception {
    final var repo = this.mavenRepo();
    uploadJar(repo, "stored-maven-plugin", jar("stored-maven-plugin", "stored"));
    uploadPom(repo, "stored-maven-plugin");
    upload(
        repo,
        METADATA_PATH,
        ("<metadata><plugins><plugin><name>Stored</name><prefix>stored</prefix>"
                + "<artifactId>stored-maven-plugin</artifactId></plugin></plugins></metadata>")
            .getBytes(StandardCharsets.UTF_8));

    uploadJar(repo, ARTIFACT_ID, jar(ARTIFACT_ID, "custom"));
    uploadPom(repo, ARTIFACT_ID);

    assertThat(groupFile(repo))
        .extracting(Plugin::getArtifactId, Plugin::getPrefix)
        .containsExactly(tuple("stored-maven-plugin", "stored"), tuple(ARTIFACT_ID, "custom"));
  }
}
