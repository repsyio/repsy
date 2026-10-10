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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.yaml.snakeyaml.Yaml;

/**
 * RPS-1614: {@code index.yaml} was built from a query with no {@code ORDER BY}, so its entries came
 * in the order the database happened to keep the rows, which changes with every update of a row.
 * The index lists the charts by name and the versions of a chart highest first, as {@code helm repo
 * index} writes it.
 */
@DisplayName("Helm index.yaml lists charts by name and versions highest first (RPS-1614)")
class HelmIndexOrderIT extends AbstractIT {

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  private void upload(final Repo repo, final String name, final String version) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                multipart(UPLOAD_PATH, repo.getName())
                    .part(new MockPart("chart", "chart.tgz", chart(name, version)))
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<Map<String, Object>>> entries(final Repo repo) throws Exception {
    final var body =
        this.mockMvc
            .perform(
                get("/{repo}/index.yaml", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    final var index = (Map<String, Object>) new Yaml().load(body);

    return (Map<String, List<Map<String, Object>>>) index.get("entries");
  }

  @Test
  @DisplayName("pushed as zeta 1.0.0, alpha 1.9.0, 2.0.0, 1.10.0-rc.1 and 1.10.0")
  void listsChartsByNameAndVersionsHighestFirst() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("rps1614"));

    this.upload(repo, "zeta", "1.0.0");
    this.upload(repo, "alpha", "1.9.0");
    this.upload(repo, "alpha", "2.0.0");
    this.upload(repo, "alpha", "1.10.0-rc.1");
    this.upload(repo, "alpha", "1.10.0");

    final var entries = this.entries(repo);

    assertThat(entries.keySet()).containsExactly("alpha", "zeta");
    assertThat(entries.get("alpha").stream().map(entry -> (String) entry.get("version")))
        .containsExactly("2.0.0", "1.10.0", "1.10.0-rc.1", "1.9.0");
  }
}
