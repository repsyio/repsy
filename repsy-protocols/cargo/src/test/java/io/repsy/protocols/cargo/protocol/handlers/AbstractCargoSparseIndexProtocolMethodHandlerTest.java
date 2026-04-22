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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoSparseIndexProtocolMethodHandler")
class AbstractCargoSparseIndexProtocolMethodHandlerTest {

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private ObjectMapper objectMapper;
  @Mock private CargoProtocolProvider provider;
  @Mock private HttpServletRequest request;

  private AbstractCargoSparseIndexProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, objectMapper, provider);
  }

  // ── Concrete subclass for testing the abstract handler ───────────────────

  static class TestHandler extends AbstractCargoSparseIndexProtocolMethodHandler {

    TestHandler(
        final PathParser p,
        final CargoProtocolFacade f,
        final ObjectMapper o,
        final CargoProtocolProvider pr) {
      super(p, f, o, pr);
    }
  }

  // ── Handler metadata ─────────────────────────────────────────────────────

  @Test
  @DisplayName("supports only GET")
  void supportsOnlyGet() {
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
  }

  // ── Path pattern matching ─────────────────────────────────────────────────

  @Nested
  @DisplayName("getPathParser() — path matching")
  class PathMatchingTests {

    @Test
    @DisplayName("matches 1-character crate name under /1/{name}")
    void matches1CharName() {
      assertPathMatches("/1/a");
    }

    @Test
    @DisplayName("matches 2-character crate name under /2/{name}")
    void matches2CharName() {
      assertPathMatches("/2/ab");
    }

    @Test
    @DisplayName("matches 3-character crate name under /3/{first-char}/{name}")
    void matches3CharName() {
      assertPathMatches("/3/a/abc");
    }

    @ParameterizedTest(name = "matches 4+-char crate at path ''{0}''")
    @ValueSource(
        strings = {
          "/ab/cd/abcd",
          "/re/ps/repsy",
          "/my/cr/my_crate",
          "/re/ps/repsy_e2e_test",
          "/se/rd/serde_json"
        })
    @DisplayName("matches 4+-character crate names under /{2-char}/{2-char}/{name}")
    void matches4PlusCharName(final String path) {
      assertPathMatches(path);
    }

    @ParameterizedTest(name = "excludes reserved path ''{0}''")
    @ValueSource(strings = {"/api/v1/crates", "/api/v1/crates/new", "/config.json", "/me"})
    @DisplayName("excludes /api/*, /config.json and /me paths")
    void excludesReservedPaths(final String path) {
      assertPathEmpty(path);
    }

    @Test
    @DisplayName("returns empty for non-GET method")
    void returnsEmptyForNonGet() {
      when(request.getMethod()).thenReturn(HttpMethod.PUT.name());
      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the base path parser returns empty")
    void returnsEmptyWhenBaseParserReturnsEmpty() {
      when(request.getMethod()).thenReturn(HttpMethod.GET.name());
      when(basePathParser.parse(request)).thenReturn(Optional.empty());
      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void assertPathMatches(final String relativePath) {
      final var ctx = contextWithPath(relativePath);
      when(request.getMethod()).thenReturn(HttpMethod.GET.name());
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).isPresent();
    }

    private void assertPathEmpty(final String relativePath) {
      final var ctx = contextWithPath(relativePath);
      when(request.getMethod()).thenReturn(HttpMethod.GET.name());
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProtocolContext contextWithPath(final String path) {
      final var urlProps = mock(BaseUrlParserProperties.class);
      when(urlProps.getRelativePath()).thenReturn(new RelativePath(path));
      final var ctx = new ProtocolContext();
      ctx.addProperty("urlProperties", urlProps);
      return ctx;
    }
  }
}
