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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoDownloadProtocolMethodHandler")
class AbstractCargoDownloadProtocolMethodHandlerTest {

  private static final String DOWNLOAD_PATH = "/api/v1/crates/serde/1.0.0/download";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoDownloadProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractCargoDownloadProtocolMethodHandler {

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
      final var request = new MockHttpServletRequest("PUT", DOWNLOAD_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("matches /api/v1/crates/{name}/{version}/download")
    void matchesDownloadPath() {
      final var request = new MockHttpServletRequest("GET", DOWNLOAD_PATH);
      final var ctx = context(DOWNLOAD_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }

    @ParameterizedTest(name = "rejects ''{0}''")
    @ValueSource(
        strings = {
          "/api/v1/crates/serde/download",
          "/api/v1/crates/serde/1.0.0/yank",
          "/api/v1/crates/serde/1.0.0/download/extra",
          "/se/rd/serde"
        })
    @DisplayName("returns empty for non-download paths")
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
    @DisplayName("returns the crate as an octet stream")
    void returnsCrate() {
      final var ctx = context(DOWNLOAD_PATH);
      final var resource = new ByteArrayResource(new byte[] {1, 2, 3});
      when(facade.download(ctx)).thenReturn(resource);

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
          .isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
      assertThat(result.getBody()).isSameAs(resource);
    }

    @Test
    @DisplayName("returns 404 when the facade fails")
    void returnsNotFoundOnError() {
      final var ctx = context(DOWNLOAD_PATH);
      when(facade.download(ctx)).thenThrow(new ItemNotFoundException("crateNotFound"));

      final var result =
          handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(result.getBody()).isNull();
    }
  }
}
