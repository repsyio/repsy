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

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-2114: deleting a Docker repo does not run the storage delete inside a transaction.
 *
 * <p>The facade used to be class-level {@code @Transactional}, so the whole blob tree was deleted
 * while a pooled connection and the row locks of the image and layer deletes were held. The storage
 * delete now probes whether a transaction is active on the request thread; a slow storage call can
 * therefore no longer hold a connection.
 *
 * <p>Runs without a test transaction (the test transaction would itself be the active one), so it
 * deletes what it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Docker repo delete holds no transaction during the storage call (RPS-2114)")
class DockerRepoDeleteNoTransactionIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @MockitoSpyBean private DockerStorageService dockerStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String panelToken;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(uniqueUsername("repodel"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.panelToken = this.bearerTokenFor(user);
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.protocolBearerTokenFor(user));
  }

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo repoWithTwoImages() throws Exception {
    final var name = uniqueRepoName("docker-repodel");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());
    final var repo = this.repoRepository.findByName(name).orElseThrow();

    for (final var image : List.of(IMAGE, "other")) {
      this.wire.pushBlobsOf(repo, image, "layer-" + image);
      assertThat(
              this.wire
                  .putImage(repo, image, "latest", imageManifest("layer-" + image))
                  .getStatus())
          .isEqualTo(201);
    }

    return repo;
  }

  private MockHttpServletResponse deleteRepo(final Repo repo) throws Exception {
    return this.perform(
            delete("/api/repos/{repo}", repo.getName()).header(AUTHORIZATION, this.panelToken))
        .andReturn()
        .getResponse();
  }

  private int count(final String sql, final Object... args) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, args);

    return count == null ? 0 : count;
  }

  private int imageRows(final Repo repo) {
    return this.count("select count(*) from docker_image where repo_id = ?", repo.getId());
  }

  private int layerRows(final Repo repo) {
    return this.count("select count(*) from docker_layer where repo_id = ?", repo.getId());
  }

  private int repoRows(final Repo repo) {
    return this.count("select count(*) from repo where id = ?", repo.getId());
  }

  @Test
  @DisplayName("the storage delete of a repo runs with no transaction open")
  void storageDeleteRunsOutsideAnyTransaction() throws Exception {
    final var repo = this.repoWithTwoImages();
    final var transactionActive = new AtomicBoolean(true);
    final var probed = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              probed.set(true);
              transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());

              return invocation.callRealMethod();
            })
        .when(this.dockerStorageService)
        .deleteRepo(repo.getId());

    final var response = this.deleteRepo(repo);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(204);
    assertThat(probed).as("the storage delete was called").isTrue();
    assertThat(transactionActive)
        .as("a slow storage delete would hold a pooled connection and the row locks")
        .isFalse();
    assertThat(this.imageRows(repo)).isZero();
    assertThat(this.layerRows(repo)).isZero();
    assertThat(this.repoRows(repo)).isZero();
  }

  @Test
  @DisplayName("a storage failure keeps the repo row and a repeated delete finishes the job")
  void aFailedStorageDeleteCanBeRepeated() throws Exception {
    final var repo = this.repoWithTwoImages();
    doThrow(new UncheckedIOException(new IOException("disk gone")))
        .when(this.dockerStorageService)
        .deleteRepo(repo.getId());

    final var failed = this.deleteRepo(repo);

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isEqualTo(500);
    assertThat(this.imageRows(repo)).as("the rows were committed before storage").isZero();
    assertThat(this.layerRows(repo)).isZero();
    assertThat(this.repoRows(repo)).as("the repo row goes last").isEqualTo(1);

    Mockito.reset(this.dockerStorageService);
    final var repeated = this.deleteRepo(repo);

    assertThat(repeated.getStatus()).as(repeated.getContentAsString()).isEqualTo(204);
    assertThat(this.repoRows(repo)).isZero();
  }
}
