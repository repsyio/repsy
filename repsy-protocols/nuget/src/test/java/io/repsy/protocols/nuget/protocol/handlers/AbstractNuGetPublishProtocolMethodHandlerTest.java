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
package io.repsy.protocols.nuget.protocol.handlers;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.dtos.NuGetErrorResponse;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetPublishProtocolMethodHandler.handle()")
class AbstractNuGetPublishProtocolMethodHandlerTest {

  private static final String MULTIPART = "Multipart/Form-Data; boundary=x";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private AbstractNuGetPublishProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractNuGetPublishProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static MockHttpServletRequest multipartRequest(final String contentType) {
    final var request = new MockHttpServletRequest("PUT", "/nuget/v3/package");
    request.setContentType(contentType);
    return request;
  }

  private static MockPart nupkgPart() {
    return new MockPart("package", "some.nupkg", "nupkg".getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"application/json", "application/octet-stream"})
  @DisplayName("rejects a request that is not multipart/form-data with 400")
  void rejectsNonMultipartRequest(final String contentType) throws IOException {
    final var request = multipartRequest(contentType);
    request.addPart(nupkgPart());

    final var response =
        handler.handle(context("/v3/package"), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(NuGetErrorResponse.of("Content-Type must be multipart/form-data"));
    verify(facade, never()).publish(any(), any());
  }

  @Test
  @DisplayName("rejects a multipart request without parts with 400")
  void rejectsMissingParts() throws IOException {
    final var response =
        handler.handle(
            context("/v3/package"), multipartRequest(MULTIPART), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo(NuGetErrorResponse.of("Missing package content."));
    verify(facade, never()).publish(any(), any());
  }

  @Test
  @DisplayName("accepts the content type in any case and publishes the package part with 201")
  void publishesPackage() throws IOException {
    final var ctx = context("/v3/package");
    final var request = multipartRequest(MULTIPART);
    request.addPart(nupkgPart());

    final var response = handler.handle(ctx, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(facade).publish(eq(ctx), any(InputStream.class));
  }

  @Test
  @DisplayName("maps a ResponseStatusException from the facade to its own status")
  void mapsConflict() throws IOException {
    final var ctx = context("/v3/package");
    final var request = multipartRequest(MULTIPART);
    request.addPart(nupkgPart());
    doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Version 1.0.0 already exists."))
        .when(facade)
        .publish(eq(ctx), any(InputStream.class));

    final var response = handler.handle(ctx, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(NuGetErrorResponse.of("Version 1.0.0 already exists."));
  }

  @Test
  @DisplayName("maps any other failure to 500 without leaking the cause")
  void mapsUnexpectedFailure() throws IOException {
    final var ctx = context("/v3/package");
    final var request = multipartRequest(MULTIPART);
    request.addPart(nupkgPart());
    doThrow(new IllegalStateException("db down"))
        .when(facade)
        .publish(eq(ctx), any(InputStream.class));

    final var response = handler.handle(ctx, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isEqualTo(NuGetErrorResponse.of("Publish failed"));
  }
}
