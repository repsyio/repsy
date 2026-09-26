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
package io.repsy.os.shared.configs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * RPS-1590: without {@code app.allowed-origins} configured (the default context every other IT
 * class shares) the panel API is same-origin only: no CORS header for any origin, and a preflight
 * is not answered with CORS headers. Before RPS-1590 (and RPS-1102) it reflected any origin with
 * credentials. See {@link CorsConfigurationRestrictedIT} for the configured case.
 */
@DisplayName("CORS, app.allowed-origins unset (RPS-1590)")
class CorsConfigurationIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("does not answer a preflight from any origin with CORS headers")
  void doesNotAnswerPreflightWithCorsHeaders() throws Exception {

    final var origin = "https://anything.example.com";

    this.perform(
            options("/api/auth/login")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
  }

  // RPS-1514: the repository port sends no CORS header at all, whatever the origin.
  @Test
  @DisplayName("sends no CORS header on the protocol port, for a preflight or a plain request")
  void sendsNoCorsHeaderOnProtocolPort() throws Exception {

    final var origin = "https://anything.example.com";

    this.mockMvc
        .perform(
            options("/v2/")
                .with(protocolPort())
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));

    this.mockMvc
        .perform(get("/v2/").with(protocolPort()).header(HttpHeaders.ORIGIN, origin))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  @DisplayName("sends no CORS header on a plain cross-origin API request either")
  void sendsNoCorsHeaderOnPlainApiRequest() throws Exception {

    this.perform(get("/api/profile").header(HttpHeaders.ORIGIN, "https://anything.example.com"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }
}
