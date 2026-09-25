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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1086: deleting a chart version removes its rows first and its files second, inside one
 * transaction, and a failure of the storage while the files go must not commit the rows.
 *
 * <p>The storage failure is an {@link IOException}, a checked exception, which Spring does not roll
 * back for on its own. Without {@code rollbackFor = IOException.class} on the facades, the chart
 * disappeared from the listings while its archive stayed in storage, and the bytes never left the
 * repo's disk usage.
 *
 * <p>Runs without a test transaction: inside one, the facade would join it and the rollback could
 * not be told apart from the test's own. It deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Helm chart delete keeps its rows when the storage fails (RPS-1086)")
class HelmDeleteStorageFailureIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String VERSION = "1.0.0";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the tests that stub it change the storage behaviour. */
  @MockitoSpyBean private HelmStorageService helmStorageService;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the charts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo helmRepoWithChart() throws Exception {
    final var name = uniqueRepoName("helm-rb");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    final var repo = this.repoRepository.findByName(name).orElseThrow();
    final var pushed =
        this.mockMvc
            .perform(
                multipart(UPLOAD_PATH, repo.getName())
                    .part(
                        new MockPart(
                            "chart", CHART + "-" + VERSION + ".tgz", chart(CHART, VERSION)))
                    .header(AUTHORIZATION, this.protocolAdminToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(pushed.getStatus()).as("chart upload").isEqualTo(201);
    assertThat(this.storedVersions(repo)).as("the chart is stored").isEqualTo(1);

    return repo;
  }

  private UUID adminId() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("helm-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return userInfo.getId();
  }

  private String panelAdminToken() {
    return this.bearerTokenFor(this.userRepository.findById(this.adminId()).orElseThrow());
  }

  private String protocolAdminToken() {
    return this.protocolBearerTokenFor(this.userRepository.findById(this.adminId()).orElseThrow());
  }

  private int storedVersions(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*)
              from helm_chart_version v join helm_chart c on c.id = v.chart_id
              where c.repo_id = ? and c.name = ? and v.version = ?
            """,
            Integer.class,
            repo.getId(),
            CHART,
            VERSION);

    return count == null ? 0 : count;
  }

  /** Makes the storage fail as it goes to remove the archive of a chart version. */
  private void failToDeleteTheArchive() throws IOException {
    doThrow(new IOException("storage went away while deleting the archive"))
        .when(this.helmStorageService)
        .deleteChartFile(any(), any(), any(), any());
  }

  private MockHttpServletResponse panelDeleteVersion(final Repo repo) throws Exception {
    return this.perform(
            delete("/api/helm/charts/{repo}/{name}/{version}", repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, this.panelAdminToken()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse panelDeleteAllVersions(final Repo repo) throws Exception {
    return this.perform(
            delete("/api/helm/charts/{repo}/{name}", repo.getName(), CHART)
                .header(AUTHORIZATION, this.panelAdminToken()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse protocolDelete(final Repo repo) throws Exception {
    return this.mockMvc
        .perform(
            delete("/{repo}/api/charts/{name}/{version}", repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, this.protocolAdminToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @Test
  @DisplayName("the panel's delete of one version keeps the version when the archive cannot go")
  void panelVersionDeleteRollsBack() throws Exception {
    final var repo = this.helmRepoWithChart();
    this.failToDeleteTheArchive();

    final var response = this.panelDeleteVersion(repo);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersions(repo)).as("the version row was rolled back").isEqualTo(1);

    Mockito.reset(this.helmStorageService);
    assertThat(this.panelDeleteVersion(repo).getStatus()).as("the retry").isEqualTo(200);
    assertThat(this.storedVersions(repo)).isZero();
  }

  @Test
  @DisplayName("the panel's delete of a whole chart keeps its versions when an archive cannot go")
  void panelChartDeleteRollsBack() throws Exception {
    final var repo = this.helmRepoWithChart();
    this.failToDeleteTheArchive();

    final var response = this.panelDeleteAllVersions(repo);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersions(repo)).as("the chart rows were rolled back").isEqualTo(1);

    Mockito.reset(this.helmStorageService);
    assertThat(this.panelDeleteAllVersions(repo).getStatus()).as("the retry").isEqualTo(200);
    assertThat(this.storedVersions(repo)).isZero();
  }

  @Test
  @DisplayName("the protocol's delete keeps the version when the archive cannot go")
  void protocolDeleteRollsBack() throws Exception {
    final var repo = this.helmRepoWithChart();
    this.failToDeleteTheArchive();

    final var response = this.protocolDelete(repo);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersions(repo)).as("the version row was rolled back").isEqualTo(1);

    Mockito.reset(this.helmStorageService);
    assertThat(this.protocolDelete(repo).getStatus()).as("the retry").isEqualTo(200);
    assertThat(this.storedVersions(repo)).isZero();
  }
}
