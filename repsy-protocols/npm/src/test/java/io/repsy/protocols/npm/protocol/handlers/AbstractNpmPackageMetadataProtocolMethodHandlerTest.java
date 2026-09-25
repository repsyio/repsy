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
package io.repsy.protocols.npm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** RPS-1359: the packument carries the validators a client revalidates its cache with. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNpmPackageMetadataProtocolMethodHandler (RPS-1359)")
class AbstractNpmPackageMetadataProtocolMethodHandlerTest {

  private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";

  @Mock private NpmProtocolProvider provider;
  @Mock private NpmProtocolFacade facade;

  private static class TestHandler extends AbstractNpmPackageMetadataProtocolMethodHandler {
    TestHandler(
        final PathParser base, final NpmProtocolFacade facade, final NpmProtocolProvider p) {
      super(base, facade, p);
    }
  }

  private org.springframework.http.ResponseEntity<Object> get(
      final String accept, final Map<String, Object> metadata) throws Exception {
    when(this.facade.getPackageMetadata(any(), eq(null), eq("demo"), eq(accept)))
        .thenReturn(metadata);
    final var request = NpmHandlerTestSupport.request("GET", "/npm/demo");
    request.addHeader(HttpHeaders.ACCEPT, accept);

    return new TestHandler(new FixedBaseParser("/demo", true), this.facade, this.provider)
        .handle(NpmHandlerTestSupport.context("/demo"), request, new MockHttpServletResponse());
  }

  private static Map<String, Object> full(final String modified) {
    return Map.of("name", "demo", "time", Map.of("modified", modified));
  }

  @Test
  @DisplayName("the full document has an ETag, a Last-Modified and Vary: Accept")
  void fullDocumentHasValidators() throws Exception {
    final var response = this.get("application/json", full("2026-03-04T05:06:07.089Z"));

    final var headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(headers.getETag()).matches("W/\"[0-9a-f]{64}\"");
    assertThat(headers.getLastModified()).isEqualTo(1772600767000L);
    assertThat(headers.getVary()).containsExactly(HttpHeaders.ACCEPT);
  }

  @Test
  @DisplayName("the abbreviated document is served as such, with a tag of its own")
  void abbreviatedDocumentHasItsOwnTag() throws Exception {
    final var abbreviated =
        this.get(ABBREVIATED, Map.of("name", "demo", "modified", "2026-03-04T05:06:07.089Z"));
    final var full = this.get("application/json", full("2026-03-04T05:06:07.089Z"));

    assertThat(abbreviated.getHeaders().getContentType().toString()).isEqualTo(ABBREVIATED);
    assertThat(abbreviated.getHeaders().getLastModified()).isEqualTo(1772600767000L);
    assertThat(abbreviated.getHeaders().getETag()).isNotEqualTo(full.getHeaders().getETag());
  }

  @Test
  @DisplayName("a document without a usable modification time has no Last-Modified, still an ETag")
  void noLastModified() throws Exception {
    final var response = this.get("*/*", Map.of("name", "demo"));

    assertThat(response.getHeaders().containsHeader(HttpHeaders.LAST_MODIFIED)).isFalse();
    assertThat(response.getHeaders().getETag()).isNotNull();
  }
}
