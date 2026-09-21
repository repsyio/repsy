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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1072: the module path and version of a Go module upload come from the URL and the {@code go}
 * directive from its {@code go.mod}, and each is stored in a length-limited {@code go_module} or
 * {@code go_module_version} column. A value that did not fit failed the row insert with a generic
 * 400 that named no field. The module path and version are now refused before the zip is read, with
 * a 400 that names the field, and a {@code go} directive that does not fit is dropped: the upload
 * goes through, and the {@code go.mod}, which is what the toolchain reads, is stored as sent.
 *
 * <p>None of these reaches the database with an over-long value, so they run in this class's test
 * transaction.
 */
@DisplayName("Go module upload holds metadata to its columns (RPS-1072)")
class GolangUploadMetadataLengthIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/lengths";
  private static final String VERSION = "v1.0.0";

  /**
   * A module path of exactly {@code length} characters: {@code example.com} and segments of 50
   * letters. The segments are short because the filesystem storage names a directory after each
   * one, and a name over 255 bytes is refused by the filesystem.
   */
  private static String modulePathOfLength(final int length) {
    final var path = new StringBuilder("example.com");

    for (var i = 0; path.length() < length; i++) {
      path.append(i % 51 == 0 ? '/' : 'a');
    }

    return path.toString();
  }

  /** A version of exactly {@code length} characters, such as {@code v1.0.0-aaa}. */
  private static String versionOfLength(final int length) {
    final var prefix = VERSION + "-";

    return prefix + "a".repeat(length - prefix.length());
  }

  /** A module zip for the decoded module path and version, with the given go.mod. */
  private static byte[] moduleZip(
      final String decodedPath, final String version, final String goMod) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = decodedPath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(goMod.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "lengths.go"));
      zip.write("package lengths\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static String goMod(final String modulePath, final String goDirective) {
    return "module " + modulePath + "\n\ngo " + goDirective + "\n";
  }

  private Repo goRepo() {
    return this.seedRepo(RepoType.GOLANG, uniqueRepoName("golang"));
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

  private Map<String, Object> storedVersion(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForMap(
        """
        select m.module_path, v.version, v.go_version
          from go_module_version v join go_module m on m.id = v.module_id
         where m.repo_id = ?
        """,
        repo.getId());
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
  @DisplayName("stores a module path, version and go directive of exactly their column lengths")
  void storesValuesAtTheLimit() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = modulePathOfLength(GoVersionUtils.MAX_MODULE_PATH_LENGTH);
    final var version = versionOfLength(GoVersionUtils.MAX_VERSION_LENGTH);
    final var goVersion = "1".repeat(GoVersionUtils.MAX_GO_VERSION_LENGTH);

    final var response =
        this.upload(
            repo,
            modulePath,
            version,
            moduleZip(modulePath, version, goMod(modulePath, goVersion)));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(modulePath).hasSize(GoVersionUtils.MAX_MODULE_PATH_LENGTH);
    assertThat(this.storedVersion(repo))
        .containsEntry("module_path", modulePath)
        .containsEntry("version", version)
        .containsEntry("go_version", goVersion);
  }

  @Test
  @DisplayName("measures the module path as it is stored, decoded and lower-cased")
  void measuresTheNormalizedModulePath() throws Exception {
    final var repo = this.goRepo();
    // Six "!a" escapes stand for six upper-case letters, so the URL is 518 characters long and the
    // path stored, once decoded and lower-cased, 512.
    final var head = modulePathOfLength(GoVersionUtils.MAX_MODULE_PATH_LENGTH - 7);
    final var encoded = head + "/" + "!a".repeat(6);
    final var decoded = head + "/" + "A".repeat(6);
    final var stored = decoded.toLowerCase(Locale.ROOT);

    final var response =
        this.upload(repo, encoded, VERSION, moduleZip(decoded, VERSION, goMod(decoded, "1.21")));

    assertThat(decoded).hasSize(GoVersionUtils.MAX_MODULE_PATH_LENGTH);
    assertThat(encoded.length()).isGreaterThan(GoVersionUtils.MAX_MODULE_PATH_LENGTH);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.storedVersion(repo)).containsEntry("module_path", stored);
  }

  @Test
  @DisplayName(
      "refuses a module path over 512 characters with a 400 that names it, storing nothing")
  void refusesOverLongModulePath() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = modulePathOfLength(GoVersionUtils.MAX_MODULE_PATH_LENGTH + 1);

    final var response =
        this.upload(
            repo, modulePath, VERSION, moduleZip(modulePath, VERSION, goMod(modulePath, "1.21")));

    this.expectRefused(
        response, "modulePathTooLong", "The module path is longer than 512 characters.");
    assertThat(this.moduleRows(repo)).as("no module row").isZero();
    assertThat(this.storedFiles(repo)).as("no file").isEmpty();
  }

  @Test
  @DisplayName("refuses a version over 100 characters with a 400 that names it, storing nothing")
  void refusesOverLongVersion() throws Exception {
    final var repo = this.goRepo();
    final var version = versionOfLength(GoVersionUtils.MAX_VERSION_LENGTH + 1);

    final var response =
        this.upload(repo, MODULE, version, moduleZip(MODULE, version, goMod(MODULE, "1.21")));

    this.expectRefused(
        response, "moduleVersionTooLong", "The module version is longer than 100 characters.");
    assertThat(this.moduleRows(repo)).as("no module row").isZero();
    assertThat(this.storedFiles(repo)).as("no file").isEmpty();
  }

  @Test
  @DisplayName(
      "drops a go directive over 20 characters, accepts the upload and stores the go.mod as sent")
  void dropsOverLongGoDirective() throws Exception {
    final var repo = this.goRepo();
    final var goVersion = "1".repeat(GoVersionUtils.MAX_GO_VERSION_LENGTH + 1);
    final var goMod = goMod(MODULE, goVersion);

    final var response = this.upload(repo, MODULE, VERSION, moduleZip(MODULE, VERSION, goMod));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.storedVersion(repo))
        .containsEntry("module_path", MODULE)
        .containsEntry("version", VERSION)
        .containsEntry("go_version", null);
    final var stored =
        this.mockMvc
            .perform(
                get("/{repo}/" + MODULE + "/@v/" + VERSION + ".mod", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(stored.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(goMod);
  }
}
