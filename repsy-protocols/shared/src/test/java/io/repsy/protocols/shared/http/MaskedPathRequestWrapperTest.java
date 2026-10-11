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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("MaskedPathRequestWrapper hides token-bearing path segments")
class MaskedPathRequestWrapperTest {

  private static final String SECRET = "s3cr3t-token-value";

  private static MockHttpServletRequest request(final String path) {
    final var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    request.setServerName("repo.example.com");

    return request;
  }

  @Test
  @DisplayName("masks the segment after a marker in the URI and the URL")
  void masksUriAndUrl() {
    final var wrapped = new MaskedPathRequestWrapper(request("/a/download-token/" + SECRET + "/b"));

    assertThat(wrapped.getRequestURI()).isEqualTo("/a/download-token/***/b");
    assertThat(wrapped.getRequestURL().toString())
        .doesNotContain(SECRET)
        .startsWith("http://repo.example.com");
  }

  @Test
  @DisplayName("leaves a path without a marker untouched")
  void plainPath() {
    final var wrapped = MaskedPathRequestWrapper.of(request("/maven/releases/a/b.jar"));

    assertThat(wrapped.getRequestURI()).isEqualTo("/maven/releases/a/b.jar");
  }

  @Test
  @DisplayName("does not wrap twice and escapes line breaks")
  void idempotentAndEscapes() {
    final var wrapped = MaskedPathRequestWrapper.of(request("/a/b\r\nERROR forged"));

    assertThat(MaskedPathRequestWrapper.of(wrapped)).isSameAs(wrapped);
    assertThat(wrapped.getRequestURI()).doesNotContain("\r").doesNotContain("\n");
  }
}
