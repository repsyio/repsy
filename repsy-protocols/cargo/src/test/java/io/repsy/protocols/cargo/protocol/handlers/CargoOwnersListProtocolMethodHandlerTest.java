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
import io.repsy.protocols.cargo.shared.crate.dtos.CargoOwnersResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
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
@DisplayName("AbstractCargoOwnersListProtocolMethodHandler")
class CargoOwnersListProtocolMethodHandlerTest {

  private static final String OWNERS_PATH = "/api/v1/crates/serde/owners";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoOwnersListProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, provider);
  }

  static class TestHandler extends AbstractCargoOwnersListProtocolMethodHandler {
    TestHandler(final PathParser p, final CargoProtocolProvider pr) {
      super(p, pr);
    }
  }

  @Test
  @DisplayName("registers itself with the provider and exposes READ metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {"PUT", "DELETE", "POST"})
    @DisplayName("returns empty for PUT/DELETE and other non-GET methods")
    void rejectsNonGet(final String method) {
      final var request = new MockHttpServletRequest(method, OWNERS_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("matches /api/v1/crates/{name}/owners on GET")
    void matchesOwnersPath() {
      final var request = new MockHttpServletRequest("GET", OWNERS_PATH);
      final var ctx = context(OWNERS_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("GET", OWNERS_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(
        strings = {"/api/v1/crates/serde/owners/extra", "/api/v1/crates/owners", "/se/rd/serde"})
    @DisplayName("returns empty for a different or a trailing-segment path")
    void rejectsOtherPaths(final String path) {
      final var request = new MockHttpServletRequest("GET", path);
      when(basePathParser.parse(request)).thenReturn(Optional.of(context(path)));

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("returns a non-empty users array, the regression pin for \"missing field users\"")
    void returnsUsersArray() {
      final var ctx = context(OWNERS_PATH);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
          .isEqualTo(MediaType.APPLICATION_JSON_VALUE);

      final var body = (CargoOwnersResponse) result.getBody();
      assertThat(body.users()).isNotEmpty();

      final var owner = body.users().getFirst();
      assertThat(owner.id()).isZero();
      assertThat(owner.login()).isNotBlank();
      assertThat(owner.name()).isNotBlank();
    }

    @Test
    @DisplayName("reports the repo name as the synthetic owner's login")
    void reportsRepoNameAsLogin() {
      final var ctx = context(OWNERS_PATH);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      final var body = (CargoOwnersResponse) result.getBody();
      assertThat(body.users().getFirst().login()).isEqualTo(CargoHandlerTestSupport.REPO_NAME);
    }
  }
}
