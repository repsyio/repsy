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
import java.nio.file.Files;
import java.time.Instant;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1438: the group-level {@code maven-metadata.xml} (and its checksums), which Maven reads to
 * resolve a plugin prefix ({@code mvn hello:hi}), is answered from the registered plugins of the
 * group when the client that published the plugin stored none (Gradle, sbt, Ivy, a raw upload). A
 * stored file always wins, the artifact-level file of an artifact with the same path comes first,
 * and nothing is written.
 *
 * <p>The rows are seeded directly and the requests run on the test thread, so the read-only lookup
 * joins the transaction that is rolled back afterwards.
 */
@DisplayName("Maven group-level metadata is generated when none is stored (RPS-1438)")
class MavenGroupMetadataSynthesisIT extends AbstractIntegrationTest {

  private static final String GROUP = "com.acme.tools";
  private static final String GROUP_PATH = "com/acme/tools";
  private static final String METADATA = GROUP_PATH + "/maven-metadata.xml";
  private static final Instant AT = Instant.parse("2026-09-20T10:10:10Z");

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
    return this.seedRepo(RepoType.MAVEN, uniqueRepoName("group-mvn"), privateRepo, null);
  }

  /** Registers an artifact of the group with one version, rows only. */
  private void register(
      final Repo repo,
      final String group,
      final String artifactId,
      final boolean plugin,
      final String name,
      final String prefix) {
    final var artifact = new Artifact();
    artifact.setRepo(repo);
    artifact.setGroupName(group);
    artifact.setArtifactName(artifactId);
    artifact.setName(name);
    artifact.setPackaging(plugin ? "maven-plugin" : "jar");
    artifact.setPlugin(plugin);
    artifact.setPrefix(prefix);
    artifact.setCreatedAt(AT);
    artifact.setLastUpdatedAt(AT);
    this.artifactRepository.saveAndFlush(artifact);

    final var version = new ArtifactVersion();
    version.setArtifact(artifact);
    version.setVersionName("1.0");
    version.setType(ArtifactVersionType.RELEASE);
    version.setName(name);
    version.setPackaging(artifact.getPackaging());
    version.setCreatedAt(AT);
    version.setLastUpdatedAt(AT);
    this.artifactVersionRepository.saveAndFlush(version);
  }

  private void registerPlugin(
      final Repo repo, final String artifactId, final String name, final String prefix) {
    register(repo, GROUP, artifactId, true, name, prefix);
  }

  private void store(final Repo repo, final String path, final String content) throws IOException {
    final var file = storageDirOf(repo).resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  @DisplayName("lists the plugins of the group by artifactId, in the shape Maven writes")
  void generatesTheFile() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "zed-maven-plugin", null, "zed");
    registerPlugin(repo, "hello-maven-plugin", "Hello Maven Plugin", "hello");

    final var response = getFile(repo, METADATA);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo("application/octet-stream");
    assertThat(response.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=maven-metadata.xml");
    assertThat(response.getContentAsString())
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <plugins>
                <plugin>
                  <name>Hello Maven Plugin</name>
                  <prefix>hello</prefix>
                  <artifactId>hello-maven-plugin</artifactId>
                </plugin>
                <plugin>
                  <prefix>zed</prefix>
                  <artifactId>zed-maven-plugin</artifactId>
                </plugin>
              </plugins>
            </metadata>
            """);
  }

  @Test
  @DisplayName("is the same bytes on every request, and its checksums are those of the bytes")
  void checksumsMatch() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");

    final var xml = getFile(repo, METADATA).getContentAsByteArray();

    assertThat(getFile(repo, METADATA).getContentAsByteArray()).isEqualTo(xml);
    assertThat(getFile(repo, METADATA + ".md5").getContentAsString())
        .isEqualTo(DigestUtils.md5Hex(xml));
    assertThat(getFile(repo, METADATA + ".sha1").getContentAsString())
        .isEqualTo(DigestUtils.sha1Hex(xml));
    assertThat(getFile(repo, METADATA + ".sha256").getContentAsString())
        .isEqualTo(DigestUtils.sha256Hex(xml));
    assertThat(getFile(repo, METADATA + ".sha512").getContentAsString())
        .isEqualTo(DigestUtils.sha512Hex(xml));
  }

  @Test
  @DisplayName("a HEAD of the file and of a checksum has the status and headers of the GET")
  void headMirrorsGet() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");

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
  @DisplayName("a one-segment group is answered too")
  void singleSegmentGroup() throws Exception {
    final var repo = seed(false);
    register(repo, "acme", "hello-maven-plugin", true, "Hello", "hello");

    final var response = getFile(repo, "acme/maven-metadata.xml");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(ArtifactUtils.readMetadata(response.getContentAsByteArray()).getPlugins())
        .extracting(plugin -> plugin.getPrefix())
        .containsExactly("hello");
  }

  @Test
  @DisplayName("a group with no plugin, and a plugin with no prefix, are a 404")
  void groupWithoutPlugins() throws Exception {
    final var repo = seed(false);
    register(repo, GROUP, "lib", false, "lib", null);
    register(repo, GROUP + ".empty", "odd-maven-plugin", true, "Odd", null);
    register(repo, GROUP + ".blank", "blank-maven-plugin", true, "Blank", "");

    for (final var path :
        new String[] {
          METADATA,
          METADATA + ".sha1",
          GROUP_PATH + "/empty/maven-metadata.xml",
          GROUP_PATH + "/blank/maven-metadata.xml"
        }) {
      assertThat(getFile(repo, path).getStatus()).as("GET " + path).isEqualTo(404);
      assertThat(headFile(repo, path).getStatus()).as("HEAD " + path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("lists only the plugins of its own group, and only of its own repo")
  void perGroupAndRepo() throws Exception {
    final var repo = seed(false);
    final var other = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");
    register(repo, GROUP + ".sub", "sub-maven-plugin", true, "Sub", "sub");
    register(other, GROUP, "other-maven-plugin", true, "Other", "other");

    final var xml = getFile(repo, METADATA).getContentAsString();

    assertThat(xml).contains("hello-maven-plugin").doesNotContain("sub-", "other-");
    assertThat(getFile(other, METADATA).getContentAsString()).contains("other-maven-plugin");
    assertThat(getFile(repo, GROUP_PATH + "/sub/maven-metadata.xml").getContentAsString())
        .contains("sub-maven-plugin");
  }

  @Test
  @DisplayName("a stored file is served as it is, and its missing checksum stays a 404")
  void storedFileWins() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");
    final var stored = "<metadata><plugins/></metadata>";
    store(repo, METADATA, stored);

    assertThat(getFile(repo, METADATA).getContentAsString()).isEqualTo(stored);
    assertThat(getFile(repo, METADATA + ".sha1").getStatus()).isEqualTo(404);
    assertThat(headFile(repo, METADATA + ".sha1").getStatus()).isEqualTo(404);

    store(repo, METADATA + ".sha1", "0123456789abcdef");

    assertThat(getFile(repo, METADATA + ".sha1").getContentAsString())
        .isEqualTo("0123456789abcdef");
  }

  @Test
  @DisplayName("the artifact-level file of an artifact with the path of the group comes first")
  void artifactLevelComesFirst() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");
    // com.acme:tools is an artifact whose path is the path of the group com.acme.tools.
    register(repo, "com.acme", "tools", false, "tools", null);

    final var metadata =
        ArtifactUtils.readMetadata(getFile(repo, METADATA).getContentAsByteArray());

    assertThat(metadata.getGroupId()).isEqualTo("com.acme");
    assertThat(metadata.getArtifactId()).isEqualTo("tools");
    assertThat(metadata.getVersioning().getVersions()).containsExactly("1.0");
    assertThat(metadata.getPlugins()).isEmpty();
  }

  @Test
  @DisplayName("a signature and the version-level file are not generated")
  void notGenerated() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");

    for (final var path :
        new String[] {
          METADATA + ".asc",
          METADATA + ".asc.sha1",
          GROUP_PATH + "/1.0-SNAPSHOT/maven-metadata.xml",
          "maven-metadata.xml"
        }) {
      assertThat(getFile(repo, path).getStatus()).as("GET " + path).isEqualTo(404);
      assertThat(headFile(repo, path).getStatus()).as("HEAD " + path).isEqualTo(404);
    }
  }

  @Test
  @DisplayName("a private repo challenges an anonymous request, and answers one with credentials")
  void privateRepo() throws Exception {
    final var repo = seed(true);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");

    assertThat(getFile(repo, METADATA).getStatus()).isEqualTo(401);
    assertThat(headFile(repo, METADATA).getStatus()).isEqualTo(401);

    final var token = this.adminProtocolBearerToken();
    final var get =
        protocol(get("/{repo}/{path}", repo.getName(), METADATA).header(AUTHORIZATION, token));

    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(get.getContentAsString()).contains("<prefix>hello</prefix>");
  }

  @Test
  @DisplayName("the directory listing of the group does not list the generated file")
  void notListed() throws Exception {
    final var repo = seed(false);
    registerPlugin(repo, "hello-maven-plugin", "Hello", "hello");
    store(repo, GROUP_PATH + "/hello-maven-plugin/1.0/hello-maven-plugin-1.0.jar", "jar");

    final var listing = getFile(repo, GROUP_PATH + "/");

    assertThat(listing.getStatus()).isEqualTo(200);
    assertThat(listing.getContentAsString())
        .contains("hello-maven-plugin/")
        .doesNotContain("maven-metadata.xml");
  }
}
