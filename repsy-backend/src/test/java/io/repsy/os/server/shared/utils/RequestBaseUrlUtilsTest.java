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
package io.repsy.os.server.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("RequestBaseUrlUtils")
class RequestBaseUrlUtilsTest {

  @Test
  @DisplayName("plain, non-proxied request: uses the request's own scheme, host and port")
  void plainRequestUsesOwnAddress() {
    final var request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("http");
    request.setServerName("localhost");
    request.setServerPort(9090);

    assertThat(RequestBaseUrlUtils.resolveBaseUrl(request)).isEqualTo("http://localhost:9090");
  }

  @Test
  @DisplayName("plain request on the default port: the port is omitted")
  void plainRequestOmitsDefaultPort() {
    final var request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("https");
    request.setServerName("localhost");
    request.setServerPort(443);

    assertThat(RequestBaseUrlUtils.resolveBaseUrl(request)).isEqualTo("https://localhost");
  }

  /**
   * RPS-1515: Tomcat's RemoteIpValve (server.forward-headers-strategy: native) parses the port out
   * of X-Forwarded-Host and discards it, so request.getServerPort() falls back to the scheme's
   * default (443 here) when the proxy sends no separate X-Forwarded-Port. The raw header is still
   * on the request, so the port is recovered from it.
   */
  @Test
  @DisplayName("X-Forwarded-Host with an embedded port and no X-Forwarded-Port: keeps the port")
  void forwardedHostWithEmbeddedPortAndNoSeparatePortHeader() {
    final var request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("https");
    request.setServerName("pub.e2e.test");
    request.setServerPort(443);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "pub.e2e.test:8443");

    assertThat(RequestBaseUrlUtils.resolveBaseUrl(request)).isEqualTo("https://pub.e2e.test:8443");
  }

  @Test
  @DisplayName("X-Forwarded-Host without a port, and a separate X-Forwarded-Port: keeps the port")
  void forwardedHostWithSeparatePortHeader() {
    final var request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("https");
    request.setServerName("pub.e2e.test");
    request.setServerPort(8443);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "pub.e2e.test");
    request.addHeader("X-Forwarded-Port", "8443");

    assertThat(RequestBaseUrlUtils.resolveBaseUrl(request)).isEqualTo("https://pub.e2e.test:8443");
  }

  @Test
  @DisplayName("X-Forwarded-Host alone, without a port anywhere: no port in the URL")
  void forwardedHostWithoutAnyPort() {
    final var request = new MockHttpServletRequest("GET", "/v2/");
    request.setScheme("https");
    request.setServerName("pub.e2e.test");
    request.setServerPort(443);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "pub.e2e.test");

    assertThat(RequestBaseUrlUtils.resolveBaseUrl(request)).isEqualTo("https://pub.e2e.test");
  }
}
