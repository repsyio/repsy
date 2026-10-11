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

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseRepository;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-2077: a release published without {@code requires_python}, and a package that has only a
 * pre-release, have a null {@code requires_python} and a null {@code stable_version}. The pypi
 * packages are {@code @NullMarked}, and Spring Data guards the getters of an interface projection
 * under the marking, so a getter that answers null without {@code @Nullable} throws {@code "Return
 * value is null but must not be null"} and the list answers 500 where it answered the null before
 * the marking. The projections declare those getters {@code @Nullable}; this is the flip-and-fail
 * of that.
 */
@DisplayName("PyPI projections of nullable columns (RPS-2077)")
class PypiNullableProjectionIT extends AbstractIT {

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private PypiProtocolFacade pypiProtocolFacade;
  @Autowired private PypiPackageRepository pypiPackageRepository;
  @Autowired private ReleaseRepository releaseRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private RepoInfo publishPreReleaseWithoutRequiresPython(final String packageName)
      throws Exception {
    final var repo =
        this.repoTxService.createRepo(
            uniqueRepoName("pypi"), RepoType.PYPI, false, "PyPI nullable projection IT repo");
    this.entityManager.flush();
    this.pypiApiFacade.createRepo(repo.getStorageKey());

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
    parameters.put("version", "1.0.0a1");
    parameters.put(
        "sha256_digest",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));

    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content",
            packageName.replace('-', '_') + "-1.0.0a1-py3-none-any.whl",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            content));
    this.entityManager.flush();

    return repo;
  }

  private UUID packageId(final RepoInfo repo) {
    return this.jdbcTemplate.queryForObject(
        "select id from pypi_package where repo_id = ?", UUID.class, repo.getId());
  }

  @Test
  @DisplayName("the package list answers a null stable version")
  void packageListAnswersNullStableVersion() throws Exception {
    final var repo = this.publishPreReleaseWithoutRequiresPython("only-pre");

    final var page = this.pypiPackageRepository.findAllByRepoId(repo.getId(), Pageable.unpaged());

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getLatestVersion()).isEqualTo("1.0.0a1");
    assertThat(page.getContent().get(0).getStableVersion()).isNull();
  }

  @Test
  @DisplayName("the releases of the package answer a null requires_python")
  void releasesAnswerNullRequiresPython() throws Exception {
    final var repo = this.publishPreReleaseWithoutRequiresPython("no-requires");

    this.jdbcTemplate.update(
        "update pypi_release set requires_python = null where package_id = ?",
        this.packageId(repo));
    this.entityManager.clear();

    final var releases = this.releaseRepository.findAllByPypiPackageId(this.packageId(repo));

    assertThat(releases).hasSize(1);
    assertThat(releases.get(0).getVersion()).isEqualTo("1.0.0a1");
    assertThat(releases.get(0).getRequiresPython()).isNull();
  }
}
