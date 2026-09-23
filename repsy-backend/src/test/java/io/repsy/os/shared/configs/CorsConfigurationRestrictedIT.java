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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * RPS-1102: once {@code app.allowed-origins} (env {@code APP_ALLOWED_ORIGINS}) is set, a
 * cross-origin preflight is allowed only for a configured origin and rejected for any other. See
 * {@link CorsConfigurationIT} for the unset (default) case, which today's deployments keep.
 */
@TestPropertySource(properties = "APP_ALLOWED_ORIGINS=https://allowed.example.com")
@DisplayName("CORS, app.allowed-origins configured (RPS-1102)")
class CorsConfigurationRestrictedIT extends AbstractIntegrationTest {

  private static final String ALLOWED_ORIGIN = "https://allowed.example.com";
  private static final String OTHER_ORIGIN = "https://not-allowed.example.com";

  @Test
  @DisplayName("allows a preflight from the configured origin")
  void allowsPreflightFromConfiguredOrigin() throws Exception {

    this.perform(
            options("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
  }

  @Test
  @DisplayName("rejects a preflight from a different, non-configured origin")
  void rejectsPreflightFromOtherOrigin() throws Exception {

    this.perform(
            options("/api/auth/login")
                .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }
}
