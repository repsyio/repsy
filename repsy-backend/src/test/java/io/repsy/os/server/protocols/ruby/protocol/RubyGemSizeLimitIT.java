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

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPS-1055: a {@code gem push} body is read through a size limit, {@code repsy.ruby.max-gem-size},
 * because no multipart limit applies to a raw request body. The limit is set low here so the gem
 * that exceeds it stays small.
 */
@TestPropertySource(properties = "repsy.ruby.max-gem-size=4KB")
@DisplayName("Ruby gem push size limit (RPS-1055)")
class RubyGemSizeLimitIT extends AbstractIntegrationTest {

  private ResultActions push(final String repoName, final byte[] body) throws Exception {
    return this.mockMvc.perform(
        post(PUBLISH_PATH, repoName)
            .with(protocolPort())
            .header(AUTHORIZATION, this.adminProtocolBearerToken())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body));
  }

  @Test
  @DisplayName("a gem under the limit is registered and downloads byte for byte")
  void acceptsGemUnderLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gem = gem("small-gem", "1.0.0");
    assertThat(gem.length).isLessThan(4 * 1024);

    this.push(repo.getName(), gem).andExpect(status().isOk());

    final var downloaded =
        this.mockMvc
            .perform(
                get("/{repo}/gems/small-gem-1.0.0.gem", repo.getName())
                    .with(protocolPort())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(downloaded).isEqualTo(gem);
  }

  @Test
  @DisplayName("a body over the limit is answered with 413 payloadTooLarge and stores nothing")
  void rejectsBodyOverLimit() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));

    this.push(repo.getName(), new byte[4 * 1024 + 1])
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.msgId").value("payloadTooLarge"));

    this.mockMvc
        .perform(
            get("/{repo}/names", repo.getName())
                .with(protocolPort())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()))
        .andExpect(status().isOk());
  }
}
