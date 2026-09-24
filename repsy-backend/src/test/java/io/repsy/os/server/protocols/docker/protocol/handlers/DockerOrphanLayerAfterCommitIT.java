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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.ui.facades.DockerApiFacade;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@DisplayName("The blobs of the orphan layers are deleted only after the row deletion commits")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DockerOrphanLayerAfterCommitIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String LAYER = "orphan-layer";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @Autowired private DockerApiFacade dockerApiFacade;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(
            uniqueUsername("orphan"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(info.getId());
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.protocolBearerTokenFor(this.userRepository.findById(info.getId()).orElseThrow()));
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /** A repo with a config blob and one layer blob that no manifest uses. */
  private Repo repoWithOrphanLayers() throws Exception {
    final var name = uniqueRepoName("docker-orphan");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());
    final var repo = this.repoRepository.findByName(name).orElseThrow();
    this.wire.pushBlobsOf(repo, IMAGE, LAYER);
    clearInvocations(this.usageUpdateService);

    return repo;
  }

  private int layerRows(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from docker_layer where repo_id = ?", Integer.class, repo.getId());

    return count == null ? 0 : count;
  }

  private static Path blobOf(final Repo repo, final String layer) {
    return storageDirOf(repo).resolve("blobs").resolve(sha256(bytes(layer)));
  }

  @Test
  @DisplayName("a committed sweep deletes the blobs and refunds their bytes")
  void aCommittedSweepDeletesTheBlobs() throws Exception {
    final var repo = this.repoWithOrphanLayers();
    assertThat(this.layerRows(repo)).as("config + layer").isEqualTo(2);
    assertThat(blobOf(repo, LAYER)).exists();
    final var repoInfo = this.repoTxService.getRepo(repo.getId());

    final var orphans = this.dockerApiFacade.deleteOrphanLayers(repoInfo);

    assertThat(orphans).hasSize(2);
    assertThat(this.layerRows(repo)).isZero();
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(blobOf(repo, LAYER)).doesNotExist());
    final var freed = orphans.stream().mapToLong(orphan -> orphan.size()).sum();
    verify(this.usageUpdateService, timeout(15_000))
        .updateUsage(new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(-freed)));
  }

  @Test
  @DisplayName("a sweep whose transaction rolls back leaves the rows and the blobs in place")
  void aRolledBackSweepLeavesTheBlobs() throws Exception {
    final var repo = this.repoWithOrphanLayers();
    final var repoInfo = this.repoTxService.getRepo(repo.getId());

    assertThatThrownBy(
            () ->
                new TransactionTemplate(this.transactionManager)
                    .executeWithoutResult(
                        status -> {
                          this.dockerApiFacade.deleteOrphanLayers(repoInfo);
                          throw new IllegalStateException("rolled back");
                        }))
        .hasMessage("rolled back");

    // The cleanup is asynchronous: it must stay away for a while, not just not have run yet.
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(this.layerRows(repo)).as("rows came back").isEqualTo(2);
              assertThat(blobOf(repo, LAYER)).as("blob kept").exists();
            });
    verify(this.usageUpdateService, never()).updateUsage(any());

    // Nothing was lost: a sweep that commits still finds and deletes them.
    this.dockerApiFacade.deleteOrphanLayers(repoInfo);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(blobOf(repo, LAYER)).doesNotExist());
  }
}
