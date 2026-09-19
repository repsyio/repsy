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
package io.repsy.os.server.protocols.ruby.protocol;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PROTOCOL_PORT;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.protocolPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Full-stack coverage of the RubyGems wire protocol served by the protocol router on the main port
 * (9090): {@code gem push}, download, index and {@code gem yank}.
 *
 * <p>The protocol path parser resolves the repo from {@code request.getServletPath()}. MockMvc
 * leaves that empty unless the test sets it, so a request that skips {@code protocolPort()} never
 * matches any handler and ends in {@code 404 unknownPath} even though the route is registered.
 */
@DisplayName("Ruby protocol /{repo}/api/v1/gems")
class RubyGemProtocolIT extends AbstractIntegrationTest {

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private ResultActions push(final String repoName, final byte[] gem, final String token)
      throws Exception {
    return this.protocol(
        post(PUBLISH_PATH, repoName)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(gem));
  }

  @Test
  @DisplayName("POST /{repo}/api/v1/gems registers the pushed gem")
  void publishesGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));

    final var body =
        this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo("Successfully registered gem: pushed-gem (1.2.3)");
  }

  @Test
  @DisplayName("a pushed gem can be downloaded byte for byte")
  void downloadsPushedGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gem = gem("pushed-gem", "1.2.3");
    this.push(repo.getName(), gem, this.adminProtocolBearerToken()).andExpect(status().isOk());

    final var downloaded =
        this.protocol(
                get("/{repo}/gems/pushed-gem-1.2.3.gem", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(downloaded).isEqualTo(gem);
  }

  @Test
  @DisplayName("a pushed gem is listed in the compact index")
  void listsPushedGemInCompactIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var names =
        this.protocol(
                get("/{repo}/names", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(names).contains("pushed-gem");
  }

  @Test
  @DisplayName("DELETE /{repo}/api/v1/gems/yank yanks a pushed gem")
  void yanksPushedGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var token = this.adminProtocolBearerToken();
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), token).andExpect(status().isOk());

    final var body =
        this.protocol(
                delete("/{repo}/api/v1/gems/yank", repo.getName())
                    .header(AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("gem_name", "pushed-gem")
                    .param("version", "1.2.3"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo("Successfully yanked gem: pushed-gem (1.2.3)");
  }

  @Test
  @DisplayName("a request without a servlet path matches no handler: 404 unknownPath")
  void requestWithoutServletPathIsUnknown() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));

    // Same request as push(), minus the servlet path that protocolPort() fills in.
    this.mockMvc
        .perform(
            post(PUBLISH_PATH, repo.getName())
                .with(
                    request -> {
                      request.setLocalPort(PROTOCOL_PORT);
                      return request;
                    })
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem("pushed-gem", "1.2.3")))
        .andExpect(status().isNotFound());
  }
}
