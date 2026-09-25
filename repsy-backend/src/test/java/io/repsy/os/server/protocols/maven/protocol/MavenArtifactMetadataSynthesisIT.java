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
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.ETAG;
import static org.springframework.http.HttpHeaders.LAST_MODIFIED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1369: the artifact-level {@code maven-metadata.xml} (and its checksums) of an artifact whose
 * client stored none, which is what sbt and Ivy publish, is answered from the registered versions.
 * Without it Maven's {@code LATEST} and ranges, Gradle's {@code 1.+} and sbt's {@code
 * latest.release} found nothing. A stored file always wins, and nothing is written.
 *
 * <p>The rows are seeded directly and the requests run on the test thread, so the read-only lookup
 * joins the transaction that is rolled back afterwards.
 */
@DisplayName("Maven artifact-level metadata is generated when none is stored (RPS-1369)")
class MavenArtifactMetadataSynthesisIT extends AbstractIntegrationTest {

  private static final String GROUP = "com.acme";
  private static final String GROUP_PATH = "com/acme";
  private static final String ARTIFACT = "lib";
  private static final String METADATA = GROUP_PATH + "/" + ARTIFACT + "/maven-metadata.xml";
  private static final Instant OLD = Instant.parse("2026-09-20T10:10:10Z");
  private static final Instant NEW = Instant.parse("2026-09-21T11:12:13Z");

  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private MockHttpServletResponse getFile(final Repo repo, final String path) throws Exception {
    return protocol(get("/{repo}/{path}", repo.getName(), path));
  }

  private MockHttpServletResponse headFile(final Repo repo, final String path) throws Exception {
    return protocol(head("/{repo}/{path}", repo.getName(), path));
  }

  private Repo seed(final boolean privateRepo) {
    return this.seedRepo(RepoType.MAVEN, uniqueRepoName("meta-mvn"), privateRepo, null);
  }

  /** Registers an artifact with these versions ({@code -SNAPSHOT} ones as snapshots), rows only. */
  private void register(
      final Repo repo,
      final String artifactId,
      final Instant lastUpdatedAt,
      final String... names) {
    final var artifact = new Artifact();
    artifact.setRepo(repo);
    artifact.setGroupName(GROUP);
    artifact.setArtifactName(artifactId);
    artifact.setName(artifactId);
    artifact.setPackaging("jar");
    artifact.setPlugin(false);
    artifact.setCreatedAt(OLD);
    artifact.setLastUpdatedAt(lastUpdatedAt);
    this.artifactRepository.saveAndFlush(artifact);

    for (int i = 0; i < names.length; i++) {
      final var version = new ArtifactVersion();
      version.setArtifact(artifact);
      version.setVersionName(names[i]);
      version.setType(
          names[i].endsWith("-SNAPSHOT")
              ? ArtifactVersionType.SNAPSHOT
              : ArtifactVersionType.RELEASE);
      version.setName(artifactId);
      version.setPackaging("jar");
      version.setCreatedAt(OLD);
      // The last one is the newest: it decides the lastUpdated of the file.
      version.setLastUpdatedAt(i == names.length - 1 ? lastUpdatedAt : OLD);
      this.artifactVersionRepository.saveAndFlush(version);
    }
  }

  private void store(final Repo repo, final String path, final String content) throws IOException {
    final var file = storageDirOf(repo).resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  @DisplayName("lists the registered versions sorted, with latest, release and lastUpdated")
  void generatesTheFile() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "1.10", "1.2", "2.0-SNAPSHOT");

    final var response = getFile(repo, METADATA);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo("application/octet-stream");
    assertThat(response.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=maven-metadata.xml");
    assertThat(response.getHeader(LAST_MODIFIED)).isNull();
    assertThat(response.getHeader(ETAG)).isNull();

    final var metadata = ArtifactUtils.readMetadata(response.getContentAsByteArray());

    assertThat(metadata.getGroupId()).isEqualTo(GROUP);
    assertThat(metadata.getArtifactId()).isEqualTo(ARTIFACT);
    assertThat(metadata.getVersioning().getVersions())
        .containsExactly("1.0", "1.2", "1.10", "2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getLatest()).isEqualTo("2.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getRelease()).isEqualTo("1.10");
    assertThat(metadata.getVersioning().getLastUpdated()).isEqualTo("20260921111213");
  }

  @Test
  @DisplayName("is the same bytes on every request, and its checksums are those of the bytes")
  void checksumsMatch() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "1.1");

    final var xml = getFile(repo, METADATA).getContentAsByteArray();

    assertThat(getFile(repo, METADATA).getContentAsByteArray()).isEqualTo(xml);

    final var md5 = getFile(repo, METADATA + ".md5");
    final var sha1 = getFile(repo, METADATA + ".sha1");

    assertThat(md5.getStatus()).isEqualTo(200);
    assertThat(md5.getContentAsString()).isEqualTo(DigestUtils.md5Hex(xml));
    assertThat(sha1.getContentAsString()).isEqualTo(DigestUtils.sha1Hex(xml));
    assertThat(getFile(repo, METADATA + ".sha256").getContentAsString())
        .isEqualTo(DigestUtils.sha256Hex(xml));
    assertThat(getFile(repo, METADATA + ".sha512").getContentAsString())
        .isEqualTo(DigestUtils.sha512Hex(xml));
    assertThat(sha1.getContentType()).isEqualTo("application/octet-stream");
    assertThat(sha1.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=maven-metadata.xml.sha1");
  }

  @Test
  @DisplayName("a HEAD of the file and of a checksum has the status and headers of the GET")
  void headMirrorsGet() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "1.1");

    for (final var path : new String[] {METADATA, METADATA + ".sha1"}) {
      final var head = headFile(repo, path);
      final var get = getFile(repo, path);

      assertThat(head.getStatus()).isEqualTo(200);
      assertThat(head.getContentAsByteArray()).isEmpty();
      assertThat(head.getHeader(CONTENT_LENGTH))
          .isEqualTo(String.valueOf(get.getContentAsByteArray().length));
      assertThat(head.getContentType()).isEqualTo(get.getContentType());
      assertThat(head.getHeader(CONTENT_DISPOSITION)).isEqualTo(get.getHeader(CONTENT_DISPOSITION));
    }
  }

  @Test
  @DisplayName("has no release for an artifact that has only snapshots")
  void snapshotsOnly() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0-SNAPSHOT");

    final var metadata =
        ArtifactUtils.readMetadata(getFile(repo, METADATA).getContentAsByteArray());

    assertThat(metadata.getVersioning().getLatest()).isEqualTo("1.0-SNAPSHOT");
    assertThat(metadata.getVersioning().getRelease()).isNull();
  }

  @Test
  @DisplayName("a version registered later is listed by the next request")
  void followsTheRows() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "1.1");

    final var before = getFile(repo, METADATA).getContentAsString();

    final var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(repo.getId(), GROUP, ARTIFACT)
            .orElseThrow();
    final var version = new ArtifactVersion();
    version.setArtifact(artifact);
    version.setVersionName("1.2");
    version.setType(ArtifactVersionType.RELEASE);
    version.setName(ARTIFACT);
    version.setPackaging("jar");
    version.setCreatedAt(NEW.plusSeconds(60));
    this.artifactVersionRepository.saveAndFlush(version);

    final var after = getFile(repo, METADATA).getContentAsString();

    assertThat(before).doesNotContain("<version>1.2</version>");
    assertThat(after).contains("<version>1.2</version>", "<release>1.2</release>");
  }

  @Test
  @DisplayName("a stored file is served as it is, and its missing checksum stays a 404")
  void storedFileWins() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "1.1");
    final var stored =
        "<metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning>"
            + "<versions><version>1.0</version></versions></versioning></metadata>";
    store(repo, METADATA, stored);

    final var xml = getFile(repo, METADATA);

    assertThat(xml.getStatus()).isEqualTo(200);
    assertThat(xml.getContentAsString()).isEqualTo(stored);
    assertThat(getFile(repo, METADATA + ".sha1").getStatus()).isEqualTo(404);
    assertThat(headFile(repo, METADATA + ".sha1").getStatus()).isEqualTo(404);

    store(repo, METADATA + ".sha1", "0123456789abcdef");

    assertThat(getFile(repo, METADATA + ".sha1").getContentAsString())
        .isEqualTo("0123456789abcdef");
  }

  @Test
  @DisplayName("a signature and the version-level file are not generated")
  void notGenerated() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "2.0-SNAPSHOT");

    for (final var path :
        new String[] {
          METADATA + ".asc",
          METADATA + ".asc.sha1",
          GROUP_PATH + "/" + ARTIFACT + "/2.0-SNAPSHOT/maven-metadata.xml",
          GROUP_PATH + "/" + ARTIFACT + "/2.0-SNAPSHOT/maven-metadata.xml.sha1",
          "com/maven-metadata.xml"
        }) {
      assertThat(getFile(repo, path).getStatus()).as("GET " + path).isEqualTo(404);
      assertThat(headFile(repo, path).getStatus()).as("HEAD " + path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName(
      "a file stored at version level does not stop the artifact level from being generated")
  void versionLevelStoredOnly() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0", "2.0-SNAPSHOT");
    final var versionLevel = GROUP_PATH + "/" + ARTIFACT + "/2.0-SNAPSHOT/maven-metadata.xml";
    store(repo, versionLevel, "<metadata><version>2.0-SNAPSHOT</version></metadata>");

    assertThat(getFile(repo, versionLevel).getContentAsString())
        .isEqualTo("<metadata><version>2.0-SNAPSHOT</version></metadata>");

    final var artifactLevel = getFile(repo, METADATA);

    assertThat(artifactLevel.getStatus()).isEqualTo(200);
    assertThat(artifactLevel.getContentAsString(StandardCharsets.UTF_8))
        .contains("<version>1.0</version>", "<version>2.0-SNAPSHOT</version>");
  }

  @Test
  @DisplayName("an unknown artifact, and a directory that holds files but no row, are a 404")
  void unknownArtifact() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0");
    store(repo, GROUP_PATH + "/ghost/1.0/ghost-1.0.jar", "jar");

    for (final var path :
        new String[] {
          GROUP_PATH + "/nope/maven-metadata.xml",
          GROUP_PATH + "/ghost/maven-metadata.xml",
          GROUP_PATH + "/ghost/maven-metadata.xml.sha1"
        }) {
      assertThat(getFile(repo, path).getStatus()).as("GET " + path).isEqualTo(404);
      assertThat(headFile(repo, path).getStatus()).as("HEAD " + path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("is per repo: another repo's artifact of the same coordinates is not listed")
  void perRepo() throws Exception {
    final var repo = seed(false);
    final var other = seed(false);
    register(other, ARTIFACT, NEW, "1.0");

    assertThat(getFile(repo, METADATA).getStatus()).isEqualTo(404);
    assertThat(getFile(other, METADATA).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a private repo challenges an anonymous request, and answers one with credentials")
  void privateRepo() throws Exception {
    final var repo = seed(true);
    register(repo, ARTIFACT, NEW, "1.0");

    assertThat(getFile(repo, METADATA).getStatus()).isEqualTo(401);
    assertThat(headFile(repo, METADATA).getStatus()).isEqualTo(401);
    // a missing artifact is 401 too: an anonymous client learns nothing about the rows
    assertThat(getFile(repo, GROUP_PATH + "/nope/maven-metadata.xml").getStatus()).isEqualTo(401);

    final var token = this.adminProtocolBearerToken();
    final var get =
        protocol(get("/{repo}/{path}", repo.getName(), METADATA).header(AUTHORIZATION, token));
    final var head =
        protocol(head("/{repo}/{path}", repo.getName(), METADATA).header(AUTHORIZATION, token));

    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(get.getContentAsString()).contains("<version>1.0</version>");
    assertThat(head.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("the directory listing does not list the generated file")
  void notListed() throws Exception {
    final var repo = seed(false);
    register(repo, ARTIFACT, NEW, "1.0");
    store(repo, GROUP_PATH + "/" + ARTIFACT + "/1.0/lib-1.0.jar", "jar");

    final var listing = getFile(repo, GROUP_PATH + "/" + ARTIFACT + "/");

    assertThat(listing.getStatus()).isEqualTo(200);
    assertThat(listing.getContentAsString()).contains("1.0/").doesNotContain("maven-metadata.xml");
  }
}
