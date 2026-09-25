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
package io.repsy.os.server.protocols.nuget.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** The public URL {@code repsy.nuget.public-url} ({@code REPO_BASE_URL}) of RPS-1432. */
@DisplayName("NuGetPublicUrlResolver")
class NuGetPublicUrlResolverTest {

  private static MockHttpServletRequest request() {
    final var request = new MockHttpServletRequest("GET", "/my-repo/v3/index.json");
    request.setScheme("http");
    request.setServerName("repo.internal");
    request.setServerPort(9090);
    return request;
  }

  @Test
  @DisplayName("names the public URL followed by the repo when one is configured")
  void publicUrl() {
    assertThat(new NuGetPublicUrlResolver("https://repo.example.com").baseUrl(request(), "my-repo"))
        .isEqualTo("https://repo.example.com/my-repo");
  }

  @Test
  @DisplayName("keeps a path prefix")
  void pathPrefix() {
    assertThat(
            new NuGetPublicUrlResolver("https://example.com/repsy").baseUrl(request(), "my-repo"))
        .isEqualTo("https://example.com/repsy/my-repo");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://repo.example.com/",
        "https://repo.example.com///",
        "  https://repo.example.com/  "
      })
  @DisplayName("strips the whitespace and the trailing slashes, as the npm registry does")
  void stripsSlashesAndWhitespace(final String configured) {
    assertThat(new NuGetPublicUrlResolver(configured).baseUrl(request(), "my-repo"))
        .isEqualTo("https://repo.example.com/my-repo");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "/", " // "})
  @DisplayName("falls back to the scheme, host and port of the request when none is configured")
  void requestFallback(final String configured) {
    assertThat(new NuGetPublicUrlResolver(configured).baseUrl(request(), "my-repo"))
        .isEqualTo("http://repo.internal:9090/my-repo");
  }
}
