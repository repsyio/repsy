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
package io.repsy.protocols.nuget.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("NuGetUrlBuilder.buildBaseUrl")
class NuGetUrlBuilderTest {

  private static MockHttpServletRequest request(
      final String scheme, final String host, final int port) {
    final var request = new MockHttpServletRequest("GET", "/my-repo/v3/index.json");
    request.setScheme(scheme);
    request.setServerName(host);
    request.setServerPort(port);
    return request;
  }

  @Test
  @DisplayName("names the scheme, host and non-default port of the request")
  void nonDefaultPort() {
    assertThat(NuGetUrlBuilder.buildBaseUrl(request("http", "repo.internal", 9090), "my-repo"))
        .isEqualTo("http://repo.internal:9090/my-repo");
  }

  @Test
  @DisplayName("leaves out the default port of the scheme")
  void defaultPorts() {
    assertThat(NuGetUrlBuilder.buildBaseUrl(request("http", "repo.internal", 80), "my-repo"))
        .isEqualTo("http://repo.internal/my-repo");
    assertThat(NuGetUrlBuilder.buildBaseUrl(request("https", "repo.internal", 443), "my-repo"))
        .isEqualTo("https://repo.internal/my-repo");
  }

  @Test
  @DisplayName("keeps port 443 on http and 80 on https, which are not defaults there")
  void otherSchemesDefaultPort() {
    assertThat(NuGetUrlBuilder.buildBaseUrl(request("http", "repo.internal", 443), "my-repo"))
        .isEqualTo("http://repo.internal:443/my-repo");
    assertThat(NuGetUrlBuilder.buildBaseUrl(request("https", "repo.internal", 80), "my-repo"))
        .isEqualTo("https://repo.internal:80/my-repo");
  }
}
