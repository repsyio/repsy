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
package io.repsy.os.panel.connector.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIT;
import io.repsy.os.shared.user.entities.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-2660: connected applications are off by default, and then every route of the flow answers 404
 * {@code itemNotFound}, as a path nothing maps does, whoever calls it.
 */
@DisplayName("Connected applications, turned off (the default)")
class ConnectorDisabledIT extends AbstractIT {

  private int status(final MockHttpServletRequestBuilder request, final String login)
      throws Exception {
    return this.perform(request.header(AUTHORIZATION, login)).andReturn().getResponse().getStatus();
  }

  @Test
  @DisplayName("every route of the flow answers 404")
  void everyRouteIsNotFound() throws Exception {
    final var login = this.bearerTokenFor(this.createUser(uniqueUsername("off"), UserRole.USER));
    final var consent =
        post("/api/profile/connector-authorizations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"redirectUri\":\"https://x.test/cb\",\"clientName\":\"x\","
                    + "\"scopes\":[\"repo:read\"],\"codeChallenge\":\"c\","
                    + "\"codeChallengeMethod\":\"S256\"}");
    final var exchange =
        post("/api/connector/tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"c\",\"codeVerifier\":\"v\",\"redirectUri\":\"r\"}");

    assertThat(this.status(consent, login)).isEqualTo(404);
    assertThat(this.status(exchange, login)).isEqualTo(404);
    assertThat(this.status(get("/api/profile/connector-tokens"), login)).isEqualTo(404);
    assertThat(this.status(delete("/api/profile/connector-tokens/current"), login)).isEqualTo(404);
    assertThat(this.status(delete("/api/profile/connector-tokens/{id}", UUID.randomUUID()), login))
        .isEqualTo(404);
  }
}
