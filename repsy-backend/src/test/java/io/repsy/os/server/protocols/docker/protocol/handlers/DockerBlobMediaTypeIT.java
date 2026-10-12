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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
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
 * RPS-2213 (audit of RPS-2172 item 2): the {@code Content-Type} of {@code GET
 * /v2/<repo>/<image>/blobs/<digest>} is the media type stored on the layer row, the one the blob
 * {@code HEAD} reports.
 *
 * <p>{@code DockerProtocolTxFacade.getLayerMediaType} runs {@code NOT_SUPPORTED}, so it cannot see
 * the rows a rolled-back test transaction wrote and would answer the {@code
 * application/octet-stream} fallback in such a test. This class runs without a test transaction,
 * commits its repo, user and blobs, and deletes exactly those in {@code @AfterEach}. It changes the
 * stored media type of a committed layer, so a handler that answers a constant instead of the
 * stored value fails here (the wire transcript of {@code OciWireCharacterizationIT} stores only the
 * default type and cannot tell the two apart).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Docker blob GET answers the stored layer media type (RPS-2213)")
class DockerBlobMediaTypeIT extends AbstractIT {

  private static final String IMAGE = "app";
  private static final String DOCKER_LAYER = "application/vnd.docker.image.rootfs.diff.tar.gzip";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

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
    final var name = uniqueRepoName("docker-mt");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("docker-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
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

  private MockHttpServletResponse headBlob(final Repo repo, final String digest, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            head("/v2/{repo}/{image}/blobs/{digest}", repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private String storedMediaType(final Repo repo, final String digest) {
    return this.jdbcTemplate.queryForObject(
        "select media_type from docker_layer where repo_id = ? and digest = ?",
        String.class,
        repo.getId(),
        digest);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("GET of a pushed blob answers the media type the blob HEAD reports")
  void getAnswersTheMediaTypeOfTheHead(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminToken();
    final var blob = bytes(algorithm + "-media-type-blob");
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(repo, blob, digest, token);

    final var get = this.getBlob(repo, digest, token);
    final var head = this.headBlob(repo, digest, token);

    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(this.storedMediaType(repo, digest)).isEqualTo(DOCKER_LAYER);
    assertThat(get.getContentType()).isEqualTo(DOCKER_LAYER);
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("GET answers the media type stored on the layer row, not a fixed one")
  void getAnswersTheStoredMediaType(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminToken();
    final var blob = bytes(algorithm + "-stored-media-type-blob");
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(repo, blob, digest, token);
    this.jdbcTemplate.update(
        "update docker_layer set media_type = ? where repo_id = ? and digest = ?",
        OCI_LAYER,
        repo.getId(),
        digest);

    final var get = this.getBlob(repo, digest, token);
    final var head = this.headBlob(repo, digest, token);

    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(get.getContentType()).isEqualTo(OCI_LAYER);
    assertThat(head.getContentType()).isEqualTo(OCI_LAYER);
    assertThat(get.getContentAsByteArray()).isEqualTo(blob);
  }
}
