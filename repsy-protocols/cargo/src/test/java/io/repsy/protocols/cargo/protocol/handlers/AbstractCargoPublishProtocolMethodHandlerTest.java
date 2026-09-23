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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractCargoPublishProtocolMethodHandler")
class AbstractCargoPublishProtocolMethodHandlerTest {

  private static final String PUBLISH_PATH = "/api/v1/crates/new";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoPublishProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractCargoPublishProtocolMethodHandler {

    TestHandler(final PathParser p, final CargoProtocolFacade f, final CargoProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  @Test
  @DisplayName("registers itself with the provider and exposes WRITE metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.PUT);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Nested
  @DisplayName("getPathParser()")
  class PathParserTests {

    @Test
    @DisplayName("returns empty for non-PUT method")
    void returnsEmptyForNonPut() {
      final var request = new MockHttpServletRequest("GET", PUBLISH_PATH);

      assertThat(handler.getPathParser().parse(request)).isEmpty();
      verifyNoInteractions(basePathParser);
    }

    @Test
    @DisplayName("returns empty when the base parser returns empty")
    void returnsEmptyWhenBaseParserEmpty() {
      final var request = new MockHttpServletRequest("PUT", PUBLISH_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.empty());

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }

    @Test
    @DisplayName("matches PUT /api/v1/crates/new")
    void matchesPublishPath() {
      final var request = new MockHttpServletRequest("PUT", PUBLISH_PATH);
      final var ctx = context(PUBLISH_PATH);
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

      assertThat(handler.getPathParser().parse(request)).containsSame(ctx);
    }

    @Test
    @DisplayName("returns empty for other PUT paths")
    void rejectsOtherPaths() {
      final var path = "/api/v1/crates/serde/1.0.0/unyank";
      final var request = new MockHttpServletRequest("PUT", path);
      when(basePathParser.parse(request)).thenReturn(Optional.of(context(path)));

      assertThat(handler.getPathParser().parse(request)).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle()")
  class HandleTests {

    @Test
    @DisplayName("publishes the request body and returns the cargo success payload")
    void publishes() throws IOException {
      final var ctx = context(PUBLISH_PATH);
      final var request = new MockHttpServletRequest("PUT", PUBLISH_PATH);
      request.setContent(new byte[] {1, 2, 3});

      final var result = handler.handle(ctx, request, new MockHttpServletResponse());

      verify(facade).publish(eq(ctx), any(InputStream.class));
      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(result.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
          .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
      assertThat((String) result.getBody()).contains("\"warnings\"");
    }

    @Test
    @DisplayName("returns 400 with a cargo error body on a deliberate validation failure")
    void returnsBadRequestOnValidationFailure() throws IOException {
      final var ctx = context(PUBLISH_PATH);
      doThrow(new IllegalArgumentException("crateVersionAlreadyExists"))
          .when(facade)
          .publish(eq(ctx), any(InputStream.class));

      final var result =
          handler.handle(
              ctx, new MockHttpServletRequest("PUT", PUBLISH_PATH), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(errorDetail(result)).isEqualTo("crateVersionAlreadyExists");
    }

    @Test
    @DisplayName("returns 400 with a cargo error body when the crate/version already exists")
    void returnsBadRequestOnAlreadyExists() throws IOException {
      final var ctx = context(PUBLISH_PATH);
      doThrow(new ItemAlreadyExistException("crateVersionAlreadyExists"))
          .when(facade)
          .publish(eq(ctx), any(InputStream.class));

      final var result =
          handler.handle(
              ctx, new MockHttpServletRequest("PUT", PUBLISH_PATH), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(errorDetail(result))
          .isEqualTo("this crate version already exists in this registry");
    }

    @Test
    @DisplayName("returns 413 with a cargo error body when the crate is too large (RPS-1119)")
    void returnsPayloadTooLargeOnOversizedCrate() throws IOException {
      final var ctx = context(PUBLISH_PATH);
      doThrow(new MaxUploadSizeExceededException(100L))
          .when(facade)
          .publish(eq(ctx), any(InputStream.class));

      final var result =
          handler.handle(
              ctx, new MockHttpServletRequest("PUT", PUBLISH_PATH), new MockHttpServletResponse());

      assertThat(result.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
      assertThat(errorDetail(result)).isEqualTo("the crate exceeds the maximum upload size");
    }

    /**
     * RPS-1141: a genuine server fault must not be caught here and shown to the client with its raw
     * message (which could carry SQL text, class names, or other internals) — it must propagate to
     * {@code ErrorHandler}, which answers a generic 500 instead. Flip-and-fail check: widening the
     * catch in {@code handle()} back to {@code Exception} makes this test fail, because the
     * facade's exception would then be caught and turned into a 400 carrying its message.
     */
    @Test
    @DisplayName("does not catch a genuine server exception, and does not leak its message")
    void propagatesUnexpectedExceptionsInsteadOfLeakingThem() throws IOException {
      final var ctx = context(PUBLISH_PATH);
      final var sensitiveMessage =
          "duplicate key value violates unique constraint \"cargo_crate_pkey\"";
      doThrow(new RuntimeException(sensitiveMessage)).when(facade).publish(eq(ctx), any());

      assertThatThrownBy(
              () ->
                  handler.handle(
                      ctx,
                      new MockHttpServletRequest("PUT", PUBLISH_PATH),
                      new MockHttpServletResponse()))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining(sensitiveMessage);
    }
  }
}
