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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * RPS-1102: without {@code app.allowed-origins} configured (the default context every other IT
 * class shares), a cross-origin preflight is allowed for any origin, exactly like before this
 * property existed. See {@link CorsConfigurationRestrictedIT} for the configured case.
 */
@DisplayName("CORS, app.allowed-origins unset (RPS-1102)")
class CorsConfigurationIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("allows a preflight from any origin")
  void allowsPreflightFromAnyOrigin() throws Exception {

    final var origin = "https://anything.example.com";

    this.perform(
            options("/api/auth/login")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
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
  @DisplayName("still reflects the origin on a plain API request, not only on a preflight")
  void reflectsOriginOnPlainApiRequest() throws Exception {

    final var origin = "https://anything.example.com";

    this.perform(get("/api/profile").header(HttpHeaders.ORIGIN, origin))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
  }
}
