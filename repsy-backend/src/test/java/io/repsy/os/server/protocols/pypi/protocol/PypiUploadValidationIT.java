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
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1224: a missing {@code sha256_digest} form field is a {@code 400 sha256DigestMissing}, not an
 * unhandled 500, and the check runs before anything is written to storage (no orphan).
 */
@DisplayName("PyPI upload sha256_digest presence validation (RPS-1224)")
class PypiUploadValidationIT extends AbstractIntegrationTest {

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

  @Test
  @DisplayName("a missing sha256_digest is a 400 sha256DigestMissing, not a 500")
  void missingSha256DigestIsA400NotA500() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-1.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "1.0.0");
    parameters.put("requires_python", ">=3.9");
    // No sha256_digest field at all.

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sha256DigestMissing");
  }

  @Test
  @DisplayName("the rejected upload leaves nothing downloadable -- the check runs before the write")
  void missingSha256DigestLeavesNothingInStorage() throws Exception {
    final var repo = this.createRepo();
    final var filename = "my_package-2.0.0-py3-none-any.whl";
    final var content = "content".getBytes(StandardCharsets.UTF_8);

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", "my-package");
    parameters.put("version", "2.0.0");
    parameters.put("requires_python", ">=3.9");

    final var file =
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

    assertThatThrownBy(() -> this.pypiProtocolFacade.uploadPackage(context(repo), parameters, file))
        .isInstanceOf(BadRequestException.class);

    assertThatThrownBy(
            () ->
                this.pypiProtocolFacade.downloadArchiveFile(context(repo), "my-package", filename))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("itemNotFound");
  }
}
