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
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1614: the {@code /simple/} root index listed the packages in the order the database kept the
 * rows (a query with no {@code ORDER BY}). It lists them by normalized name, the order pypi.org
 * uses, so a mirror or a client that compares the page between requests sees the same one.
 */
@DisplayName("PyPI /simple/ root index lists packages by normalized name (RPS-1614)")
class PypiSimpleIndexOrderIT extends AbstractIntegrationTest {

  private static final Pattern HREF = Pattern.compile("/simple/([^/]+)/\"");

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private RepoInfo createRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(
            uniqueRepoName("pypi"), RepoType.PYPI, false, "PyPI index order IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());

    return repoInfo;
  }

  private void publish(final RepoInfo repo, final String packageName) throws Exception {
    final var content = ("wheel of " + packageName).getBytes(StandardCharsets.UTF_8);
    final var context = new ProtocolContext();

    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", "1.0.0");
    parameters.put("requires_python", ">=3.9");
    parameters.put(
        "sha256_digest",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));

    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content",
            packageName.replace('-', '_') + "-1.0.0-py3-none-any.whl",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            content));
  }

  @Test
  @DisplayName("published as zebra, mango, alpha: served as alpha, mango, zebra")
  void listsPackagesByNormalizedName() throws Exception {
    final var repo = this.createRepo();

    this.publish(repo, "zebra");
    this.publish(repo, "mango");
    this.publish(repo, "alpha");

    final var body =
        this.mockMvc
            .perform(get("/{repo}/simple/", repo.getName()).with(protocolPort()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    final List<String> names = HREF.matcher(body).results().map(m -> m.group(1)).toList();

    assertThat(names).containsExactly("alpha", "mango", "zebra");
  }
}
