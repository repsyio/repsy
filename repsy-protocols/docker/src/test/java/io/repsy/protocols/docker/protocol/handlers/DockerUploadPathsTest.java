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
package io.repsy.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("DockerUploadPaths.sessionLocation")
class DockerUploadPathsTest {

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static ProtocolContext context() {
    final var properties = mock(BaseUrlParserProperties.class);
    when(properties.getRepoName()).thenReturn("my-repo");
    final var context = new ProtocolContext();
    context.addProperty("urlProperties", properties);
    return context;
  }

  private static MockHttpServletRequest current(
      final String scheme, final String host, final int port) {
    final var request = new MockHttpServletRequest("POST", "/v2/my-repo/img/blobs/uploads/");
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(port);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    return request;
  }

  @Test
  @DisplayName("is /v2/<repo>/<image>/blobs/uploads/<id> on the request's host and port")
  void location() {
    current("http", "registry.internal", 8080);

    assertThat(DockerUploadPaths.sessionLocation(context(), "ns/img", "abc"))
        .isEqualTo("http://registry.internal:8080/v2/my-repo/ns/img/blobs/uploads/abc");
  }

  @Test
  @DisplayName("leaves out the default port of the scheme")
  void defaultPort() {
    current("https", "registry.internal", 443);

    assertThat(DockerUploadPaths.sessionLocation(context(), "img", "abc"))
        .isEqualTo("https://registry.internal/v2/my-repo/img/blobs/uploads/abc");
  }

  @Test
  @DisplayName(
      "names the port embedded in X-Forwarded-Host, which Spring's builder lost (RPS-1515)")
  void embeddedForwardedPort() {
    current("https", "registry.internal", 443)
        .addHeader("X-Forwarded-Host", "reg.example.com:8443");

    assertThat(DockerUploadPaths.sessionLocation(context(), "img", "abc"))
        .isEqualTo("https://registry.internal:8443/v2/my-repo/img/blobs/uploads/abc");
  }
}
