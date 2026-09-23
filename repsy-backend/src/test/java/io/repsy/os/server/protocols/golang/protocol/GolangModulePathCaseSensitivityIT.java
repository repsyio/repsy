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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1232: {@code AbstractGoProtocolFacade.upload}/{@code download} used to lower-case the decoded
 * module path before using it as the storage/DB key, so two module paths that differ only by case
 * (a scenario Go's own module system explicitly supports -- it uses !-escaping, not case-folding,
 * for exactly this reason) collapsed into the same module. The path is now stored and looked up
 * case-preserved, matching the real {@code go} toolchain.
 *
 * <p>A module that was already published (and hence lower-cased) before this fix keeps its data:
 * nothing is migrated, but a lookup that finds no row under the exact requested case falls back
 * once to the all-lower-case spelling (see {@code GoModuleServiceImpl.findModule} and {@code
 * AbstractGoProtocolFacade.getResourceWithLegacyFallback}/{@code lowerCaseModuleSegment}).
 *
 * <p>None of these reaches the database with a leaked row across test methods: each uses its own
 * run-unique module path, and all mutations happen inside this class's default rolled-back
 * transaction (the storage writes are cleaned up along with the repo by the normal repo lifecycle).
 */
@DisplayName("Go module paths are case-sensitive for storage and lookup (RPS-1232)")
class GolangModulePathCaseSensitivityIT extends AbstractIntegrationTest {

  private static final String DOMAIN = "example.com";

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
      zip.putNextEntry(new ZipEntry(prefix + "casing.go"));
      zip.write("package casing\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  /**
   * {@code urlModulePath} is used verbatim in the URL, raw upper-case letters and all: the server
   * only un-escapes {@code !} sequences, so a raw upper-case letter with no {@code !} prefix passes
   * through {@code decodeModulePath} unchanged (confirmed by {@code GoVersionUtilsTest}), exactly
   * as a hand-rolled HTTP client (rather than the real {@code go} binary) would send it.
   */
  private void upload(final Repo repo, final String urlModulePath, final String version)
      throws Exception {
    this.mockMvc
        .perform(
            put("/{repo}/" + urlModulePath + "/@v/" + version, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType("application/zip")
                .content(moduleZip(urlModulePath, version))
                .with(protocolPort()))
        .andExpect(status().isOk());
  }

  private String versionList(final Repo repo, final String urlModulePath) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/" + urlModulePath + "/@v/list", repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private String latestVersion(final Repo repo, final String urlModulePath) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/{repo}/" + urlModulePath + "/@latest", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    return JsonPath.read(response, "$.Version");
  }

  private List<String> storedModulePaths(final Repo repo) {
    this.entityManager.flush();
    return this.jdbcTemplate.queryForList(
        "select module_path from go_module where repo_id = ? order by module_path",
        String.class,
        repo.getId());
  }

  @Test
  @DisplayName(
      "a mixed-case module path and its all-lower-case spelling coexist as distinct modules")
  void distinctModulesCoexistByCase() throws Exception {
    final var repo = this.goRepo();
    final var lowerPath = DOMAIN + "/" + uniqueRepoName("casing");
    final var mixedPath = lowerPath.replaceFirst("casing", "Casing");

    this.upload(repo, lowerPath, "v0.1.0");
    this.upload(repo, lowerPath, "v0.2.0");
    this.upload(repo, mixedPath, "v0.9.0");

    assertThat(this.storedModulePaths(repo)).containsExactlyInAnyOrder(lowerPath, mixedPath);

    assertThat(this.versionList(repo, lowerPath).lines().toList())
        .as("the lower-case module keeps its own version history")
        .containsExactly("v0.1.0", "v0.2.0");
    assertThat(this.versionList(repo, mixedPath).lines().toList())
        .as("the mixed-case module is a DISTINCT module with its own version history")
        .containsExactly("v0.9.0");

    assertThat(this.latestVersion(repo, lowerPath)).isEqualTo("v0.2.0");
    assertThat(this.latestVersion(repo, mixedPath)).isEqualTo("v0.9.0");
  }

  @Test
  @DisplayName(
      "a legacy row published (and hence lower-cased) before this fix is still reachable by its"
          + " original lower-case path, and by a lookup for its real, mixed case (fallback)")
  void legacyLowerCasedRowIsStillReachable() throws Exception {
    final var repo = this.goRepo();
    // A module whose real name has upper-case letters, but which was uploaded through the
    // now-fixed endpoint using an all-lower-case URL, standing in for a module that, before
    // RPS-1232, would have been stored lower-cased regardless of the URL's real case.
    final var legacyPath = DOMAIN + "/" + uniqueRepoName("legacy");

    this.upload(repo, legacyPath, "v1.0.0");

    assertThat(this.storedModulePaths(repo)).containsExactly(legacyPath);

    // Reachable by the exact path it was stored under (trivial, always true).
    assertThat(this.versionList(repo, legacyPath).lines().toList()).containsExactly("v1.0.0");
    assertThat(this.latestVersion(repo, legacyPath)).isEqualTo("v1.0.0");

    // Reachable by its real, mixed case too: no row/storage exists under that exact case, so both
    // GoModuleServiceImpl.findModule and AbstractGoProtocolFacade.getResourceWithLegacyFallback
    // fall back once to the all-lower-case spelling.
    final var mixedCaseRequestPath = legacyPath.replaceFirst("legacy", "Legacy");
    assertThat(this.versionList(repo, mixedCaseRequestPath).lines().toList())
        .as("the lower-case fallback still finds the legacy row's versions")
        .containsExactly("v1.0.0");
    assertThat(this.latestVersion(repo, mixedCaseRequestPath)).isEqualTo("v1.0.0");
  }
}
