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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoSearchProtocolMethodHandler")
class AbstractCargoSearchProtocolMethodHandlerTest {

  private static final String SEARCH_PATH = "/api/v1/crates";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoSearchProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractCargoSearchProtocolMethodHandler {

    TestHandler(final PathParser p, final CargoProtocolFacade f, final CargoProtocolProvider pr) {
      super(p, f, pr);
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

    @Test
    @DisplayName("returns empty for non-GET method")
    void returnsEmptyForNonGet() {
      final var request = new MockHttpServletRequest("PUT", SEARCH_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("matches GET /api/v1/crates")
    void matchesSearchPath() {
      final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
      final var ctx = context(SEARCH_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }

    @Test
    @DisplayName("returns empty for other paths")
    void rejectsOtherPaths() {
      final var path = "/api/v1/crates/serde/1.0.0/download";
      final var request = new MockHttpServletRequest("GET", path);
      when(basePathParser.parse(request)).thenReturn(Optional.of(context(path)));

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("uses defaults (empty query, page 1, 10 per page) when no parameters are given")
    void usesDefaults() {
      final var ctx = context(SEARCH_PATH);
      when(facade.search(eq(ctx), eq(""), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of()));

      final var result =
          handler.handle(
              ctx, new MockHttpServletRequest("GET", SEARCH_PATH), new MockHttpServletResponse());

      final var pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(facade).search(eq(ctx), eq(""), pageable.capture());
      assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 10));
      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("passes query and pagination, returning crates and total")
    @SuppressWarnings("unchecked")
    void passesQueryAndPagination() {
      final var ctx = context(SEARCH_PATH);
      final var item = new CrateListItem("serde", "1.0.0", 42L, "desc", Instant.EPOCH);
      final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
      request.setParameter("q", "ser");
      request.setParameter("per_page", "20");
      request.setParameter("page", "3");
      when(facade.search(eq(ctx), eq("ser"), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(2, 20), 41));

      final var result = handler.handle(ctx, request, new MockHttpServletResponse());

      final var pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(facade).search(eq(ctx), eq("ser"), pageable.capture());
      assertThat(pageable.getValue()).isEqualTo(PageRequest.of(2, 20));

      final var body = (Map<String, Object>) result.getBody();
      assertThat(body).containsEntry("crates", List.of(item));
      assertThat((Map<String, Object>) body.get("meta")).containsEntry("total", 41L);
    }

    @Test
    @DisplayName("clamps per_page to [1, 100] and page to at least 1")
    void clampsPagination() {
      final var ctx = context(SEARCH_PATH);
      final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
      request.setParameter("per_page", "500");
      request.setParameter("page", "0");
      when(facade.search(eq(ctx), eq(""), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of()));

      handler.handle(ctx, request, new MockHttpServletResponse());

      final var pageable = ArgumentCaptor.forClass(Pageable.class);
      verify(facade).search(eq(ctx), eq(""), pageable.capture());
      assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("returns 400 with a cargo error body for invalid pagination")
    void returnsBadRequestOnInvalidParameter() {
      final var request = new MockHttpServletRequest("GET", SEARCH_PATH);
      request.setParameter("per_page", "abc");

      final var result =
          handler.handle(context(SEARCH_PATH), request, new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(errorDetail(result)).contains("abc");
      verifyNoInteractions(facade);
    }
  }
}
