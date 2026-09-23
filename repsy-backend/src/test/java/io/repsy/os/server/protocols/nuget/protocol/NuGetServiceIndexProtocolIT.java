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
package io.repsy.os.server.protocols.nuget.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-stack coverage for the NuGet V3 service index ({@code /v3/index.json}) on the protocol port.
 *
 * <p>NuGet.Client matches each resource's {@code @type} by exact string against a fixed vocabulary;
 * for search and autocomplete that vocabulary has no bare form, so an index that advertised only
 * {@code SearchQueryService} made {@code dotnet package search} report "The source does not have a
 * Search service!" (RPS-1240, after RPS-1213 had already fixed the registration type).
 */
@DisplayName("NuGet wire protocol service index")
class NuGetServiceIndexProtocolIT extends AbstractIntegrationTest {

  private Repo repo;

  @BeforeEach
  void seedNuGetRepo() {
    this.repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-index"));
  }

  private List<String> typesServedAt(final JsonNode index, final String pathSuffix) {
    final var types = new ArrayList<String>();

    for (final var resource : index.get("resources")) {
      if (resource.get("@id").asString().endsWith(pathSuffix)) {
        types.add(resource.get("@type").asString());
      }
    }

    return types;
  }

  @Test
  @DisplayName("advertises the versioned search and autocomplete types NuGet.Client resolves")
  void advertisesTypesTheClientResolves() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/{repo}/v3/index.json", this.repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken())
                    .with(protocolPort()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    final var index = new ObjectMapper().readTree(response);

    assertThat(index.get("version").asString()).isEqualTo("3.0.0");
    assertThat(typesServedAt(index, "/v3/search"))
        .containsExactlyInAnyOrder("SearchQueryService", "SearchQueryService/3.0.0-beta");
    assertThat(typesServedAt(index, "/v3/autocomplete"))
        .containsExactlyInAnyOrder(
            "SearchAutocompleteService", "SearchAutocompleteService/3.0.0-beta");
    assertThat(typesServedAt(index, "/v3/registration")).containsExactly("RegistrationsBaseUrl");
    assertThat(typesServedAt(index, "/v3/package"))
        .containsExactlyInAnyOrder("PackageBaseAddress/3.0.0", "PackagePublish/2.0.0");
  }
}
