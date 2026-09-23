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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

/**
 * Full-stack coverage of the PyPI wire protocol's read routes served by the protocol router on the
 * main port: {@code GET/HEAD /{repo}/simple/} (RPS-1221) and {@code HEAD} mirroring {@code GET}'s
 * status on the project page and the archive download (RPS-1226).
 *
 * <p>Packages are seeded through {@link PypiProtocolFacadeImpl#uploadPackage} directly (the same
 * pattern {@code PypiUploadDigestIT}/{@code PypiUploadValidationIT} use), not a wire multipart
 * {@code POST}: this class's own subject is the read side, and every read assertion below still
 * goes through the real protocol router via {@link #protocol}.
 */
@DisplayName("PyPI wire protocol read routes (RPS-1221/RPS-1226)")
class PypiWireReadIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** A fresh, public (so wire reads need no auth) PyPI repo, storage directory included. */
  private RepoInfo createRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(
            uniqueRepoName("pypi"), RepoType.PYPI, false, "PyPI wire-read IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());
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
      final byte[] content)
      throws Exception {

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", version);
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", sha256Hex(content));

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file);
  }

  @Test
  @DisplayName("HEAD /{repo}/simple/ answers 200 unconditionally: the repo already resolved")
  void headSimpleRootAlwaysAnswers200() throws Exception {
    final var repo = this.createRepo();

    this.protocol(head("/{repo}/simple/", repo.getName())).andExpect(status().isOk());
  }

  @Test
  @DisplayName("HEAD mirrors GET's status on the project page and the archive download (RPS-1226)")
  void headMirrorsGetStatus() throws Exception {
    final var repo = this.createRepo();
    final var filename = "wireread_pkg-1.0.0-py3-none-any.whl";
    final var content = "wheel bytes".getBytes(StandardCharsets.UTF_8);

    this.publish(repo, "wireread-pkg", "1.0.0", filename, content);

    this.protocol(head("/{repo}/simple/wireread-pkg/", repo.getName())).andExpect(status().isOk());

    this.protocol(head("/{repo}/simple/no-such-package/", repo.getName()))
        .andExpect(status().isNotFound());

    this.protocol(head("/{repo}/wireread-pkg/-/{file}", repo.getName(), filename))
        .andExpect(status().isOk());

    this.protocol(head("/{repo}/wireread-pkg/-/no-such-file.whl", repo.getName()))
        .andExpect(status().isNotFound());

    this.protocol(head("/{repo}/this/path/never/existed", repo.getName()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "HEAD on a non-normalized project name redirects (307) instead of always answering 200")
  void headOnNonNormalizedNameRedirects() throws Exception {
    final var repo = this.createRepo();

    // The redirect fires purely from the raw path's shape, before any existence check --
    // demonstrated here on a package that was never published.
    this.protocol(head("/{repo}/simple/WireRead_Pkg/", repo.getName()))
        .andExpect(status().isTemporaryRedirect());
  }

  @Test
  @DisplayName(
      "GET /{repo}/simple/ renders working hrefs off the real repo URI, as text/html (RPS-1221)")
  void rootIndexRendersRealRepoUriAsHtml() throws Exception {
    final var repo = this.createRepo();
    final var filename = "wireread_pkg-1.0.0-py3-none-any.whl";
    final var content = "wheel bytes".getBytes(StandardCharsets.UTF_8);

    this.publish(repo, "wireread-pkg", "1.0.0", filename, content);

    final var response =
        this.protocol(get("/{repo}/simple/", repo.getName()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(response.getContentType()).startsWith(MediaType.TEXT_HTML_VALUE);

    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).doesNotContain("/pypi/" + repo.getName() + "/simple/");
    assertThat(body).contains("/" + repo.getName() + "/simple/wireread-pkg/");

    // The rendered href must itself resolve, not just look plausible.
    final var hrefStart = body.indexOf("href=\"") + "href=\"".length();
    final var href = body.substring(hrefStart, body.indexOf('"', hrefStart));
    final var path = URI.create(href).getPath();

    this.protocol(get(path)).andExpect(status().isOk());
  }
}
