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
package io.repsy.protocols.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@DisplayName("PublicUrls")
class PublicUrlsTest {

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static MockHttpServletRequest request(
      final String scheme, final String host, final int port) {
    final var request = new MockHttpServletRequest();
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(port);
    return request;
  }

  /** The strategy docker and helm used before: Spring's builder on the current request. */
  private static String springContextRoot(final MockHttpServletRequest request) {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
  }

  @ParameterizedTest
  @CsvSource({
    "http, repo.example.com, 80",
    "https, repo.example.com, 443",
    "http, repo.example.com, 8080",
    "https, repo.example.com, 8443",
    "http, localhost, 9090"
  })
  @DisplayName("is the same address Spring's builder gave when no proxy header names a port")
  void sameAsSpringBuilder(final String scheme, final String host, final int port) {
    final var request = request(scheme, host, port);

    assertThat(PublicUrls.contextRoot(request)).isEqualTo(springContextRoot(request));
    assertThat(PublicUrls.currentContextRoot()).isEqualTo(springContextRoot(request));
  }

  @Test
  @DisplayName("keeps the context path")
  void contextPath() {
    final var request = request("https", "repo.example.com", 443);
    request.setContextPath("/repsy");

    assertThat(PublicUrls.origin(request)).isEqualTo("https://repo.example.com");
    assertThat(PublicUrls.contextRoot(request)).isEqualTo(springContextRoot(request));
    assertThat(PublicUrls.contextRoot(request)).isEqualTo("https://repo.example.com/repsy");
  }

  @Test
  @DisplayName(
      "takes the port embedded in X-Forwarded-Host, which Spring's builder loses (RPS-1515)")
  void embeddedForwardedPort() {
    final var request = request("https", "repo.example.com", 443);
    request.addHeader("X-Forwarded-Host", "repo.example.com:8443");

    assertThat(PublicUrls.origin(request)).isEqualTo("https://repo.example.com:8443");
    assertThat(springContextRoot(request)).isEqualTo("https://repo.example.com");
  }

  @Test
  @DisplayName("X-Forwarded-Port wins over the embedded one, and a default port is left out")
  void forwardedPort() {
    final var request = request("https", "repo.example.com", 8080);
    request.addHeader("X-Forwarded-Host", "repo.example.com:8443");
    request.addHeader("X-Forwarded-Port", "443");

    assertThat(PublicUrls.origin(request)).isEqualTo("https://repo.example.com");
  }

  @Test
  @DisplayName("fails outside a servlet request, as Spring's builder did")
  void noCurrentRequest() {
    assertThatThrownBy(PublicUrls::currentContextRoot).isInstanceOf(IllegalStateException.class);
  }
}
