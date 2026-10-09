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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A wrong verb on a panel route is answered 405 {@code methodNotSupported} with an {@code Allow}
 * header, not 404 (RPS-2094). The port-based handler mapping used to return no handler when the
 * path matched a mapping but the verb did not, so the request fell through to the static-resource
 * handler and became {@code itemNotFound}. The protocol port is not affected: its router takes
 * every verb, and a path nothing maps is still 404.
 */
@DisplayName("Panel API wrong verb answers 405")
class PanelMethodNotAllowedIT extends AbstractIntegrationTest {

  private static final String USAGE_PATH = "/api/usage";

  @Test
  @DisplayName("answers 405 with Allow listing GET for POST /api/usage, with a token")
  void wrongVerbWithToken() throws Exception {
    final var result =
        this.perform(post(USAGE_PATH).header(AUTHORIZATION, this.adminBearerToken()));

    expectMethodNotAllowed(result);

    final var allow = result.andReturn().getResponse().getHeader(HttpHeaders.ALLOW);
    assertThat(allow).contains("GET").doesNotContain("POST").doesNotContain("DELETE");
  }

  @Test
  @DisplayName("answers 405 for a wrong verb without any credentials, as no handler is reached")
  void wrongVerbWithoutToken() throws Exception {
    expectMethodNotAllowed(this.perform(delete(USAGE_PATH)));
  }

  @Test
  @DisplayName("answers 405 for a wrong verb on a path with a variable segment")
  void wrongVerbOnTemplatePath() throws Exception {
    expectMethodNotAllowed(this.perform(post("/api/repos/some-repo/settings")));
  }

  @Test
  @DisplayName("answers 404 itemNotFound for a path nothing maps")
  void unknownPathStaysNotFound() throws Exception {
    expectError(
        this.perform(get("/api/no-such-route")),
        HttpStatus.NOT_FOUND,
        "itemNotFound",
        null,
        "The requested item is not found.");
  }

  @Test
  @DisplayName("answers 406 for an Accept header that cannot be parsed")
  void unparsableAcceptIsNotAcceptable() throws Exception {
    this.perform(
            get(USAGE_PATH)
                .header(AUTHORIZATION, this.adminBearerToken())
                .header(HttpHeaders.ACCEPT, "not a media type;;"))
        .andExpect(status().isNotAcceptable());
  }

  @Test
  @DisplayName("keeps answering a CORS preflight on a mapped path, not 405")
  void preflightIsNotMethodNotAllowed() throws Exception {
    final var result =
        this.perform(
                options(USAGE_PATH)
                    .header(HttpHeaders.ORIGIN, "https://panel.example.com")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isNotEqualTo(405);
  }

  @Test
  @DisplayName("keeps answering 404 for an unknown path on the protocol port, whatever the verb")
  void protocolPortIsUnaffected() throws Exception {
    for (final var request :
        java.util.List.of(
            put("/no-such-protocol-path"),
            post("/no-such-protocol-path"),
            delete("/no-such-protocol-path"))) {
      this.mockMvc.perform(request.with(protocolPort())).andExpect(status().isNotFound());
    }
  }
}
