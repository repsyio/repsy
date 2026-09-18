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
import static io.repsy.protocols.cargo.protocol.handlers.CargoHandlerTestSupport.errorDetail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoYankProtocolMethodHandler")
class AbstractCargoYankProtocolMethodHandlerTest {

  private static final String YANK_PATH = "/api/v1/crates/serde/1.0.0/yank";
  private static final String UNYANK_PATH = "/api/v1/crates/serde/1.0.0/unyank";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoYankProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractCargoYankProtocolMethodHandler {

    TestHandler(final PathParser p, final CargoProtocolFacade f, final CargoProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  @Test
  @DisplayName("registers itself with the provider and exposes WRITE metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE, HttpMethod.PUT);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @Test
    @DisplayName("returns empty for unsupported methods")
    void returnsEmptyForUnsupportedMethod() {
      final var request = new MockHttpServletRequest("GET", YANK_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("DELETE", YANK_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} -> matches={2}")
    @CsvSource({
      "DELETE, /api/v1/crates/serde/1.0.0/yank,     true",
      "PUT,    /api/v1/crates/serde/1.0.0/unyank,   true",
      "PUT,    /api/v1/crates/serde/1.0.0/yank,     false",
      "DELETE, /api/v1/crates/serde/1.0.0/unyank,   false",
      "DELETE, /api/v1/crates/serde/yank,           false",
      "PUT,    /api/v1/crates/new,                  false"
    })
    @DisplayName("only matches DELETE .../yank and PUT .../unyank")
    void matchesEndpointAndMethod(final String method, final String path, final boolean matches) {
      final var request = new MockHttpServletRequest(method, path);
      final var ctx = context(path);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      final var result = handler.getPathParser().parse(request);

      if (matches) {
        assertThat(result).containsSame(ctx);
      } else {
        assertThat(result).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("yanks the version for a /yank path")
    void yanks() {
      final var ctx = context(YANK_PATH);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      verify(facade).yank(ctx);
      verify(facade, never()).unyank(ctx);
      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getBody()).isEqualTo(Map.of("ok", true));
    }

    @Test
    @DisplayName("unyanks the version for an /unyank path")
    void unyanks() {
      final var ctx = context(UNYANK_PATH);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      verify(facade).unyank(ctx);
      verify(facade, never()).yank(ctx);
      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("returns 400 with a cargo error body when the facade fails")
    void returnsBadRequestOnError() {
      final var ctx = context(YANK_PATH);
      doThrow(new IllegalStateException("crateVersionNotFound")).when(facade).yank(ctx);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(errorDetail(result)).isEqualTo("crateVersionNotFound");
    }
  }
}
