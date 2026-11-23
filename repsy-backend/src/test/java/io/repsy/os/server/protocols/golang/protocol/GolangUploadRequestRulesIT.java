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
package io.repsy.os.server.protocols.golang.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.shared.token.DeployTokenFixtures;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-2739: the request rules of a Go module upload ({@code PUT /{repo}/{module}/@v/{version}})
 * that only the e2e suite pinned over HTTP: the optional {@code Content-Sha256} header, the
 * accepted URL suffixes, and the refusal of a read-only deploy token. They run through the protocol
 * router on MockMvc; nothing here needs a real port.
 *
 * <p>None of these needs a committed row, so they run in this class's default rolled-back
 * transaction.
 */
@DisplayName("Go module upload request rules: Content-Sha256, URL suffixes, read-only token")
class GolangUploadRequestRulesIT extends AbstractIT {

  private static final String MODULE = "example.com/uploadrules";
  private static final String CONTENT_SHA256 = "Content-Sha256";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private Repo goRepo() {
    return this.seedRepo(RepoType.GOLANG, uniqueRepoName("golang"));
  }

  private static byte[] moduleZip(final String version) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = MODULE + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + MODULE + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "rules.go"));
      zip.write("package uploadrules\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** PUTs a module zip; a null {@code sha256} sends no {@code Content-Sha256} header. */
  private MockHttpServletResponse upload(
      final Repo repo,
      final String authorization,
      final String version,
      final String suffix,
      final String sha256)
      throws Exception {
    final var request =
        put("/{repo}/" + MODULE + "/@v/" + version + suffix, repo.getName())
            .header(AUTHORIZATION, authorization)
            .contentType("application/zip")
            .content(moduleZip(version))
            .with(protocolPort());

    if (sha256 != null) {
      request.header(CONTENT_SHA256, sha256);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse versionList(final Repo repo, final String authorization)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/" + MODULE + "/@v/list", repo.getName())
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private List<String> versions(final Repo repo) throws Exception {
    final var response = this.versionList(repo, this.adminProtocolBearerToken());

    // A module with no version has no list at all: 404.
    return response.getStatus() == 404
        ? List.of()
        : response.getContentAsString().lines().sorted().toList();
  }

  private static byte[] storedZip(final Repo repo, final String version) throws IOException {
    final var dir = storageDirOf(repo);
    try (final var files = Files.walk(dir)) {
      final var zip =
          files.filter(file -> file.getFileName().toString().equals(version + ".zip")).findFirst();

      return zip.isPresent() ? Files.readAllBytes(zip.get()) : new byte[0];
    }
  }

  private void expectOk(final MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
  }

  @Test
  @DisplayName("a wrong Content-Sha256 is a 400 sha256Mismatch and stores nothing")
  void wrongContentSha256IsRefused() throws Exception {
    final var repo = this.goRepo();
    final var admin = this.adminProtocolBearerToken();

    final var response = this.upload(repo, admin, "v0.0.1", "", "deadbeef");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("\"msgId\":\"sha256Mismatch\"");
    assertThat(this.versions(repo)).isEmpty();
  }

  @Test
  @DisplayName("an upper-case Content-Sha256 is lower-cased before it is compared")
  void upperCaseContentSha256IsAccepted() throws Exception {
    final var repo = this.goRepo();
    final var digest = sha256Hex(moduleZip("v0.0.1")).toUpperCase(java.util.Locale.ROOT);

    this.expectOk(this.upload(repo, this.adminProtocolBearerToken(), "v0.0.1", "", digest));

    assertThat(this.versions(repo)).containsExactly("v0.0.1");
  }

  @Test
  @DisplayName("a missing Content-Sha256 is skipped, not demanded")
  void absentContentSha256IsSkipped() throws Exception {
    final var repo = this.goRepo();

    this.expectOk(this.upload(repo, this.adminProtocolBearerToken(), "v0.0.1", "", null));

    assertThat(this.versions(repo)).containsExactly("v0.0.1");
  }

  @Test
  @DisplayName("the upload URL takes no suffix, .zip, and even .mod, always as a zip body")
  void uploadUrlSuffixes() throws Exception {
    final var repo = this.goRepo();
    final var admin = this.adminProtocolBearerToken();

    this.expectOk(this.upload(repo, admin, "v0.0.1", "", null));
    this.expectOk(this.upload(repo, admin, "v0.0.2", ".zip", null));
    // The suffix only strips the version out of the last path segment; nothing else reads it, so a
    // .mod URL still stores the zip body it was sent.
    this.expectOk(this.upload(repo, admin, "v0.0.3", ".mod", null));

    assertThat(this.versions(repo)).containsExactly("v0.0.1", "v0.0.2", "v0.0.3");
    assertThat(storedZip(repo, "v0.0.3")).isEqualTo(moduleZip("v0.0.3"));
  }

  @Test
  @DisplayName(
      "a read-only deploy token upload is a flat text/plain 401, and the token still reads @v/list")
  void readOnlyTokenCannotUpload() throws Exception {
    final var repo = this.goRepo();
    final var token =
        DeployTokenFixtures.seed(
            repo, true, this.repoRepository, this.deployTokenRepository, this.entityManager);
    final var credential = basicAuth(token.username(), token.secret());

    final var refused = this.upload(repo, credential, "v0.0.1", "", null);

    assertThat(refused.getStatus()).isEqualTo(401);
    // The go command prints a 401 body only when it is text/plain (RPS-1435).
    assertThat(refused.getHeader(CONTENT_TYPE)).startsWith("text/plain");
    assertThat(refused.getContentAsString()).contains("credentials");
    assertThat(this.versions(repo)).isEmpty();

    this.expectOk(this.upload(repo, this.adminProtocolBearerToken(), "v0.0.1", "", null));

    final var read = this.versionList(repo, credential);
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(read.getContentAsString()).contains("v0.0.1");
  }
}
