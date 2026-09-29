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
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1720 C8: {@code @latest} and {@code @v/list} follow the real GOPROXY protocol's version
 * semantics rather than a plain max over every published version.
 *
 * <p>{@code @v/list} excludes pseudo-versions (go.dev/ref/mod#goproxy-protocol: "This list should
 * not include pseudo-versions"), but keeps tagged pre-releases such as {@code v1.0.0-rc1} -- those
 * are real, addressable versions. {@code @latest} follows the go command's own "latest" version
 * query (go.dev/ref/mod#version-queries): the highest release wins over any pre-release with the
 * same or lower precedence; only when the module has no release at all does the highest tagged
 * pre-release win; only when it has no tagged version at all does a pseudo-version win.
 *
 * <p>Before this fix, {@code GoModuleServiceImpl.computeLatestVersion} was a plain {@code
 * COMPARATOR.max()} over every version (so a pre-release outranked an older release), and {@code
 * AbstractGoProtocolFacade.listVersions} listed every {@code .info} file including pseudo-versions.
 *
 * <p>None of these reaches the database outside a version row, so they run in this class's default
 * rolled-back transaction.
 */
@DisplayName("Go @latest/@v/list follow the real GOPROXY version semantics (RPS-1720 C8)")
class GolangVersionSemanticsIT extends AbstractIntegrationTest {

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
      zip.putNextEntry(new ZipEntry(prefix + "marker.go"));
      zip.write(("package marker // " + version + "\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private void publish(final Repo repo, final String modulePath, final String version)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                put("/{repo}/" + modulePath + "/@v/" + version, repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .contentType("application/zip")
                    .content(moduleZip(modulePath, version))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
  }

  private MockHttpServletResponse wireGet(
      final Repo repo, final String modulePath, final String suffix) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/" + modulePath + "/" + suffix, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private String latestVersionOf(final Repo repo, final String modulePath) throws Exception {
    final var res = this.wireGet(repo, modulePath, "@latest");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    // {"Version":"vX.Y.Z", ...}
    final var body = res.getContentAsString(StandardCharsets.UTF_8);
    final var marker = "\"Version\":\"";
    final var start = body.indexOf(marker) + marker.length();
    return body.substring(start, body.indexOf('"', start));
  }

  private String[] listedVersions(final Repo repo, final String modulePath) throws Exception {
    final var res = this.wireGet(repo, modulePath, "@v/list");
    assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    final var body = res.getContentAsString(StandardCharsets.UTF_8).strip();
    return body.isEmpty() ? new String[0] : body.split("\n");
  }

  @Test
  @DisplayName("@latest picks the highest RELEASE, not a newer tagged pre-release (R9b)")
  void latestPrefersAReleaseOverANewerPreRelease() throws Exception {
    final var repo = this.goRepo();
    final var module = "example.com/latest-release-over-prerelease";
    this.publish(repo, module, "v1.2.0");
    this.publish(repo, module, "v1.3.0-beta.1");

    assertThat(this.latestVersionOf(repo, module))
        .as("v1.2.0 is a real release; v1.3.0-beta.1 is only a pre-release of an unreleased 1.3.0")
        .isEqualTo("v1.2.0");
  }

  @Test
  @DisplayName(
      "@latest falls back to the highest tagged pre-release when the module has no release at all")
  void latestFallsBackToAPreReleaseWithNoReleasePublished() throws Exception {
    final var repo = this.goRepo();
    final var module = "example.com/latest-prerelease-only";
    this.publish(repo, module, "v1.0.0-alpha.1");
    this.publish(repo, module, "v1.0.0-beta.1");

    assertThat(this.latestVersionOf(repo, module)).isEqualTo("v1.0.0-beta.1");
  }

  @Test
  @DisplayName(
      "@latest falls back to a pseudo-version, rather than 404ing, when it is the only version"
          + " published")
  void latestFallsBackToAPseudoVersionWithNothingElsePublished() throws Exception {
    final var repo = this.goRepo();
    final var module = "example.com/latest-pseudo-only";
    this.publish(repo, module, "v1.0.0-20240101120000-0123456789ab");

    assertThat(this.latestVersionOf(repo, module)).isEqualTo("v1.0.0-20240101120000-0123456789ab");
  }

  @Test
  @DisplayName("@v/list excludes pseudo-versions but keeps a tagged pre-release, sorted (R8b)")
  void listExcludesPseudoVersionsButKeepsTaggedPreReleases() throws Exception {
    final var repo = this.goRepo();
    final var module = "example.com/list-excludes-pseudo";
    this.publish(repo, module, "v1.0.0");
    this.publish(repo, module, "v1.1.0-rc1");
    this.publish(repo, module, "v1.2.0-20240101120000-0123456789ab");

    assertThat(this.listedVersions(repo, module))
        .as("the pseudo-version is not listed, the tagged pre-release is")
        .containsExactly("v1.0.0", "v1.1.0-rc1");
  }
}
