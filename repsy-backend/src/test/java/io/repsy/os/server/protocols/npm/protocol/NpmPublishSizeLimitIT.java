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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1561: an {@code npm publish} body (the packument plus its base64 tarball) was read whole into
 * memory with no size limit at all, unlike the other protocols. Below Jackson's default
 * 100,000,000-character string-length ceiling it was accepted regardless of size; above it (a
 * tarball of about 75MB, base64 inflating it further) the parse failed with an unrelated 500
 * instead of 413. The limit ({@code repsy.npm.max-publish-size}) is set low here so the tarball
 * that exceeds it stays small.
 */
@TestPropertySource(properties = "repsy.npm.max-publish-size=4KB")
@DisplayName("npm publish size limit (RPS-1561)")
class NpmPublishSizeLimitIT extends AbstractIntegrationTest {

  private static final String PACKAGE_PATH = "/{repo}/{name}";

  @Autowired private ObjectMapper objectMapper;

  private ResultActions publish(final String repoName, final String token, final byte[] body)
      throws Exception {
    return this.mockMvc.perform(
        put(PACKAGE_PATH, repoName, "left-pad")
            .with(protocolPort())
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions getPackage(final String repoName, final String token) throws Exception {
    return this.mockMvc.perform(
        get(PACKAGE_PATH, repoName, "left-pad").with(protocolPort()).header(AUTHORIZATION, token));
  }

  @Test
  @DisplayName("a publish under the limit is accepted")
  void acceptsPublishUnderLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.adminProtocolBearerToken();
    final var publishBody =
        NpmPublishBodies.body(this.objectMapper, repo.getName(), "left-pad", "1.0.0", Map.of());
    assertThat(publishBody.length).isLessThan(4 * 1024);

    this.publish(repo.getName(), token, publishBody).andExpect(status().isOk());

    this.getPackage(repo.getName(), token).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a publish over the limit is answered with 413 payloadTooLarge and stores nothing")
  void rejectsPublishOverLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var token = this.adminProtocolBearerToken();
    final var oversizedTarball = new byte[8 * 1024];
    final var publishBody =
        NpmPublishBodies.body(
            this.objectMapper, repo.getName(), "left-pad", "1.0.0", Map.of(), oversizedTarball);
    assertThat(publishBody.length).isGreaterThan(4 * 1024);

    this.publish(repo.getName(), token, publishBody)
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.msgId").value("payloadTooLarge"));

    this.getPackage(repo.getName(), token).andExpect(status().isNotFound());
  }
}
