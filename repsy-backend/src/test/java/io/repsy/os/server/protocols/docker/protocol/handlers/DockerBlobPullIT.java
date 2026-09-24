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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.bytes;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1244: {@code GET /v2/<repo>/<image>/blobs/<digest>} of a blob pushed by a {@code sha256:} or
 * {@code sha512:} digest streams that blob and reports that digest.
 *
 * <p>{@code DockerProtocolTxFacade.getLayer} runs {@code NOT_SUPPORTED}, so it cannot see the rows
 * a rolled-back test transaction wrote: this class runs without a test transaction, commits its
 * repo, user and blobs, and deletes exactly those in {@code @AfterEach}. {@link UsageUpdateService}
 * is mocked: it is {@code @Async} and irrelevant here.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Docker blob GET by digest (RPS-1244)")
class DockerBlobPullIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-blob");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("docker-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private void pushBlob(final Repo repo, final byte[] blob, final String digest, final String token)
      throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
  }

  private MockHttpServletResponse getBlob(final Repo repo, final String digest, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/blobs/{digest}", repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("GET of a blob by its digest streams the blob and reports that digest")
  void getsABlobByItsDigest(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminToken();
    final var blob = bytes(algorithm + "-addressed-blob-content");
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(repo, blob, digest, token);

    final var response = this.getBlob(repo, digest, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsByteArray()).isEqualTo(blob);
    assertThat(response.getHeader("Docker-Content-Digest")).isEqualTo(digest);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("GET of a digest that names no blob of the repo is 404")
  void anUnknownBlobIs404(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminToken();
    final var blob = bytes("another-" + algorithm + "-blob");
    this.pushBlob(repo, blob, "sha512".equals(algorithm) ? sha512(blob) : sha256(blob), token);

    final var unknown = "sha512".equals(algorithm) ? "0".repeat(128) : "0".repeat(64);

    assertThat(this.getBlob(repo, algorithm + ":" + unknown, token).getStatus()).isEqualTo(404);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("a blob is not served by another repo, whatever the digest algorithm")
  void aBlobIsNotServedByAnotherRepo(final String algorithm) throws Exception {
    final var owner = this.dockerRepo();
    final var other = this.dockerRepo();
    final var token = this.adminToken();
    final var blob = bytes("owned-" + algorithm + "-blob");
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(owner, blob, digest, token);

    assertThat(this.getBlob(owner, digest, token).getStatus()).isEqualTo(200);
    assertThat(this.getBlob(other, digest, token).getStatus()).isEqualTo(404);
  }
}
