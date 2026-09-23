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

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1223: the override guard is decided by the FILENAME alone -- a form {@code version} that
 * disagrees with the filename's own version can no longer bypass {@code allowOverride: false}.
 */
@DisplayName("PyPI upload override guard (RPS-1223)")
class PypiUploadOverrideIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private RepoInfo createRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(unique("pypi"), RepoType.PYPI, true, "PyPI IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());
    return repoInfo;
  }

  /**
   * The storage layer never parses archive bytes (it stores them verbatim), so a plain marker
   * payload is enough to tell two uploads of the same path apart -- no real zip needed.
   */
  private static byte[] wheelContent(final String marker) {
    return marker.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private void upload(
      final RepoInfo repo,
      final String packageName,
      final String formVersion,
      final String filename,
      final byte[] content)
      throws Exception {

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", formVersion);
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", sha256Hex(content));

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
  }

  @Test
  @DisplayName(
      "refuses an override whose form version does not match the filename's own version (the exact"
          + " RPS-1223 bypass)")
  void refusesOverrideWhenFormVersionDoesNotMatchTheFilename() throws Exception {
    final var repo = this.createRepo();
    repo.setAllowOverride(false);
    final var filename = "my_package-1.0.0-py3-none-any.whl";

    this.upload(repo, "my-package", "1.0.0", filename, wheelContent("original"));

    assertThatThrownBy(
            () ->
                this.upload(
                    repo, "my-package", "1.0.0.post9", filename, wheelContent("bypass attempt")))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("fileAlreadyExists");
  }

  @Test
  @DisplayName("refuses an exact repeat (same filename, same form version)")
  void refusesOverrideOnAnExactRepeat() throws Exception {
    final var repo = this.createRepo();
    repo.setAllowOverride(false);
    final var filename = "my_package-1.0.0-py3-none-any.whl";

    this.upload(repo, "my-package", "1.0.0", filename, wheelContent("original"));

    assertThatThrownBy(
            () -> this.upload(repo, "my-package", "1.0.0", filename, wheelContent("again")))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("fileAlreadyExists");
  }

  @Test
  @DisplayName("still replaces the stored bytes when the repo allows override")
  void allowsOverrideWhenTheRepoAllowsIt() throws Exception {
    final var repo = this.createRepo();
    repo.setAllowOverride(true);
    final var filename = "my_package-1.0.0-py3-none-any.whl";

    this.upload(repo, "my-package", "1.0.0", filename, wheelContent("original"));
    this.upload(repo, "my-package", "1.0.0", filename, wheelContent("replaced"));

    final var resource =
        this.pypiProtocolFacade.downloadArchiveFile(
            buildDownloadContext(repo), "my-package", filename);
    assertThat(new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8))
        .contains("replaced");
  }

  private static ProtocolContext buildDownloadContext(final RepoInfo repo) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    return context;
  }
}
