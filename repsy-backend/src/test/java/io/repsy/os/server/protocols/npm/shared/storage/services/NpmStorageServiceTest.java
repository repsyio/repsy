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
package io.repsy.os.server.protocols.npm.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.repsy.libs.storage.core.services.StorageStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RPS-1300: the address a rebuilt {@code dist.tarball} points at is the registry's, whichever port
 * the request that triggered the rebuild came in on. RPS-1333: the same address names every version
 * served or published, and a configured public URL takes the request's place.
 */
@DisplayName("NpmStorageService registry address (RPS-1300)")
class NpmStorageServiceTest {

  private static final int REPO_PORT = 9090;

  private static MockHttpServletRequest request(
      final String scheme, final String host, final int serverPort, final int localPort) {
    final var request = new MockHttpServletRequest();
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(serverPort);
    request.setLocalPort(localPort);

    return request;
  }

  private static NpmStorageService serviceWithPublicUrl(final String publicUrl) {
    return new NpmStorageService(mock(StorageStrategy.class), REPO_PORT, publicUrl);
  }

  @Test
  @DisplayName("the configured public URL wins, without its trailing slash, request or none")
  void publicUrlWins() {
    final var service = serviceWithPublicUrl(" https://repo.example.test/repsy/ ");

    assertThat(service.registryBaseUrl()).isEqualTo("https://repo.example.test/repsy");

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request("http", "10.0.0.5", 9090, 9090)));
    try {
      assertThat(service.registryBaseUrl()).isEqualTo("https://repo.example.test/repsy");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("without a public URL the request decides, and outside a request nothing does")
  void requestDecidesWithoutPublicUrl() {
    final var service = serviceWithPublicUrl("");

    assertThat(service.registryBaseUrl()).isNull();

    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request("https", "repo.test", 443, 8080)));
    try {
      assertThat(service.registryBaseUrl()).isEqualTo("https://repo.test");
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("a request to the registry port is the registry's address")
  void registryRequest() {
    assertThat(
            NpmStorageService.registryBaseUrl(request("http", "repo.test", 9090, 9090), REPO_PORT))
        .isEqualTo("http://repo.test:9090");
  }

  @Test
  @DisplayName("a request to the panel port gets the registry port instead")
  void panelRequest() {
    assertThat(
            NpmStorageService.registryBaseUrl(request("http", "repo.test", 8080, 8080), REPO_PORT))
        .isEqualTo("http://repo.test:9090");
  }

  @Test
  @DisplayName("behind a proxy the address the client used is all there is to go by")
  void proxiedRequest() {
    assertThat(
            NpmStorageService.registryBaseUrl(request("https", "repo.test", 443, 8080), REPO_PORT))
        .isEqualTo("https://repo.test");
  }
}
