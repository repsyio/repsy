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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RPS-1131: the filter only sends the header on the API port, and only for requests that are not
 * the JSON API ({@code /api/**}); it honours {@code enabled}, {@code reportOnly} and the {@code
 * policy} override.
 */
@DisplayName("SecurityHeadersFilter")
class SecurityHeadersFilterTest {

  private static final int API_PORT = 8080;
  private static final int OTHER_PORT = 9090;

  private final MultiPortProperties multiPortProperties = new MultiPortProperties();
  private final FilterChain filterChain = Mockito.mock(FilterChain.class);

  @BeforeEach
  void setUp() {
    this.multiPortProperties.setPorts(Map.of("api", API_PORT));
    this.multiPortProperties.setPortAliases(Map.of(8443, "api"));
  }

  @Test
  @DisplayName("sends Content-Security-Policy for a SPA path on the API port")
  void sendsHeaderForSpaPathOnApiPort() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, false, null));

    final var request = request(API_PORT, "/repos/some-repo/overview");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response)
        .setHeader(Mockito.eq("Content-Security-Policy"), Mockito.contains("default-src 'self'"));
    verify(this.filterChain).doFilter(request, response);
  }

  @Test
  @DisplayName("sends Content-Security-Policy-Report-Only when report-only is enabled")
  void sendsReportOnlyHeaderWhenConfigured() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, true, null));

    final var request = request(API_PORT, "/");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response).setHeader(Mockito.eq("Content-Security-Policy-Report-Only"), any());
    verify(response, never()).setHeader(Mockito.eq("Content-Security-Policy"), any());
  }

  @Test
  @DisplayName("does not send the header for a JSON API path")
  void doesNotSendHeaderForApiPath() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, false, null));

    final var request = request(API_PORT, "/api/profile");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response, never()).setHeader(any(), any());
  }

  @Test
  @DisplayName("does not send the header outside the API port")
  void doesNotSendHeaderOutsideApiPort() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, false, null));

    final var request = request(OTHER_PORT, "/");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response, never()).setHeader(any(), any());
  }

  @Test
  @DisplayName("does not send the header when disabled")
  void doesNotSendHeaderWhenDisabled() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(false, false, null));

    final var request = request(API_PORT, "/");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response, never()).setHeader(any(), any());
  }

  @Test
  @DisplayName("an overriding policy replaces the built-in one")
  void policyOverrideReplacesBuiltInPolicy() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, false, "default-src 'none'"));

    final var request = request(API_PORT, "/");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response).setHeader("Content-Security-Policy", "default-src 'none'");
  }

  @Test
  @DisplayName("the API port alias (SSL) is treated as the API port too")
  void aliasPortIsTreatedAsApiPort() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties(null),
            new ContentSecurityPolicyProperties(true, false, null));

    final var request = request(8443, "/");
    final var response = Mockito.mock(HttpServletResponse.class);

    filter.doFilterInternal(request, response, this.filterChain);

    verify(response).setHeader(Mockito.eq("Content-Security-Policy"), any());
  }

  @Test
  @DisplayName("connect-src includes the configured CORS origins")
  void connectSrcIncludesConfiguredOrigins() throws Exception {

    final var filter =
        new SecurityHeadersFilter(
            this.multiPortProperties,
            new AppCorsProperties("https://panel.example.com,https://staging.example.com"),
            new ContentSecurityPolicyProperties(true, false, null));

    final var request = request(API_PORT, "/");
    final var response = Mockito.mock(HttpServletResponse.class);
    final var policy = new String[1];
    Mockito.doAnswer(
            invocation -> {
              policy[0] = invocation.getArgument(1);
              return null;
            })
        .when(response)
        .setHeader(Mockito.eq("Content-Security-Policy"), any());

    filter.doFilterInternal(request, response, this.filterChain);

    assertThat(policy[0])
        .contains("connect-src 'self'")
        .contains("https://panel.example.com")
        .contains("https://staging.example.com");
  }

  private static HttpServletRequest request(final int localPort, final String uri) {
    final var request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getLocalPort()).thenReturn(localPort);
    Mockito.when(request.getRequestURI()).thenReturn(uri);
    return request;
  }
}
