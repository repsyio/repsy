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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import io.repsy.os.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * RPS-1514: with {@code app.hsts-max-age} (env {@code APP_HSTS_MAX_AGE}) set, {@code
 * Strict-Transport-Security} is sent on a secure request of either port and never on a plain one.
 * See {@link SecurityHeadersIT} for the default (off) case.
 */
@TestPropertySource(properties = "APP_HSTS_MAX_AGE=31536000")
@DisplayName("Strict-Transport-Security, app.hsts-max-age set (RPS-1514)")
class HstsHeaderIT extends AbstractIntegrationTest {

  private static final String HSTS = "Strict-Transport-Security";

  @Test
  @DisplayName("is sent on a secure API request")
  void sentOnSecureApiRequest() throws Exception {

    this.perform(get("/api/profile").secure(true))
        .andExpect(header().string(HSTS, "max-age=31536000"));
  }

  @Test
  @DisplayName("is sent on a secure protocol request")
  void sentOnSecureProtocolRequest() throws Exception {

    this.mockMvc
        .perform(get("/v2/").with(protocolPort()).secure(true))
        .andExpect(header().string(HSTS, "max-age=31536000"));
  }

  @Test
  @DisplayName("is not sent on a plain request")
  void notSentOnPlainRequest() throws Exception {

    this.perform(get("/api/profile")).andExpect(header().doesNotExist(HSTS));
    this.mockMvc.perform(get("/v2/").with(protocolPort())).andExpect(header().doesNotExist(HSTS));
  }
}
