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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-1424: {@code DELETE /api/charts/<name>/<version>} removes a chart's stored files, so it needs
 * MANAGE (the ADMIN role) like the panel's delete, and a deploy token never has MANAGE. Pushing a
 * chart stays a WRITE. A USER-role account and a read-write deploy token are refused with a 401
 * challenge and the chart stays; an admin deletes it.
 */
@DisplayName("Helm classic chart delete needs MANAGE (RPS-1424)")
class HelmChartDeletePermissionIT extends AbstractIntegrationTest {

  private static final String CHART = "payments";
  private static final String VERSION = "1.0.0";
  private static final String DELETE_PATH = "/{repo}/api/charts/{name}/{version}";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private Repo repoWithChart() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-rps1424"), true, null);
    final var pushed = this.push(repo, this.adminProtocolBearerToken());

    assertThat(pushed.getStatus()).as("push by an admin").isEqualTo(201);

    return repo;
  }

  private MockHttpServletResponse push(final Repo repo, final String authorization)
      throws Exception {
    return this.mockMvc
        .perform(
            multipart(UPLOAD_PATH, repo.getName())
                .part(new MockPart("chart", CHART + "-" + VERSION + ".tgz", chart(CHART, VERSION)))
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse deleteChart(final Repo repo, final String authorization)
      throws Exception {
    return this.mockMvc
        .perform(
            delete(DELETE_PATH, repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private int downloadStatus(final Repo repo) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/charts/{name}-{version}.tgz", repo.getName(), CHART, VERSION)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedToken(final Repo repo, final boolean readOnly) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  private Map<String, String> deployTokenCredentials(final Repo repo, final boolean readOnly) {
    final var secret = this.seedToken(repo, readOnly);
    final var kind = readOnly ? "read-only" : "read-write";
    final var credentials = new LinkedHashMap<String, String>();

    credentials.put(kind + " deploy token as the Basic password", basicAuth("helm", secret));
    credentials.put(kind + " deploy token as a raw Bearer", AuthUtils.AUTH_BEARER + secret);

    return credentials;
  }

  private void assertRefused(final Repo repo, final String credential, final String authorization)
      throws Exception {

    final var response = this.deleteChart(repo, authorization);

    assertThat(response.getStatus()).as("delete with %s", credential).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).as("its challenge").isNotBlank();
  }

  @Test
  @DisplayName("a USER-role account cannot delete a chart version, and the chart stays")
  void userCannotDelete() throws Exception {
    final var repo = this.repoWithChart();
    final var user = this.createUser(uniqueUsername("rps1424"), UserRole.USER);

    this.assertRefused(repo, "a USER", basicAuth(user.getUsername(), VALID_PASSWORD));

    assertThat(this.downloadStatus(repo)).isEqualTo(200);
  }

  @Test
  @DisplayName("a read-write deploy token cannot delete a chart version, and the chart stays")
  void readWriteDeployTokenCannotDelete() throws Exception {
    final var repo = this.repoWithChart();

    for (final var credential : this.deployTokenCredentials(repo, false).entrySet()) {
      this.assertRefused(repo, credential.getKey(), credential.getValue());
    }

    assertThat(this.downloadStatus(repo)).isEqualTo(200);
  }

  @Test
  @DisplayName("a read-only deploy token cannot delete a chart version either")
  void readOnlyDeployTokenCannotDelete() throws Exception {
    final var repo = this.repoWithChart();

    for (final var credential : this.deployTokenCredentials(repo, true).entrySet()) {
      this.assertRefused(repo, credential.getKey(), credential.getValue());
    }

    assertThat(this.downloadStatus(repo)).isEqualTo(200);
  }

  @Test
  @DisplayName("an admin deletes a chart version")
  void adminCanDelete() throws Exception {
    final var repo = this.repoWithChart();

    assertThat(this.deleteChart(repo, this.adminProtocolBearerToken()).getStatus()).isEqualTo(200);
    assertThat(this.downloadStatus(repo)).isEqualTo(404);
  }

  @Test
  @DisplayName("a USER-role account and a read-write deploy token still push a chart")
  void pushStaysWrite() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-rps1424"), true, null);
    final var user = this.createUser(uniqueUsername("rps1424"), UserRole.USER);

    assertThat(this.push(repo, basicAuth(user.getUsername(), VALID_PASSWORD)).getStatus())
        .as("push by a USER")
        .isEqualTo(201);

    // One repo per credential: a second push of the same version would be a conflict, not a WRITE
    // check.
    final var secretRepo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm-rps1424"), true, null);
    final var basicToken = basicAuth("helm", this.seedToken(secretRepo, false));
    assertThat(this.push(secretRepo, basicToken).getStatus())
        .as("push with a read-write deploy token")
        .isEqualTo(201);
  }
}
