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
package io.repsy.protocols.cargo.protocol.handlers;

import static io.repsy.protocols.cargo.protocol.handlers.CargoHandlerTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoOwnersModifyProtocolMethodHandler")
class CargoOwnersModifyProtocolMethodHandlerTest {

  private static final String OWNERS_PATH = "/api/v1/crates/serde/owners";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoOwnersModifyProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, provider);
  }

  static class TestHandler extends AbstractCargoOwnersModifyProtocolMethodHandler {
    TestHandler(final PathParser p, final CargoProtocolProvider pr) {
      super(p, pr);
    }
  }

  @Test
  @DisplayName("registers itself with the provider and exposes WRITE metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.PUT, HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @ParameterizedTest(name = "matches {0} on the owners path")
    @ValueSource(strings = {"PUT", "DELETE"})
    @DisplayName("matches PUT and DELETE")
    void matchesOwnersPath(final String method) {
      final var request = new MockHttpServletRequest(method, OWNERS_PATH);
      final var ctx = context(OWNERS_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {"GET", "POST"})
    @DisplayName("returns empty for GET and other unsupported methods")
    void rejectsUnsupportedMethods(final String method) {
      final var request = new MockHttpServletRequest(method, OWNERS_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("PUT", OWNERS_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("returns empty for a different path")
    void rejectsOtherPaths() {
      final var path = "/api/v1/crates/serde/owners/extra";
      final var request = new MockHttpServletRequest("PUT", path);
      when(basePathParser.parse(request)).thenReturn(Optional.of(context(path)));

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("returns the ok/msg body for PUT")
    void putReturnsOkBody() {
      final var ctx = context(OWNERS_PATH);

      final var result =
          handler.handle(
              ctx, new MockHttpServletRequest("PUT", OWNERS_PATH), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
          .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
      assertThat(result.getBody())
          .isEqualTo(
              Map.of(
                  "ok",
                  true,
                  "msg",
                  "Ownership is managed at the repository level in this registry"));
    }

    @Test
    @DisplayName("returns the ok/msg body for DELETE")
    void deleteReturnsOkBody() {
      final var ctx = context(OWNERS_PATH);

      final var result =
          handler.handle(
              ctx,
              new MockHttpServletRequest("DELETE", OWNERS_PATH),
              new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getBody())
          .isEqualTo(
              Map.of(
                  "ok",
                  true,
                  "msg",
                  "Ownership is managed at the repository level in this registry"));
    }
  }
}
