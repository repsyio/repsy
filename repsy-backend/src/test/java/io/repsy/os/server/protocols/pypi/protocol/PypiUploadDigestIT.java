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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
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
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1225: the {@code .sha256} sidecar is computed server-side from the uploaded bytes -- never
 * the client-sent value verbatim -- and a mismatching client digest is rejected outright.
 */
@DisplayName("PyPI upload sha256 digest is server-computed and verified (RPS-1225)")
class PypiUploadDigestIT extends AbstractIntegrationTest {

  private static final Pattern SHA256_FRAGMENT = Pattern.compile("#sha256=([0-9a-f]{64})");

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

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

  private void upload(
      final RepoInfo repo,
      final String packageName,
      final String version,
      final String filename,
      final byte[] content,
      final String sha256Digest)
      throws Exception {

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", version);
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", sha256Digest);

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
    this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file);
  }

  @Test
  @DisplayName(
      "the served sha256 matches the actual uploaded bytes, even for an uppercase client digest")
  void servedSha256MatchesTheActualUploadedBytes() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-1.0.0-py3-none-any.whl";
    final var content = "real wheel bytes".getBytes(StandardCharsets.UTF_8);
    final var realDigest = sha256Hex(content);

    // Client sends the correct digest, but UPPERCASE -- must still be accepted and normalized.
    this.upload(
        repo,
        "my-package",
        "1.0.0",
        filename,
        content,
        realDigest.toUpperCase(java.util.Locale.ROOT));

    final var page = this.pypiProtocolFacade.fetchFromLocalStorage(repo, "my-package");
    final var html = new String(page.getByteArray(), StandardCharsets.UTF_8);
    final var matcher = SHA256_FRAGMENT.matcher(html);

    assertThat(matcher.find()).as("the project page has a #sha256= fragment").isTrue();
    assertThat(matcher.group(1)).isEqualTo(realDigest);
  }

  @Test
  @DisplayName("rejects a wrong digest with 400 sha256DigestMismatch and persists nothing")
  void rejectsAWrongDigestWithoutPersistingAnything() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-2.0.0-py3-none-any.whl";
    final var content = "real wheel bytes".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> this.upload(repo, "my-package", "2.0.0", filename, content, "deadbeef"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sha256DigestMismatch");

    assertThatThrownBy(
            () ->
                this.pypiProtocolFacade.downloadArchiveFile(context(repo), "my-package", filename))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }
}
