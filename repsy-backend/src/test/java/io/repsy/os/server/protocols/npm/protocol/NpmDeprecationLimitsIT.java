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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.npm.shared.utils.NpmPublishLimits;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1747: a deprecation message is stored whole and a dist-tag name goes into a length-limited
 * column, so both are bounded before anything is written.
 */
@DisplayName("npm deprecation message and dist-tag name limits")
class NpmDeprecationLimitsIT extends AbstractIntegrationTest {

  private static final String NAME = "left-pad";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo publishedRepo(final String token) throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1747"), false, null);
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), NAME)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), NAME, "1.0.0", Map.of())));
    assertThat(response.getStatus()).isEqualTo(200);

    return repo;
  }

  private MockHttpServletResponse deprecate(
      final Repo repo, final String token, final String message) throws Exception {
    final var read =
        this.protocol(
            get("/{repo}/{name}", repo.getName(), NAME)
                .queryParam("write", "true")
                .header(AUTHORIZATION, token));
    final Map<String, Object> packument =
        this.objectMapper.readValue(
            read.getContentAsString(StandardCharsets.UTF_8), new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    final var versions = (Map<String, Map<String, Object>>) packument.get("versions");
    versions.get("1.0.0").put("deprecated", message);

    return this.protocol(
        put("/{repo}/{name}", repo.getName(), NAME)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.objectMapper.writeValueAsBytes(packument)));
  }

  @Test
  @DisplayName("a deprecation message over the limit is a 400, one at the limit is accepted")
  void deprecationMessageIsBounded() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("depr"), UserRole.ADMIN));
    final var repo = this.publishedRepo(token);
    final var limit = NpmPublishLimits.MAX_DEPRECATION_MESSAGE_LENGTH;

    final var refused = this.deprecate(repo, token, "x".repeat(limit + 1));
    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat(refused.getContentAsString()).contains("deprecationMessageTooLong");

    assertThat(this.deprecate(repo, token, "x".repeat(limit)).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("a dist-tag name over the limit is a 400 before the tag row is written")
  void distTagNameIsBounded() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("depr"), UserRole.ADMIN));
    final var repo = this.publishedRepo(token);

    final var response =
        this.protocol(
            put(
                    "/{repo}/-/package/{name}/dist-tags/{tag}",
                    repo.getName(),
                    NAME,
                    "t".repeat(NpmPublishLimits.MAX_DIST_TAG_LENGTH + 1))
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"1.0.0\""));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("distTagNameTooLong");
  }
}
