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
 * RPS-1227: {@code AbstractGoProtocolFacade.upload} used to take the version straight from the URL
 * with only a length bound, so any string ("banana", "1.0.0", ...) was accepted, stored immutably
 * and could poison {@code COMPARATOR}'s ordering of {@code @v/list}/{@code @latest}. A strict
 * Go-semver gate now runs after the existing length check, so a refused upload still names the
 * over-long-value case with {@code moduleVersionTooLong} ahead of the new {@code
 * invalidModuleVersion}.
 *
 * <p>None of these reaches the database, so they run in this class's default rolled-back
 * transaction.
 */
@DisplayName(
    "Go module upload validates the version string against Go's own semver grammar (RPS-1227)")
class GolangVersionValidationIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/semver";

  private Repo goRepo() {
    return this.seedRepo(RepoType.GOLANG, uniqueRepoName("golang"));
  }

  private static byte[] moduleZip(final String modulePath, final String version) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = modulePath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + modulePath + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "semver.go"));
      zip.write("package semver\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private MockHttpServletResponse upload(
      final Repo repo, final String modulePath, final String version) throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/" + modulePath + "/@v/" + version, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType("application/zip")
                .content(moduleZip(modulePath, version))
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

  private void expectRefused(
      final MockHttpServletResponse response, final String msgId, final String text)
      throws Exception {
    final var body = response.getContentAsString(StandardCharsets.UTF_8);

    assertThat(response.getStatus()).as(body).isEqualTo(400);
    assertThat(body).contains("\"msgId\":\"%s\"".formatted(msgId)).contains(text);
  }

  @Test
  @DisplayName("refuses a non-semver version with 400 invalidModuleVersion, storing nothing")
  void refusesNonSemverVersion() throws Exception {
    final var repo = this.goRepo();

    final var response = this.upload(repo, MODULE, "banana");

    this.expectRefused(
        response, "invalidModuleVersion", "The module version is not a valid Go semver string.");
    assertThat(this.moduleRows(repo)).as("no module row").isZero();
    assertThat(this.storedFiles(repo)).as("no file").isEmpty();
  }

  @Test
  @DisplayName("refuses a version missing the leading 'v' with 400 invalidModuleVersion")
  void refusesVersionWithoutLeadingV() throws Exception {
    final var repo = this.goRepo();

    final var response = this.upload(repo, MODULE, "1.0.0");

    this.expectRefused(
        response, "invalidModuleVersion", "The module version is not a valid Go semver string.");
    assertThat(this.moduleRows(repo)).as("no module row").isZero();
  }

  @Test
  @DisplayName(
      "an over-long but syntactically valid version still yields moduleVersionTooLong, not"
          + " invalidModuleVersion (pins the check order)")
  void overLongValidSemverStillPinsLengthCheck() throws Exception {
    final var repo = this.goRepo();
    // A valid pre-release identifier, but 102 characters long overall.
    final var version = "v1.0.0-" + "a".repeat(95);

    final var response = this.upload(repo, MODULE, version);

    this.expectRefused(
        response, "moduleVersionTooLong", "The module version is longer than 100 characters.");
  }

  @Test
  @DisplayName(
      "accepts a +incompatible build-metadata suffix and a pseudo-version (regression guard"
          + " against over-tightening the semver gate)")
  void acceptsIncompatibleSuffixAndPseudoVersion() throws Exception {
    final var repo = this.goRepo();

    final var incompatibleResponse = this.upload(repo, MODULE, "v1.2.3+incompatible");
    assertThat(incompatibleResponse.getStatus())
        .as(incompatibleResponse.getContentAsString())
        .isEqualTo(200);

    final var pseudoVersion = "v1.0.0-20240101120000-0123456789ab";
    final var pseudoResponse = this.upload(repo, MODULE, pseudoVersion);
    assertThat(pseudoResponse.getStatus()).as(pseudoResponse.getContentAsString()).isEqualTo(200);
  }
}
