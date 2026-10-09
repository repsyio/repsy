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
package io.repsy.os.server.protocols.helm;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.RANGE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-2090: {@code GET /v2/<repo>/<chart>/blobs/<digest>} of a Helm OCI chart layer (the Helm twin
 * of {@code DockerBlobPullIT}): the blob is streamed with {@code Docker-Content-Digest}, an unknown
 * digest is {@code 404 BLOB_UNKNOWN}, and a blob is served by its repo only. Only {@code sha256:}
 * digests are routed (the handler's path pattern), so a {@code sha512:} digest is not a blob pull.
 *
 * <p>The handler is {@code HelmOciBlobPullProtocolMethodHandler}. It has no chart-name check: the
 * layer is addressed by digest within the repo, so another chart name of the same repo serves it
 * too (asserted below, as today's behaviour).
 */
@DisplayName("Helm OCI blob GET by digest (RPS-2090)")
class HelmOciBlobPullIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String OTHER_CHART = "invoices";
  private static final String OCTET_STREAM = "application/octet-stream";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private Repo helmRepo() {
    return this.seedRepo(RepoType.HELM, uniqueRepoName("helm-blob"));
  }

  private void pushBlob(
      final Repo repo,
      final String chartName,
      final byte[] blob,
      final String digest,
      final String token)
      throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), chartName)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).as("blob upload start").isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), chartName, uploadId)
                    .param("digest", digest)
                    .contentType(OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
  }

  private MockHttpServletResponse getBlob(
      final Repo repo, final String chartName, final String digest, final String token)
      throws Exception {
    final var request =
        get("/v2/{repo}/{name}/blobs/{digest}", repo.getName(), chartName, digest)
            .with(protocolPort());
    if (token != null) {
      request.header(AUTHORIZATION, token);
    }

    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  @Test
  @DisplayName("GET of a pushed chart layer streams it and reports its digest")
  void getsABlobByItsDigest() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "1.0.0");
    final var digest = digest("SHA-256", blob);
    this.pushBlob(repo, CHART, blob, digest, token);

    final var response = this.getBlob(repo, CHART, digest, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsByteArray()).isEqualTo(blob);
    assertThat(response.getHeader("Docker-Content-Digest")).isEqualTo(digest);
    assertThat(response.getContentType()).isEqualTo(OCTET_STREAM);
  }

  @Test
  @DisplayName("GET of a digest that names no blob of the repo is 404 BLOB_UNKNOWN")
  void anUnknownBlobIs404() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "1.0.0");
    this.pushBlob(repo, CHART, blob, digest("SHA-256", blob), token);

    final var response = this.getBlob(repo, CHART, "sha256:" + "0".repeat(64), token);

    assertThat(response.getStatus()).isEqualTo(404);
    final var body = response.getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("BLOB_UNKNOWN");
  }

  @Test
  @DisplayName("a blob is not served by another repo")
  void aBlobIsNotServedByAnotherRepo() throws Exception {
    final var owner = this.helmRepo();
    final var other = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "2.0.0");
    final var digest = digest("SHA-256", blob);
    this.pushBlob(owner, CHART, blob, digest, token);

    assertThat(this.getBlob(owner, CHART, digest, token).getStatus()).isEqualTo(200);
    assertThat(this.getBlob(other, CHART, digest, token).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("the layer of one chart is served under another chart name of the same repo")
  void aBlobIsServedUnderAnotherChartOfTheSameRepo() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "3.0.0");
    final var digest = digest("SHA-256", blob);
    this.pushBlob(repo, CHART, blob, digest, token);

    final var response = this.getBlob(repo, OTHER_CHART, digest, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsByteArray()).isEqualTo(blob);
  }

  @Test
  @DisplayName("a private repo refuses a blob pull without credentials")
  void privateRepoIsAuthGated() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-blob"), true, null);
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "4.0.0");
    final var digest = digest("SHA-256", blob);
    this.pushBlob(repo, CHART, blob, digest, token);

    assertThat(this.getBlob(repo, CHART, digest, null).getStatus()).isEqualTo(401);
    assertThat(this.getBlob(repo, CHART, digest, token).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a digest that is not sha256 is not routed to the blob pull")
  void aNonSha256DigestIsNotABlobPull() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();

    final var response = this.getBlob(repo, CHART, "sha512:" + "0".repeat(128), token);

    assertThat(response.getStatus()).isGreaterThanOrEqualTo(400);
  }

  @Test
  @DisplayName("a Range request is answered with the requested bytes")
  void rangeRequest() throws Exception {
    final var repo = this.helmRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = chart(CHART, "5.0.0");
    final var digest = digest("SHA-256", blob);
    this.pushBlob(repo, CHART, blob, digest, token);

    final var response =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{name}/blobs/{digest}", repo.getName(), CHART, digest)
                    .header(AUTHORIZATION, token)
                    .header(RANGE, "bytes=0-9")
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as("Range status").isEqualTo(206);
    assertThat(response.getContentAsByteArray()).hasSize(10);
  }
}
