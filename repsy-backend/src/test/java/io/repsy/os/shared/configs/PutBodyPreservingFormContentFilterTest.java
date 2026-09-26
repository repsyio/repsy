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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1443: {@code curl -X PUT --data-binary @file} declares {@code
 * application/x-www-form-urlencoded}, and Spring's form content filter used to read that body into
 * request parameters, so the upload handler saw an empty stream. A {@code PUT} or {@code PATCH}
 * keeps its body; a {@code DELETE} still gets its form parameters, as {@code gem yank} needs.
 */
@DisplayName("PutBodyPreservingFormContentFilter")
class PutBodyPreservingFormContentFilterTest {

  private static final String FORM = "application/x-www-form-urlencoded";
  private static final byte[] BODY = "a=b&c=d".getBytes(StandardCharsets.UTF_8);

  private final PutBodyPreservingFormContentFilter filter =
      new PutBodyPreservingFormContentFilter();

  private static MockHttpServletRequest request(final String method, final String contentType) {
    final var request = new MockHttpServletRequest(method, "/repo/com/acme/lib/1.0/lib-1.0.jar");
    request.setContent(BODY);

    if (contentType != null) {
      request.setContentType(contentType);
    }

    return request;
  }

  private MockFilterChain run(final MockHttpServletRequest request) throws Exception {
    final var chain = new MockFilterChain();
    this.filter.doFilter(request, new MockHttpServletResponse(), chain);

    return chain;
  }

  @ParameterizedTest
  @ValueSource(strings = {"PUT", "PATCH"})
  @DisplayName("leaves the body of a form-encoded PUT or PATCH unread and its parameters empty")
  void keepsTheBodyOfAPutOrPatch(final String method) throws Exception {
    final var chain = run(request(method, FORM));

    final var passed = (MockHttpServletRequest) chain.getRequest();

    assertThat(passed.getInputStream().readAllBytes()).isEqualTo(BODY);
    assertThat(passed.getParameterMap()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PUT", "PATCH"})
  @DisplayName("passes a PUT or PATCH of any other content type on untouched")
  void passesOtherContentTypesOn(final String method) throws Exception {
    for (final var type : new String[] {"application/octet-stream", null}) {
      final var chain = run(request(method, type));

      assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(BODY);
    }
  }

  @Test
  @DisplayName("still reads the form-encoded body of a DELETE into parameters (gem yank)")
  void stillParsesTheFormBodyOfADelete() throws Exception {
    final var chain = run(request("DELETE", FORM));

    assertThat(chain.getRequest().getParameter("a")).isEqualTo("b");
    assertThat(chain.getRequest().getParameter("c")).isEqualTo("d");
  }
}
