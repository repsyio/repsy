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

package io.repsy.os.server.protocols.pypi.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.server.security.shared.resolvers.PypiArtifactStorageResolver;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.util.UriUtils;

/**
 * RPS-1662: a PEP 440 local version ({@code 1.0+cu118}, {@code 1.0+local.1}) is accepted on upload
 * and KEPT, so {@code 1.0}, {@code 1.0+cu118} and {@code 1.0+cu121} are three releases. The
 * important property is that a file belongs to exactly one release: the old grammar read {@code
 * pkg-1.0+cu118-...whl} as release {@code 1.0}, so deleting {@code 1.0} deleted the local builds
 * too.
 *
 * <p>Packages are seeded through {@link PypiProtocolFacadeImpl#uploadPackage} directly, the same
 * pattern {@code PypiWireReadIT} uses, and every read goes through the real protocol router.
 */
@DisplayName("PyPI local versions are separate releases (RPS-1662)")
class PypiLocalVersionIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @Autowired private PypiArtifactStorageResolver pypiArtifactStorageResolver;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  /**
   * A request for a URI that must reach the router exactly as written, like a client's. Tomcat
   * decodes the path into the servlet path (a {@code %2B} becomes a {@code +}, a literal {@code +}
   * stays one) before the path parser sees it, and MockMvc does not, so this does it.
   */
  private ResultActions rawProtocol(
      final AbstractMockHttpServletRequestBuilder<?> request, final String rawPath)
      throws Exception {
    return this.mockMvc.perform(
        request
            .with(protocolPort())
            .with(
                r -> {
                  r.setServletPath(UriUtils.decode(rawPath, StandardCharsets.UTF_8));
                  return r;
                }));
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private RepoInfo createRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(
            uniqueRepoName("pypi-lv"), RepoType.PYPI, false, "PyPI local version IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());
    repoInfo.setAllowOverride(false);
    return repoInfo;
  }

  private static ProtocolContext context(final RepoInfo repo) {
    final var ctx = new ProtocolContext();
    ctx.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    return ctx;
  }

  private void publish(
      final RepoInfo repo,
      final String packageName,
      final String version,
      final String filename,
      final byte[] content,
      final String requiresPython)
      throws Exception {

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", version);
    parameters.put("requires_python", requiresPython);
    parameters.put("sha256_digest", sha256Hex(content));

    this.pypiProtocolFacade.uploadPackage(
        context(repo),
        parameters,
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
  }

  private static byte[] bytes(final String marker) {
    return marker.getBytes(StandardCharsets.UTF_8);
  }

  private Path archive(final RepoInfo repo, final String packageName, final String filename) {
    final Repo entity = this.repoRepository.findByName(repo.getName()).orElseThrow();

    return storageDirOf(entity).resolve(packageName).resolve(filename);
  }

  private static Path digestOf(final Path archive) {
    return archive.resolveSibling(archive.getFileName() + ".sha256");
  }

  private boolean hasRelease(final RepoInfo repo, final String packageName, final String version) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from pypi_release r
              join pypi_package p on p.id = r.package_id
            where p.repo_id = ? and p.normalized_name = ? and r.version = ?
            """,
            Integer.class,
            repo.getId(),
            packageName,
            version);

    return count != null && count > 0;
  }

  /**
   * A package with the public release 1.0 and the local builds 1.0+cu118 (wheel) and 1.0+local.1.
   */
  private void seedPublicAndLocalBuilds(final RepoInfo repo, final String name) throws Exception {
    final var wheelName = name.replace('-', '_');

    this.publish(repo, name, "1.0", wheelName + "-1.0-py3-none-any.whl", bytes("public"), ">=3.8");
    this.publish(
        repo,
        name,
        "1.0+cu118",
        wheelName + "-1.0+cu118-py3-none-any.whl",
        bytes("cu118"),
        ">=3.9");
    this.publish(
        repo, name, "1.0+local.1", name + "-1.0+local.1.tar.gz", bytes("local1"), ">=3.10");
  }

  @Test
  @DisplayName("a local-version wheel and sdist are stored under their own release, segment kept")
  void uploadKeepsTheLocalSegment() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();

    this.seedPublicAndLocalBuilds(repo, name);

    assertThat(this.hasRelease(repo, name, "1.0")).isTrue();
    assertThat(this.hasRelease(repo, name, "1.0+cu118")).isTrue();
    assertThat(this.hasRelease(repo, name, "1.0+local.1")).isTrue();
    assertThat(this.archive(repo, name, name.replace('-', '_') + "-1.0+cu118-py3-none-any.whl"))
        .hasBinaryContent(bytes("cu118"));
    assertThat(this.archive(repo, name, name + "-1.0+local.1.tar.gz"))
        .hasBinaryContent(bytes("local1"));
  }

  @Test
  @DisplayName("a form version that is not the filename's version is refused and stores nothing")
  void versionOfTheFormMustEqualTheVersionOfTheFilename() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheel = name.replace('-', '_') + "-1.0+cu118-py3-none-any.whl";

    assertThatThrownBy(() -> this.publish(repo, name, "1.0", wheel, bytes("x"), ">=3.9"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("archiveVersionMismatch");

    assertThat(this.hasRelease(repo, name, "1.0")).isFalse();
    assertThat(this.hasRelease(repo, name, "1.0+cu118")).isFalse();
    assertThat(this.archive(repo, name, wheel)).doesNotExist();
  }

  @Test
  @DisplayName("deleting release 1.0 leaves the files and rows of 1.0+cu118 and 1.0+local.1")
  void deletingThePublicReleaseKeepsTheLocalBuilds() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheelName = name.replace('-', '_');
    this.seedPublicAndLocalBuilds(repo, name);

    this.pypiApiFacade.deleteRelease(repo, name, "1.0");

    assertThat(this.hasRelease(repo, name, "1.0")).isFalse();
    assertThat(this.archive(repo, name, wheelName + "-1.0-py3-none-any.whl")).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheelName + "-1.0-py3-none-any.whl")))
        .doesNotExist();

    assertThat(this.hasRelease(repo, name, "1.0+cu118")).isTrue();
    assertThat(this.archive(repo, name, wheelName + "-1.0+cu118-py3-none-any.whl")).exists();
    assertThat(digestOf(this.archive(repo, name, wheelName + "-1.0+cu118-py3-none-any.whl")))
        .exists();
    assertThat(this.hasRelease(repo, name, "1.0+local.1")).isTrue();
    assertThat(this.archive(repo, name, name + "-1.0+local.1.tar.gz")).exists();
    assertThat(digestOf(this.archive(repo, name, name + "-1.0+local.1.tar.gz"))).exists();
  }

  @Test
  @DisplayName("deleting release 1.0+cu118 leaves release 1.0 and the other local build")
  void deletingALocalBuildKeepsThePublicReleaseAndTheOtherBuilds() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheelName = name.replace('-', '_');
    this.seedPublicAndLocalBuilds(repo, name);

    this.pypiApiFacade.deleteRelease(repo, name, "1.0+cu118");

    assertThat(this.hasRelease(repo, name, "1.0+cu118")).isFalse();
    assertThat(this.archive(repo, name, wheelName + "-1.0+cu118-py3-none-any.whl")).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheelName + "-1.0+cu118-py3-none-any.whl")))
        .doesNotExist();

    assertThat(this.hasRelease(repo, name, "1.0")).isTrue();
    assertThat(this.archive(repo, name, wheelName + "-1.0-py3-none-any.whl")).exists();
    assertThat(this.hasRelease(repo, name, "1.0+local.1")).isTrue();
    assertThat(this.archive(repo, name, name + "-1.0+local.1.tar.gz")).exists();
  }

  @Test
  @DisplayName("the vulnerability scanner resolves the file of exactly the release it asks for")
  void scannerResolvesTheExactRelease() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheelName = name.replace('-', '_');
    this.seedPublicAndLocalBuilds(repo, name);

    assertThat(
            this.pypiArtifactStorageResolver.resolve(
                repo.getStorageKey(), repo.getName(), name, "1.0"))
        .contains(name + "/" + wheelName + "-1.0-py3-none-any.whl");
    assertThat(
            this.pypiArtifactStorageResolver.resolve(
                repo.getStorageKey(), repo.getName(), name, "1.0+cu118"))
        .contains(name + "/" + wheelName + "-1.0+cu118-py3-none-any.whl");
    assertThat(
            this.pypiArtifactStorageResolver.resolve(
                repo.getStorageKey(), repo.getName(), name, "1.0+cu121"))
        .isEmpty();
  }

  @Test
  @DisplayName("a literal + and a %2B in the download URL serve the same file, GET and HEAD")
  void plusAndPercentEncodedPlusDownloadTheSameFile() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheel = name.replace('-', '_') + "-1.0+cu118-py3-none-any.whl";
    final var content = bytes("cu118 wheel bytes");
    this.publish(repo, name, "1.0+cu118", wheel, content, ">=3.9");

    final var literal = "/" + repo.getName() + "/" + name + "/-/" + wheel;
    final var encoded = literal.replace("+", "%2B");

    final var viaLiteral =
        this.rawProtocol(get(URI.create(literal)), literal)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    final var viaEncoded =
        this.rawProtocol(get(URI.create(encoded)), encoded)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(viaLiteral.getContentAsByteArray()).isEqualTo(content);
    assertThat(viaEncoded.getContentAsByteArray()).isEqualTo(content);
    this.rawProtocol(head(URI.create(literal)), literal).andExpect(status().isOk());
    this.rawProtocol(head(URI.create(encoded)), encoded).andExpect(status().isOk());
  }

  @Test
  @DisplayName("the simple index lists each build with its own requires-python and a working href")
  void simpleIndexListsEveryBuildWithItsOwnRequiresPython() throws Exception {
    final var repo = this.createRepo();
    final var name = "lv-" + randomTag();
    final var wheelName = name.replace('-', '_');
    this.seedPublicAndLocalBuilds(repo, name);

    final var page =
        this.protocol(get("/{repo}/simple/{name}/", repo.getName(), name))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(page)
        .contains(wheelName + "-1.0-py3-none-any.whl#sha256=")
        .contains("data-requires-python=\"&gt;=3.8\">" + wheelName + "-1.0-py3-none-any.whl<")
        .contains("data-requires-python=\"&gt;=3.9\">" + wheelName + "-1.0+cu118-py3-none-any.whl<")
        .contains("data-requires-python=\"&gt;=3.10\">" + name + "-1.0+local.1.tar.gz<");

    // Every href must resolve to its file, however the client spells the +.
    final var hrefStart = page.indexOf(wheelName + "-1.0+cu118-py3-none-any.whl#");
    final var hrefBegin = page.lastIndexOf("href=\"", hrefStart) + "href=\"".length();
    final var href = page.substring(hrefBegin, page.indexOf('"', hrefBegin));
    final var path = URI.create(href).getRawPath();

    this.rawProtocol(get(URI.create(path)), path).andExpect(status().isOk());
  }
}
