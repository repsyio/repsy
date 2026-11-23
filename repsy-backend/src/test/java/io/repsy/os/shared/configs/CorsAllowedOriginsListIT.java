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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.spa.controllers.AbstractStaticFrontendIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * RPS-2739: {@code APP_ALLOWED_ORIGINS} set to a comma separated list of two origins, as the e2e
 * {@code --cors} overlay sets it. The existing {@code CorsConfigurationRestrictedIT} pins a single
 * origin; this class pins what only a list can show: the second origin works like the first, the
 * preflight echoes the requested method and headers, the CSP {@code connect-src} names every listed
 * origin on the SPA ({@code /index.html} too), and an error or a refusal still carries the
 * hardening headers.
 *
 * <p>It extends {@link AbstractStaticFrontendIT} so the SPA answers are real; the added property
 * makes it a Spring context of its own.
 */
@TestPropertySource(
    properties = "APP_ALLOWED_ORIGINS=http://allowed.e2e.test,http://second-allowed.e2e.test")
@DisplayName("CORS and CSP, app.allowed-origins is a list of two origins (RPS-2739)")
class CorsAllowedOriginsListIT extends AbstractStaticFrontendIT {

  private static final String FIRST = "http://allowed.e2e.test";
  private static final String SECOND = "http://second-allowed.e2e.test";
  private static final String FOREIGN = "http://evil.e2e.test";

  @Test
  @DisplayName("a preflight from each listed origin echoes that origin, the method and the headers")
  void preflightFromEachListedOrigin() throws Exception {

    for (final var origin : new String[] {FIRST, SECOND}) {
      this.perform(
              options("/api/repos")
                  .header(HttpHeaders.ORIGIN, origin)
                  .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                  .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization"));
    }
  }

  @Test
  @DisplayName("an error answer to a listed origin carries that origin, with credentials")
  void errorAnswerCarriesTheOrigin() throws Exception {

    for (final var origin : new String[] {FIRST, SECOND}) {
      this.perform(get("/api/profile").header(HttpHeaders.ORIGIN, origin))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }
  }

  @Test
  @DisplayName(
      "an origin that is not in the list is refused with 403, for a preflight and a request")
  void unlistedOriginIsRefused() throws Exception {

    this.perform(
            options("/api/repos")
                .header(HttpHeaders.ORIGIN, FOREIGN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

    this.perform(get("/api/profile").header(HttpHeaders.ORIGIN, FOREIGN))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        // the refusal is still a response of the panel port: the hardening headers are on it
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }

  @Test
  @DisplayName("a request without an Origin gets no CORS header")
  void noOriginNoCorsHeader() throws Exception {

    this.perform(get("/api/profile"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  @DisplayName("the protocol port sends no CORS header, even for the second listed origin")
  void protocolPortSendsNothing() throws Exception {

    for (final var path : new String[] {"/v2/", "/"}) {
      this.mockMvc
          .perform(get(path).with(protocolPort()).header(HttpHeaders.ORIGIN, SECOND))
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
  }

  @Test
  @DisplayName("connect-src names every listed origin, on / and on /index.html")
  void connectSrcNamesTheListedOrigins() throws Exception {

    for (final var path : new String[] {"/", "/index.html"}) {
      this.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(
              header()
                  .string(
                      "Content-Security-Policy",
                      containsString("connect-src 'self' " + FIRST + " " + SECOND)))
          .andExpect(
              header().string("Content-Security-Policy", containsString("object-src 'none'")))
          .andExpect(header().string("Content-Security-Policy", containsString("base-uri 'self'")));
    }
  }
}
