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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1228: {@code GoModFileValidator} used to validate a {@code go.mod}'s content in isolation,
 * with no way to compare its {@code module} directive against the URL's own module path. A zip
 * whose go.mod named a completely different module still uploaded successfully, and, since Go
 * module versions are immutable, could never be corrected in place. The validator now takes the
 * URL's decoded module path and refuses a mismatch.
 *
 * <p>None of these reaches the database, so they run in this class's default rolled-back
 * transaction.
 */
@DisplayName(
    "Go module upload compares the go.mod module directive against the URL path (RPS-1228)")
class GolangGoModPathMismatchIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/modpath";
  private static final String VERSION = "v1.0.0";

  private Repo goRepo() {
    return this.seedRepo(RepoType.GOLANG, uniqueRepoName("golang"));
  }

  /**
   * A module zip whose zip-entry prefix is {@code decodedPath} but whose go.mod directive is {@code
   * goModModule}.
   */
  private static byte[] moduleZip(
      final String decodedPath, final String version, final String goModModule) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = decodedPath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + goModModule + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "modpath.go"));
      zip.write("package modpath\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private MockHttpServletResponse upload(
      final Repo repo, final String urlModulePath, final String version, final byte[] zip)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/" + urlModulePath + "/@v/" + version, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType("application/zip")
                .content(zip)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private int moduleRows(final Repo repo) {
    this.entityManager.flush();
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from go_module where repo_id = ?", Integer.class, repo.getId());

    return count == null ? 0 : count;
  }

  private List<String> storedFiles(final Repo repo) throws IOException {
    final var dir = storageDirOf(repo);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (final var files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .map(file -> dir.relativize(file).toString())
          .toList();
    }
  }

  @Test
  @DisplayName(
      "refuses a go.mod naming a different module than the URL path, storing nothing (RPS-1228)")
  void refusesMismatchedModuleDirective() throws Exception {
    final var repo = this.goRepo();

    final var response =
        this.upload(
            repo, MODULE, VERSION, moduleZip(MODULE, VERSION, "example.com/completely-different"));

    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(response.getStatus()).as(body).isEqualTo(400);
    assertThat(body)
        .contains("\"msgId\":\"goModModulePathMismatch\"")
        .contains("The go.mod module directive does not match the module path in the URL.");
    assertThat(this.moduleRows(repo)).as("no module row").isZero();
    assertThat(this.storedFiles(repo)).as("no file").isEmpty();
  }

  @Test
  @DisplayName("accepts a go.mod whose module directive matches the URL path (happy path guard)")
  void acceptsMatchingModuleDirective() throws Exception {
    final var repo = this.goRepo();

    final var response = this.upload(repo, MODULE, VERSION, moduleZip(MODULE, VERSION, MODULE));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.moduleRows(repo)).as("one module row").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "compares against the decoded, case-preserved module path -- after !-escapes are resolved,"
          + " not the lower-cased stored one")
  void comparesAgainstTheDecodedModulePath() throws Exception {
    final var repo = this.goRepo();
    final var encodedUrlPath = "example.com/!upper";
    final var decodedPath = "example.com/Upper";

    final var response =
        this.upload(repo, encodedUrlPath, VERSION, moduleZip(decodedPath, VERSION, decodedPath));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.moduleRows(repo)).as("one module row").isEqualTo(1);
  }
}
