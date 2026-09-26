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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1457: a stored group-level {@code maven-metadata.xml} (uploaded by {@code mvn deploy} of a
 * plugin) must not hide the plugins that Gradle, sbt or Ivy register afterwards, and those send no
 * group-level file. When the POM of a plugin registers, the file gets a {@code <plugin>} entry for
 * every registered plugin of the group whose artifactId it lacks, with the checksums that are
 * stored rewritten and a stored signature of the file dropped.
 *
 * <p>Runs without a test transaction, like {@link MavenStoredMetadataAppendIT}: registering a POM
 * commits its rows in a transaction of their own, and the plugin list the rewrite reads is that of
 * committed rows. It deletes the repos and users it commits. {@link UsageUpdateService} is mocked,
 * so the usage an upload reported can be checked to the byte.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName(
    "A registered plugin POM completes the stored group-level maven-metadata.xml (RPS-1457)")
class MavenStoredGroupMetadataAppendIT extends AbstractIntegrationTest {

  private static final String GROUP = "com.example.tools";
  private static final String GROUP_DIR = "com/example/tools/";
  private static final String METADATA_PATH = GROUP_DIR + "maven-metadata.xml";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private String token;

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the artifacts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("group-append");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    if (this.token == null) {
      final var userInfo =
          this.userTxService.create(
              uniqueUsername("maven-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
      this.createdUserIds.add(userInfo.getId());
      this.token =
          this.protocolBearerTokenFor(this.userRepository.findById(userInfo.getId()).orElseThrow());
    }

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private static String pom(
      final String group, final String artifactId, final String name, final String packaging) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + group
        + "</groupId><artifactId>"
        + artifactId
        + "</artifactId><version>1.0</version><packaging>"
        + packaging
        + "</packaging><name>"
        + name
        + "</name></project>";
  }

  private static String pomPath(final String group, final String artifactId) {
    return group.replace('.', '/') + "/" + artifactId + "/1.0/" + artifactId + "-1.0.pom";
  }

  private MockHttpServletResponse putFile(final Repo repo, final String path, final String body)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{path}", repo.getName(), path)
                .header(AUTHORIZATION, this.token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse getFile(final Repo repo, final String path) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/{path}", repo.getName(), path)
                .header(AUTHORIZATION, this.token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void deployPlugin(final Repo repo, final String group, final String artifactId)
      throws Exception {
    final var response =
        putFile(
            repo,
            pomPath(group, artifactId),
            pom(group, artifactId, artifactId.toUpperCase(), "maven-plugin"));

    assertThat(response.getStatus()).as("PUT pom " + artifactId).isEqualTo(200);
  }

  private void deployPlugin(final Repo repo, final String artifactId) throws Exception {
    deployPlugin(repo, GROUP, artifactId);
  }

  /** What Maven uploads next to a group-level file: the file and the digests it computed of it. */
  private void deployGroupMetadata(final Repo repo, final String xml) throws Exception {
    assertThat(putFile(repo, METADATA_PATH, xml).getStatus()).isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".sha1", DigestUtils.sha1Hex(xml)).getStatus())
        .isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".md5", DigestUtils.md5Hex(xml)).getStatus())
        .isEqualTo(200);
  }

  private static String pluginsXml(final String... artifactIdAndPrefix) {
    final var listed = new StringBuilder();

    for (final var entry : artifactIdAndPrefix) {
      final var parts = entry.split("=");

      listed
          .append("<plugin><name>")
          .append(parts[0])
          .append("</name><prefix>")
          .append(parts[1])
          .append("</prefix><artifactId>")
          .append(parts[0])
          .append("</artifactId></plugin>");
    }

    return "<metadata><plugins>" + listed + "</plugins></metadata>";
  }

  private static Path file(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private static byte[] bytesOf(final Repo repo, final String path) throws Exception {
    return Files.readAllBytes(file(repo, path));
  }

  private static void writeToStorage(final Repo repo, final String path, final String content)
      throws Exception {
    Files.createDirectories(file(repo, path).getParent());
    Files.writeString(file(repo, path), content);
  }

  private List<Plugin> listedPlugins(final Repo repo, final String path) throws Exception {
    final var response = getFile(repo, path);

    assertThat(response.getStatus()).isEqualTo(200);

    return ArtifactUtils.readMetadata(response.getContentAsByteArray()).getPlugins();
  }

  private void assertUsageOf(final Repo repo, final long bytes) {
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(bytes)));
  }

  @Test
  @DisplayName("a plugin published without the file is added to the file Maven stored")
  void mavenThenGradle() throws Exception {
    final var repo = this.mavenRepo();
    deployPlugin(repo, "foo-maven-plugin");
    deployGroupMetadata(repo, pluginsXml("foo-maven-plugin=foo"));
    final var before = bytesOf(repo, METADATA_PATH).length;
    clearInvocations(this.usageUpdateService);

    deployPlugin(repo, "bar-maven-plugin");

    final var response = getFile(repo, METADATA_PATH);
    final var plugins = ArtifactUtils.readMetadata(response.getContentAsByteArray()).getPlugins();

    assertThat(plugins)
        .extracting(Plugin::getArtifactId, Plugin::getPrefix, Plugin::getName)
        .containsExactly(
            tuple("foo-maven-plugin", "foo", "foo-maven-plugin"),
            tuple("bar-maven-plugin", "bar", "BAR-MAVEN-PLUGIN"));
    assertThat(getFile(repo, METADATA_PATH + ".sha1").getContentAsString())
        .isEqualTo(DigestUtils.sha1Hex(response.getContentAsByteArray()));
    assertThat(getFile(repo, METADATA_PATH + ".md5").getContentAsString())
        .isEqualTo(DigestUtils.md5Hex(response.getContentAsByteArray()));
    // Only the checksums that were stored are rewritten, none is created.
    assertThat(file(repo, METADATA_PATH + ".sha256")).doesNotExist();
    assertThat(getFile(repo, METADATA_PATH + ".sha256").getStatus()).isEqualTo(404);

    // The POM and the file that grew: the digests keep their 40 and 32 characters.
    final var pomBytes =
        pom(GROUP, "bar-maven-plugin", "BAR-MAVEN-PLUGIN", "maven-plugin")
            .getBytes(StandardCharsets.UTF_8)
            .length;

    assertUsageOf(repo, pomBytes + (bytesOf(repo, METADATA_PATH).length - before));
  }

  @Test
  @DisplayName("drops the stored signature of the file and reports every byte of the change")
  void staleSignatureIsDroppedAndCounted() throws Exception {
    final var repo = this.mavenRepo();
    deployPlugin(repo, "foo-maven-plugin");
    deployGroupMetadata(repo, pluginsXml("foo-maven-plugin=foo"));
    final var signature = "-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n";
    final var signatureChecksum = DigestUtils.sha1Hex(signature);
    assertThat(putFile(repo, METADATA_PATH + ".asc", signature).getStatus()).isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".asc.sha1", signatureChecksum).getStatus())
        .isEqualTo(200);
    final var before = bytesOf(repo, METADATA_PATH).length;
    clearInvocations(this.usageUpdateService);

    deployPlugin(repo, "bar-maven-plugin");

    assertThat(file(repo, METADATA_PATH + ".asc")).doesNotExist();
    assertThat(file(repo, METADATA_PATH + ".asc.sha1")).doesNotExist();
    assertThat(getFile(repo, METADATA_PATH + ".asc").getStatus()).isEqualTo(404);

    final var pomBytes =
        pom(GROUP, "bar-maven-plugin", "BAR-MAVEN-PLUGIN", "maven-plugin")
            .getBytes(StandardCharsets.UTF_8)
            .length;
    final var grown = bytesOf(repo, METADATA_PATH).length - before;

    assertUsageOf(repo, pomBytes + grown - signature.length() - signatureChecksum.length());
  }

  @Test
  @DisplayName("a plugin the file lists under its own prefix is left alone, with the signature")
  void aListedPluginIsNotTouched() throws Exception {
    final var repo = this.mavenRepo();
    // Maven wrote the real goalPrefix of bar, not the one derived from its artifactId.
    deployGroupMetadata(repo, pluginsXml("bar-maven-plugin=custom"));
    assertThat(putFile(repo, METADATA_PATH + ".asc", "signature").getStatus()).isEqualTo(200);
    final var before = bytesOf(repo, METADATA_PATH);
    final var sha1Before = bytesOf(repo, METADATA_PATH + ".sha1");

    deployPlugin(repo, "bar-maven-plugin");

    assertThat(bytesOf(repo, METADATA_PATH)).isEqualTo(before);
    assertThat(bytesOf(repo, METADATA_PATH + ".sha1")).isEqualTo(sha1Before);
    assertThat(getFile(repo, METADATA_PATH + ".asc").getContentAsString()).isEqualTo("signature");
  }

  @Test
  @DisplayName("the POM of an ordinary artifact of the group leaves the file as it is")
  void anOrdinaryPomIsNotAPlugin() throws Exception {
    final var repo = this.mavenRepo();
    deployPlugin(repo, "bar-maven-plugin");
    deployGroupMetadata(repo, pluginsXml("foo-maven-plugin=foo"));
    final var before = bytesOf(repo, METADATA_PATH);

    assertThat(putFile(repo, pomPath(GROUP, "lib"), pom(GROUP, "lib", "lib", "jar")).getStatus())
        .isEqualTo(200);

    assertThat(bytesOf(repo, METADATA_PATH)).isEqualTo(before);
  }

  @Test
  @DisplayName("a stored file that cannot be parsed is left as it is and the upload still succeeds")
  void unparsableFileIsLeftAlone() throws Exception {
    final var repo = this.mavenRepo();
    writeToStorage(repo, METADATA_PATH, "<metadata><plugins>");

    deployPlugin(repo, "bar-maven-plugin");

    assertThat(Files.readString(file(repo, METADATA_PATH))).isEqualTo("<metadata><plugins>");
    assertThat(getFile(repo, pomPath(GROUP, "bar-maven-plugin")).getStatus()).isEqualTo(200);
    assertThat(
            this.jdbcTemplate.queryForObject(
                "select count(*) from maven_artifact_version av join maven_artifact a on a.id = av.artifact_id"
                    + " where a.repo_id = ?",
                Long.class,
                repo.getId()))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("the artifact-level file that has the path of the group's file is left as it is")
  void anArtifactLevelFileAtTheSamePathIsLeftAlone() throws Exception {
    final var repo = this.mavenRepo();
    // com.example:tools is an artifact whose path is the path of the group com.example.tools.
    final var artifactLevel =
        "<metadata><groupId>com.example</groupId><artifactId>tools</artifactId><versioning>"
            + "<latest>1.0</latest><release>1.0</release><versions><version>1.0</version>"
            + "</versions><lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
    writeToStorage(repo, METADATA_PATH, artifactLevel);

    deployPlugin(repo, "bar-maven-plugin");

    assertThat(Files.readString(file(repo, METADATA_PATH))).isEqualTo(artifactLevel);
  }

  @Test
  @DisplayName("a group with no stored file gets none, and is still answered a generated one")
  void noStoredFileNoneIsCreated() throws Exception {
    final var repo = this.mavenRepo();
    deployPlugin(repo, "foo-maven-plugin");
    deployPlugin(repo, "bar-maven-plugin");

    assertThat(file(repo, METADATA_PATH)).doesNotExist();
    assertThat(listedPlugins(repo, METADATA_PATH))
        .extracting(Plugin::getArtifactId)
        .containsExactly("bar-maven-plugin", "foo-maven-plugin");
    assertThat(file(repo, METADATA_PATH)).doesNotExist();
  }

  @Test
  @DisplayName("the file of a group of one segment is completed too")
  void oneSegmentGroup() throws Exception {
    final var repo = this.mavenRepo();
    final var path = "e2eplug/maven-metadata.xml";
    writeToStorage(repo, path, pluginsXml("foo-maven-plugin=foo"));

    deployPlugin(repo, "e2eplug", "bar-maven-plugin");

    assertThat(listedPlugins(repo, path))
        .extracting(Plugin::getArtifactId)
        .containsExactly("foo-maven-plugin", "bar-maven-plugin");
  }

  @Test
  @DisplayName("a file that already misses earlier plugins gets all of them with the next POM")
  void healsAFileThatLagsBehind() throws Exception {
    final var repo = this.mavenRepo();
    deployPlugin(repo, "bar-maven-plugin");
    deployPlugin(repo, "baz-maven-plugin");
    // The file a Maven client stored after it read a list that lacked bar and baz.
    writeToStorage(repo, METADATA_PATH, pluginsXml("foo-maven-plugin=foo"));

    deployPlugin(repo, "foo-maven-plugin");

    assertThat(listedPlugins(repo, METADATA_PATH))
        .extracting(Plugin::getArtifactId)
        .containsExactly("foo-maven-plugin", "bar-maven-plugin", "baz-maven-plugin");
  }

  @Test
  @DisplayName("six plugins registered at once are all listed afterwards")
  void parallelPluginUploadsAreAllListed() throws Exception {
    final var repo = this.mavenRepo();
    deployGroupMetadata(repo, pluginsXml("foo-maven-plugin=foo"));
    final var artifactIds =
        List.of("a", "b", "c", "d", "e", "f").stream().map(s -> s + "-maven-plugin").toList();

    try (final var pool = Executors.newFixedThreadPool(artifactIds.size())) {
      final var uploads =
          artifactIds.stream()
              .map(
                  artifactId ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              return putFile(
                                      repo,
                                      pomPath(GROUP, artifactId),
                                      pom(GROUP, artifactId, artifactId, "maven-plugin"))
                                  .getStatus();
                            } catch (final Exception e) {
                              throw new IllegalStateException(e);
                            }
                          },
                          pool))
              .toList();

      for (final var upload : uploads) {
        assertThat(upload.get(60, TimeUnit.SECONDS)).isEqualTo(200);
      }
    }

    assertThat(listedPlugins(repo, METADATA_PATH))
        .extracting(Plugin::getArtifactId)
        .containsExactlyInAnyOrder(
            "foo-maven-plugin",
            "a-maven-plugin",
            "b-maven-plugin",
            "c-maven-plugin",
            "d-maven-plugin",
            "e-maven-plugin",
            "f-maven-plugin");
  }
}
