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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.shared.token.DeployTokenFixtures;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * RPS-2739: the request rules of a twine upload ({@code POST /{repo}/}) that only the e2e suite
 * pinned over HTTP: a read-only deploy token is refused, a body that is not multipart matches no
 * handler, a multipart body without a {@code content} part is a bodyless 400, and the repo URL
 * works with and without its trailing slash. They run through the protocol router on MockMvc;
 * nothing here needs a real port.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async} and cannot see this class's
 * uncommitted rows. Every test runs in the default rolled-back transaction.
 */
@DisplayName("PyPI upload request rules: read-only token, non-multipart, trailing slash")
class PypiUploadRequestRulesIT extends AbstractIT {

  private static final String PACKAGE = "request-rules";
  private static final String FILENAME = "request_rules-1.0.0-py3-none-any.whl";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private Repo pypiRepo() {
    return this.seedRepo(RepoType.PYPI, uniqueRepoName("pypi"));
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  // The upload handler only takes a request the servlet container resolved to multipart. MockMvc's
  // multipart(...) builder makes one up front, which a security wrapper then hides, so the body is
  // posted as a plain multipart/form-data request with the file as a part and the form fields as
  // parameters, and DispatcherServlet resolves it the way it does on the wire.

  private static RequestPostProcessor part(final MockPart part) {
    return request -> {
      request.addPart(part);
      return request;
    };
  }

  /** A twine upload form posted to {@code url} (the repo root, with or without its slash). */
  private MockHttpServletResponse upload(
      final String url, final Repo repo, final String authorization) throws Exception {
    final var content = "wheel bytes".getBytes(StandardCharsets.UTF_8);
    final var file = new MockPart("content", FILENAME, content);

    return this.mockMvc
        .perform(
            post(url, repo.getName())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .with(part(file))
                .param(":action", "file_upload")
                .param("name", PACKAGE)
                .param("version", "1.0.0")
                .param("requires_python", ">=3.9")
                .param("sha256_digest", sha256Hex(content))
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private int projectPageStatus(final Repo repo, final String authorization) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/simple/" + PACKAGE + "/", repo.getName())
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int releaseCount(final Repo repo) {
    this.entityManager.flush();

    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from pypi_release r join pypi_package p on p.id = r.package_id
        where p.repo_id = ?""",
        Integer.class,
        repo.getId());
  }

  @Test
  @DisplayName("a read-only deploy token upload is a flat 401 unAuthorized, and it still reads")
  void readOnlyTokenCannotUpload() throws Exception {
    final var repo = this.pypiRepo();
    final var token =
        DeployTokenFixtures.seed(
            repo, true, this.repoRepository, this.deployTokenRepository, this.entityManager);
    final var credential = basicAuth(token.username(), token.secret());

    final var refused = this.upload("/{repo}/", repo, credential);

    assertThat(refused.getStatus()).isEqualTo(401);
    assertThat(refused.getContentAsString()).contains("\"msgId\":\"unAuthorized\"");
    assertThat(this.releaseCount(repo)).isZero();

    assertThat(this.upload("/{repo}/", repo, this.adminProtocolBearerToken()).getStatus())
        .isEqualTo(200);
    assertThat(this.projectPageStatus(repo, credential)).isEqualTo(200);
  }

  @Test
  @DisplayName("a JSON POST to the repo root matches no handler: 404 unknownPath")
  void nonMultipartPostIsUnknownPath() throws Exception {
    final var repo = this.pypiRepo();

    final var response =
        this.mockMvc
            .perform(
                post("/{repo}/", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentAsString()).contains("\"msgId\":\"unknownPath\"");
    assertThat(this.releaseCount(repo)).isZero();
  }

  @Test
  @DisplayName("a multipart POST without a content part is a 400 with no body")
  void multipartWithoutContentPartIsABodylessBadRequest() throws Exception {
    final var repo = this.pypiRepo();

    final var response =
        this.mockMvc
            .perform(
                post("/{repo}/", repo.getName())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .param("name", "not-a-real-upload")
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(this.releaseCount(repo)).isZero();
  }

  @Test
  @DisplayName("POST /{repo} without the trailing slash is accepted like POST /{repo}/")
  void postWithoutTrailingSlashIsAccepted() throws Exception {
    final var repo = this.pypiRepo();

    final var response = this.upload("/{repo}", repo, this.adminProtocolBearerToken());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(this.releaseCount(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("POST /{repo}/simple is the read-only index route and refuses an upload: 404")
  void postToTheSimpleIndexIsRefused() throws Exception {
    final var repo = this.pypiRepo();

    final var response = this.upload("/{repo}/simple", repo, this.adminProtocolBearerToken());

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(this.releaseCount(repo)).isZero();
  }
}
