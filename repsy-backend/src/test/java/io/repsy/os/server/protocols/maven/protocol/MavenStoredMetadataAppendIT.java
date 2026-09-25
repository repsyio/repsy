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
import java.util.stream.IntStream;
import org.apache.commons.codec.digest.DigestUtils;
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
 * RPS-1437: a stored artifact-level {@code maven-metadata.xml} (uploaded by Maven or Gradle) must
 * not hide the versions that Apache Ivy or sbt register afterwards, and those two send no metadata.
 * When a POM registers a version that the stored file lacks, the file gets it, with {@code latest},
 * {@code release}, {@code lastUpdated} and the checksums that are stored, and a stored signature of
 * the file is dropped.
 *
 * <p>Runs without a test transaction, like {@link MavenPomStorageConsistencyIT}: registering a POM
 * commits its rows in a transaction of their own, and the version list the rewrite reads is that of
 * committed rows. It deletes the repos and users it commits. {@link UsageUpdateService} is mocked,
 * so the usage an upload reported can be checked to the byte.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A registered POM adds its version to the stored maven-metadata.xml (RPS-1437)")
class MavenStoredMetadataAppendIT extends AbstractIntegrationTest {

  private static final String ARTIFACT_DIR = "com/example/lib/";
  private static final String METADATA_PATH = ARTIFACT_DIR + "maven-metadata.xml";

  private static final String POM_TEMPLATE =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>@VERSION@</version>
      </project>
      """;

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

  private Repo mavenRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("meta-append");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

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

  private static String pom(final String version) {
    return POM_TEMPLATE.replace("@VERSION@", version);
  }

  private static String pomPath(final String version) {
    return ARTIFACT_DIR + version + "/lib-" + version + ".pom";
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

  private void deployPom(final Repo repo, final String version) throws Exception {
    assertThat(putFile(repo, pomPath(version), pom(version)).getStatus())
        .as("PUT pom " + version)
        .isEqualTo(200);
  }

  /** What Maven uploads next to a metadata file: the file and the digests it computed of it. */
  private void deployMetadata(final Repo repo, final String... versions) throws Exception {
    final var xml = metadataXml(versions);

    assertThat(putFile(repo, METADATA_PATH, xml).getStatus()).isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".sha1", DigestUtils.sha1Hex(xml)).getStatus())
        .isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".md5", DigestUtils.md5Hex(xml)).getStatus())
        .isEqualTo(200);
  }

  private static String metadataXml(final String... versions) {
    final var listed = new StringBuilder();

    for (final var version : versions) {
      listed.append("<version>").append(version).append("</version>");
    }

    return "<metadata><groupId>com.example</groupId><artifactId>lib</artifactId><versioning>"
        + "<latest>"
        + versions[versions.length - 1]
        + "</latest><release>"
        + versions[versions.length - 1]
        + "</release><versions>"
        + listed
        + "</versions><lastUpdated>20260101000000</lastUpdated></versioning></metadata>";
  }

  private static Path file(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private static byte[] bytesOf(final Repo repo, final String path) throws Exception {
    return Files.readAllBytes(file(repo, path));
  }

  private List<String> listedVersions(final Repo repo) throws Exception {
    final var response = getFile(repo, METADATA_PATH);

    assertThat(response.getStatus()).isEqualTo(200);

    return ArtifactUtils.readMetadata(response.getContentAsByteArray())
        .getVersioning()
        .getVersions();
  }

  private void assertUsageOf(final Repo repo, final long bytes) {
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(bytes)));
  }

  @Test
  @DisplayName("Ivy publishing 2.0 after mvn deploy of 1.0 puts 2.0 in the file and moves LATEST")
  void mavenThenIvy() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");

    deployPom(repo, "2.0");

    final var response = getFile(repo, METADATA_PATH);
    final var metadata = ArtifactUtils.readMetadata(response.getContentAsByteArray());

    assertThat(metadata.getVersioning().getVersions()).containsExactly("1.0", "2.0");
    assertThat(metadata.getVersioning().getLatest()).isEqualTo("2.0");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("2.0");
    assertThat(metadata.getVersioning().getLastUpdated()).isNotEqualTo("20260101000000");
    assertThat(getFile(repo, METADATA_PATH + ".sha1").getContentAsString())
        .isEqualTo(DigestUtils.sha1Hex(response.getContentAsByteArray()));
    assertThat(getFile(repo, METADATA_PATH + ".md5").getContentAsString())
        .isEqualTo(DigestUtils.md5Hex(response.getContentAsByteArray()));
    // Only the checksums that were stored are rewritten, none is created.
    assertThat(file(repo, METADATA_PATH + ".sha256")).doesNotExist();
    assertThat(getFile(repo, METADATA_PATH + ".sha256").getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("drops the stored signature of the file and reports every byte of the change")
  void staleSignatureIsDroppedAndCounted() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");
    final var signature = "-----BEGIN PGP SIGNATURE-----\n\nabc\n-----END PGP SIGNATURE-----\n";
    final var signatureChecksum = DigestUtils.sha1Hex(signature);
    assertThat(putFile(repo, METADATA_PATH + ".asc", signature).getStatus()).isEqualTo(200);
    assertThat(putFile(repo, METADATA_PATH + ".asc.sha1", signatureChecksum).getStatus())
        .isEqualTo(200);
    final var before = bytesOf(repo, METADATA_PATH).length;
    clearInvocations(this.usageUpdateService);

    deployPom(repo, "2.0");

    assertThat(file(repo, METADATA_PATH + ".asc")).doesNotExist();
    assertThat(file(repo, METADATA_PATH + ".asc.sha1")).doesNotExist();
    assertThat(getFile(repo, METADATA_PATH + ".asc").getStatus()).isEqualTo(404);

    // The POM, the file that grew, and the two signature files that are gone. The digests keep
    // their 40 and 32 characters.
    final var pomBytes = pom("2.0").getBytes(StandardCharsets.UTF_8).length;
    final var grown = bytesOf(repo, METADATA_PATH).length - before;

    assertUsageOf(repo, pomBytes + grown - signature.length() - signatureChecksum.length());
  }

  @Test
  @DisplayName("a redeploy of a version the file lists leaves the file and its signature alone")
  void redeployOfAListedVersionChangesNothing() throws Exception {
    final var repo = this.mavenRepo(true);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");
    assertThat(putFile(repo, METADATA_PATH + ".asc", "signature").getStatus()).isEqualTo(200);
    final var before = bytesOf(repo, METADATA_PATH);
    final var sha1Before = bytesOf(repo, METADATA_PATH + ".sha1");

    deployPom(repo, "1.0");

    assertThat(bytesOf(repo, METADATA_PATH)).isEqualTo(before);
    assertThat(bytesOf(repo, METADATA_PATH + ".sha1")).isEqualTo(sha1Before);
    assertThat(getFile(repo, METADATA_PATH + ".asc").getContentAsString()).isEqualTo("signature");
  }

  @Test
  @DisplayName("a literal snapshot is listed as latest without moving release, once")
  void literalSnapshot() throws Exception {
    final var repo = this.mavenRepo(true);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");
    final var snapshot = "2.0-SNAPSHOT";

    deployPom(repo, snapshot);

    final var versioning =
        ArtifactUtils.readMetadata(getFile(repo, METADATA_PATH).getContentAsByteArray())
            .getVersioning();

    assertThat(versioning.getVersions()).containsExactly("1.0", snapshot);
    assertThat(versioning.getLatest()).isEqualTo(snapshot);
    assertThat(versioning.getRelease()).isEqualTo("1.0");

    final var written = bytesOf(repo, METADATA_PATH);

    deployPom(repo, snapshot);

    assertThat(bytesOf(repo, METADATA_PATH)).isEqualTo(written);
  }

  @Test
  @DisplayName("a file that already misses an earlier version gets all of them")
  void healsAFileThatLagsBehind() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployPom(repo, "2.0");
    // The file a Maven client stored after it read a list that lacked 2.0.
    deployMetadata(repo, "1.0");

    deployPom(repo, "3.0");

    assertThat(listedVersions(repo)).containsExactly("1.0", "2.0", "3.0");
  }

  @Test
  @DisplayName("a stored file that cannot be parsed is left as it is and the upload still succeeds")
  void unparsableFileIsLeftAlone() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    Files.createDirectories(file(repo, METADATA_PATH).getParent());
    Files.writeString(file(repo, METADATA_PATH), "<metadata><versioning>");

    deployPom(repo, "2.0");

    assertThat(Files.readString(file(repo, METADATA_PATH))).isEqualTo("<metadata><versioning>");
    assertThat(getFile(repo, pomPath("2.0")).getStatus()).isEqualTo(200);
    assertThat(
            this.jdbcTemplate.queryForObject(
                "select count(*) from maven_artifact_version av join maven_artifact a on a.id = av.artifact_id"
                    + " where a.repo_id = ?",
                Long.class,
                repo.getId()))
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("a stored file without a <versioning> is left as it is")
  void fileWithoutVersioningIsLeftAlone() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    Files.createDirectories(file(repo, METADATA_PATH).getParent());
    Files.writeString(file(repo, METADATA_PATH), "<metadata/>");

    deployPom(repo, "2.0");

    assertThat(Files.readString(file(repo, METADATA_PATH))).isEqualTo("<metadata/>");
  }

  @Test
  @DisplayName("a POM that is refused leaves the stored file as it was")
  void refusedPomChangesNothing() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");
    final var before = bytesOf(repo, METADATA_PATH);

    // Another group than its path, and a redeploy where overriding is off.
    assertThat(
            putFile(repo, pomPath("2.0"), pom("2.0").replace("com.example", "org.other"))
                .getStatus())
        .isEqualTo(400);
    assertThat(putFile(repo, pomPath("1.0"), pom("1.0")).getStatus()).isNotEqualTo(200);

    assertThat(bytesOf(repo, METADATA_PATH)).isEqualTo(before);
  }

  @Test
  @DisplayName("an artifact with no stored file gets none, and is still answered a generated one")
  void noStoredFileNoneIsCreated() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployPom(repo, "2.0");

    assertThat(file(repo, METADATA_PATH)).doesNotExist();
    assertThat(listedVersions(repo)).containsExactly("1.0", "2.0");
    assertThat(file(repo, METADATA_PATH)).doesNotExist();
  }

  @Test
  @DisplayName("eight versions registered at once are all listed afterwards")
  void parallelPomUploadsAreAllListed() throws Exception {
    final var repo = this.mavenRepo(false);
    deployPom(repo, "1.0");
    deployMetadata(repo, "1.0");
    final var versions = IntStream.rangeClosed(2, 9).mapToObj(i -> i + ".0").toList();

    try (final var pool = Executors.newFixedThreadPool(versions.size())) {
      final var uploads =
          versions.stream()
              .map(
                  version ->
                      CompletableFuture.supplyAsync(
                          () -> {
                            try {
                              return putFile(repo, pomPath(version), pom(version)).getStatus();
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

    assertThat(listedVersions(repo))
        .containsExactly("1.0", "2.0", "3.0", "4.0", "5.0", "6.0", "7.0", "8.0", "9.0");
  }
}
