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

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_LAYER_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-2164: {@code repsy.helm.oci.strict-wire=false} turns the Docker-like manifest checks and the
 * {@code Accept} negotiation of the Helm OCI routes off again, so an operator can fall back to the
 * earlier lenient wire without a new release. {@code OciWireCharacterizationIT} pins the strict
 * default.
 */
@TestPropertySource(properties = "repsy.helm.oci.strict-wire=false")
@DisplayName("Helm OCI lenient wire (RPS-2164)")
class HelmOciLenientWireIT extends AbstractIT {

  private static final String CHART = "payments";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Test
  @DisplayName("a bare manifest of another Content-Type is stored and any Accept header is served")
  void bareManifestIsStoredAndAcceptIsNotNegotiated() throws Exception {
    final var repo = this.seedRepo(RepoType.HELM, uniqueRepoName("helm"));
    final var token = this.adminProtocolBearerToken();
    final var layer = chart(CHART, "1.0.0");
    final var layerDigest = digest("SHA-256", layer);

    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), CHART)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    final var location = start.getHeader("Location");
    final var uploaded =
        this.mockMvc
            .perform(
                put(
                        "/v2/{repo}/{name}/blobs/uploads/{id}",
                        repo.getName(),
                        CHART,
                        location.substring(location.lastIndexOf('/') + 1))
                    .param("digest", layerDigest)
                    .contentType("application/octet-stream")
                    .content(layer)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(uploaded.getStatus()).as("layer upload").isEqualTo(201);

    final var manifest =
        "{\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
            .formatted(OCI_LAYER_TYPE, layerDigest, layer.length);
    final var pushed =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/manifests/1.0.0", repo.getName(), CHART)
                    .contentType("application/json")
                    .content(manifest.getBytes(StandardCharsets.UTF_8))
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);

    final var pulled =
        this.mockMvc
            .perform(
                get("/v2/{repo}/{name}/manifests/1.0.0", repo.getName(), CHART)
                    .header("Accept", "text/plain")
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(pulled.getStatus()).isEqualTo(200);
  }
}
