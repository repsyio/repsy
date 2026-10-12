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

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-2213 (audit of RPS-2172 item 2): {@code DELETE /api/repos/<repo>} of a Helm repo that holds a
 * classic chart and an OCI blob removes the rows and the whole storage directory.
 *
 * <p>{@code HelmApiFacade.deleteRepo} runs {@code NOT_SUPPORTED} and deletes the storage tree; the
 * repo row (and the chart rows through the cascade) goes in the caller's transaction after it.
 * {@code ProtocolRepoControllerIT} only deletes an empty Helm repo, inside a rolled-back test
 * transaction. This class runs without a test transaction, commits its repo, user and content, and
 * deletes exactly those in {@code @AfterEach}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Helm repo delete with content (RPS-2213)")
class HelmRepoDeleteWithContentIT extends AbstractIT {

  private static final String CHART = "payments";
  private static final String VERSION = "1.0.0";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmStorageService helmStorageService;

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

  private Repo helmRepo() {
    final var name = uniqueRepoName("helm-del");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  @Test
  @DisplayName("removes the chart rows and the storage directory of a repo with content")
  void deletesRepoWithChartAndBlob() throws Exception {
    final var repo = this.helmRepo();
    final var user =
        this.userTxService.create(
            uniqueUsername("helm-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(user.getId());
    final var userEntity = this.userRepository.findById(user.getId()).orElseThrow();
    final var protocolToken = this.protocolBearerTokenFor(userEntity);
    final var panelToken = this.bearerTokenFor(userEntity);

    final var chartPush =
        this.mockMvc
            .perform(
                multipart(UPLOAD_PATH, repo.getName())
                    .part(
                        new MockPart(
                            "chart", CHART + "-" + VERSION + ".tgz", chart(CHART, VERSION)))
                    .header(AUTHORIZATION, protocolToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(chartPush.getStatus()).as("chart upload").isEqualTo(201);

    final var blob = chart("invoices", "2.0.0");
    final var blobDigest = digest("SHA-256", blob);
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), "invoices")
                    .header(AUTHORIZATION, protocolToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).as("blob upload start").isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);
    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), "invoices", uploadId)
                    .param("digest", blobDigest)
                    .contentType("application/octet-stream")
                    .content(blob)
                    .header(AUTHORIZATION, protocolToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);

    assertThat(storageDirOf(repo)).as("the repo has files").isNotEmptyDirectory();
    assertThat(this.chartRows(repo)).as("the chart is committed").isEqualTo(1);
    assertThat(this.blobRows(repo)).as("the blob is committed").isEqualTo(1);

    final var deleted =
        this.perform(delete("/api/repos/{name}", repo.getName()).header(AUTHORIZATION, panelToken))
            .andReturn()
            .getResponse();

    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(this.repoRepository.existsById(repo.getId())).isFalse();
    assertThat(this.chartRows(repo)).isZero();
    assertThat(this.blobRows(repo)).isZero();
    assertThat(storageDirOf(repo)).doesNotExist();
  }

  private int chartRows(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from helm_chart where repo_id = ?", Integer.class, repo.getId());
  }

  private int blobRows(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from helm_oci_blob where repo_id = ?", Integer.class, repo.getId());
  }
}
