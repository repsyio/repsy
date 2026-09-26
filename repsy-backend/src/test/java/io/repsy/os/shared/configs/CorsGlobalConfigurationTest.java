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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1590: with {@code app.allowed-origins} unset the panel API is same-origin only (no CORS
 * header, and no {@code 403 Invalid CORS request} either); only the configured origins are opened,
 * and only on the API port.
 */
@DisplayName("CorsGlobalConfiguration")
class CorsGlobalConfigurationTest {

  private static final int API_PORT = 8080;
  private static final int PROTOCOL_PORT = 9090;
  private static final String ALLOWED_ORIGIN = "https://panel.example.com";
  private static final String OTHER_ORIGIN = "https://evil.example.com";

  private final MultiPortProperties multiPortProperties = new MultiPortProperties();

  @BeforeEach
  void setUp() {
    this.multiPortProperties.setPorts(Map.of("api", API_PORT));
    this.multiPortProperties.setPortAliases(Map.of(8443, "api"));
  }

  private CorsGlobalConfiguration filter(final String allowedOrigins) {
    return new CorsGlobalConfiguration(
        new AppCorsProperties(allowedOrigins), new ApiPortMatcher(this.multiPortProperties));
  }

  private static MockHttpServletRequest request(
      final int port, final String method, final String origin, final boolean preflight) {

    final var request = new MockHttpServletRequest(method, "/api/auth/login");
    request.setLocalPort(port);
    request.addHeader(HttpHeaders.ORIGIN, origin);

    if (preflight) {
      request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
    }

    return request;
  }

  private static void assertNoCorsHeaders(final MockHttpServletResponse response) {
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
  }

  @Test
  @DisplayName("unset: a cross-origin request passes through with no CORS header and no 403")
  void unsetSendsNoCorsHeaderOnPlainRequest() throws Exception {

    for (final var unset : new String[] {null, "", "  ", " , "}) {
      final var response = new MockHttpServletResponse();
      final var chain = new MockFilterChain();

      this.filter(unset).doFilter(request(API_PORT, "POST", OTHER_ORIGIN, false), response, chain);

      assertThat(chain.getRequest()).as("chain reached for %s", unset).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
      assertNoCorsHeaders(response);
    }
  }

  @Test
  @DisplayName("unset: a preflight is not answered with CORS headers, it reaches the chain")
  void unsetDoesNotAnswerPreflight() throws Exception {

    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    this.filter(null).doFilter(request(API_PORT, "OPTIONS", OTHER_ORIGIN, true), response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertNoCorsHeaders(response);
  }

  @Test
  @DisplayName("configured: the listed origin is reflected with credentials, on every alias port")
  void configuredOriginIsAllowed() throws Exception {

    for (final var port : new int[] {API_PORT, 8443}) {
      final var response = new MockHttpServletResponse();

      this.filter(ALLOWED_ORIGIN + ", https://other.example.com")
          .doFilter(request(port, "POST", ALLOWED_ORIGIN, false), response, new MockFilterChain());

      assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
          .isEqualTo(ALLOWED_ORIGIN);
      assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
          .isEqualTo("true");
    }
  }

  @Test
  @DisplayName(
      "configured: an origin that is not listed is rejected with 403 and never reaches the chain")
  void configuredRejectsOtherOrigin() throws Exception {

    final var response = new MockHttpServletResponse();
    final var chain = Mockito.mock(jakarta.servlet.FilterChain.class);
    final var request = request(API_PORT, "OPTIONS", OTHER_ORIGIN, true);

    this.filter(ALLOWED_ORIGIN).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertNoCorsHeaders(response);
    verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
  }

  @Test
  @DisplayName("configured: the protocol port still gets no CORS header")
  void configuredLeavesProtocolPortAlone() throws Exception {

    final var response = new MockHttpServletResponse();
    final var chain = new MockFilterChain();

    this.filter(ALLOWED_ORIGIN)
        .doFilter(request(PROTOCOL_PORT, "GET", ALLOWED_ORIGIN, false), response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertNoCorsHeaders(response);
  }
}
