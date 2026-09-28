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
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1463: a manifest delete whose disk-usage lookup fails with an {@link IOException} must not
 * delete the manifest file or its rows either.
 *
 * <p>{@code AbstractDockerStorageService.deleteManifest} used to catch that {@code IOException},
 * log it and answer {@code 0L}: the caller (already inside the deleting transaction, its rows
 * flushed) treated that as "nothing to refund" and carried on, so the manifest row was gone, the
 * file stayed on disk and the usage was never refunded — a disk leak and a wrong usage total. The
 * fix lets the failure propagate unchecked, exactly like a failure from {@code
 * StorageStrategy.delete} already does (pinned by {@link DockerDeleteStorageFailureIT}), so the
 * whole transaction rolls back and the file is never touched.
 *
 * <p>Runs without a test transaction, since a rollback is only observable when the request's own
 * transaction rolls back for real; it deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Docker delete when the manifest usage lookup fails (RPS-1463)")
class DockerManifestUsageFailureIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @MockitoBean private UsageUpdateService usageUpdateService;

  @MockitoSpyBean(name = "osStorageStrategyDocker")
  private StorageStrategy storageStrategy;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;
  private String protocolToken;
  private String panelToken;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(uniqueUsername("usagefail"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.protocolToken = this.protocolBearerTokenFor(user);
    this.panelToken = this.bearerTokenFor(user);
    this.wire =
        new DockerWire(
            this.mockMvc, this.webApplicationContext, protocolPort(), this.protocolToken);
  }

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-ufail");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private void failManifestUsageLookup() throws IOException {
    doThrow(new IOException("cannot read manifest size"))
        .when(this.storageStrategy)
        .getFileUsage(any(), any());
  }

  private MockHttpServletResponse deleteManifestByDigest(final Repo repo, final String digest)
      throws Exception {

    return this.mockMvc
        .perform(
            delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, this.protocolToken)
                .with(protocolPort()))
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

  private int tagRows(final Repo repo) {
    return this.count(
        """
        select count(*) from docker_tag t join docker_image i on i.id = t.image_id
        where i.repo_id = ?
        """,
        repo.getId());
  }

  private int manifestRows(final Repo repo) {
    return this.count(
        """
        select count(*) from docker_manifest m join docker_image i on i.id = m.image_id
        where i.repo_id = ?
        """,
        repo.getId());
  }

  private long manifestFiles(final Repo repo) throws IOException {
    try (var files = Files.list(storageDirOf(repo).resolve("manifests"))) {
      return files.count();
    }
  }

  @Test
  @DisplayName("deleting the last manifest whose usage cannot be read keeps its row, tag and file")
  void aFailedUsageLookupKeepsTheManifestAndItsFile() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    final var manifest = imageManifest("layer-one");
    assertThat(this.wire.putImage(repo, IMAGE, "latest", manifest).getStatus()).isEqualTo(201);
    Mockito.reset(this.usageUpdateService); // what the push charged is not under test
    this.failManifestUsageLookup();

    final var failed = this.deleteManifestByDigest(repo, sha256(bytes(manifest)));

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isEqualTo(500);
    assertThat(this.imageRows(repo)).as("the image row came back").isEqualTo(1);
    assertThat(this.tagRows(repo)).isEqualTo(1);
    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.manifestFiles(repo)).as("the manifest file was not deleted").isEqualTo(1);
    verify(this.usageUpdateService, never()).updateUsage(any());

    // Once the usage lookup works again the same delete goes through.
    Mockito.reset(this.storageStrategy);
    final var deleted = this.deleteManifestByDigest(repo, sha256(bytes(manifest)));

    assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(202);
    assertThat(this.imageRows(repo)).isZero();
    assertThat(this.manifestRows(repo)).isZero();
    assertThat(this.manifestFiles(repo)).isZero();
  }
}
