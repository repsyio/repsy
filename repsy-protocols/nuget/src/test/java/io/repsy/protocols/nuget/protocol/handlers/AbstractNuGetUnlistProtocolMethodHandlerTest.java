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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.dtos.NuGetErrorResponse;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("AbstractNuGetUnlistProtocolMethodHandler")
class AbstractNuGetUnlistProtocolMethodHandlerTest {

  private static final String PATH = "/nuget/v3/package/Some.Package/1.0.0";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private AbstractNuGetUnlistProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    handler = new TestHandler(basePathParser, facade, provider);
  }

  static class TestHandler extends AbstractNuGetUnlistProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static MockHttpServletRequest request(final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @Test
  @DisplayName("registers itself with the provider and exposes WRITE metadata")
  void metadata() {
    verify(provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.WRITE)
        .containsEntry("writeOperation", true);
  }

  @Test
  @DisplayName("getPathParser() returns empty for other HTTP methods")
  void pathParserRejectsOtherMethods() {
    assertThat(handler.getPathParser().parse(request("GET", PATH))).isEmpty();
    verifyNoInteractions(basePathParser);
  }

  @ParameterizedTest(name = "{0} -> matches={1}")
  @CsvSource({
    "/nuget/v3/package/Some.Package/1.0.0,   true",
    "/nuget/V3/PACKAGE/Some.Package/1.0.0,   true",
    "/nuget/v3/package/Some.Package,         false",
    "/nuget/v3/package/Some.Package/1.0.0/x, false",
    "/nuget/v3/registration/Some.Package/1.0.0, false"
  })
  @DisplayName("getPathParser() only matches .../v3/package/{id}/{version}")
  void pathParserMatchesEndpoint(final String path, final boolean matches) {
    final var request = request("DELETE", path);
    final var ctx = context(path);
    if (matches) {
      when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));
    }

    final var result = handler.getPathParser().parse(request);

    if (matches) {
      assertThat(result).containsSame(ctx);
    } else {
      assertThat(result).isEmpty();
    }
  }

  @Test
  @DisplayName("handle() returns 204 No Content when the facade succeeds")
  void handlesSuccess() throws IOException {
    final var ctx = context(PATH);

    final var response =
        handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    verify(facade).unlistVersion(ctx);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  @DisplayName("handle() maps ItemNotFoundException to 404 with the message id")
  void handlesNotFound() throws IOException {
    final var ctx = context(PATH);
    doThrow(new ItemNotFoundException("packageNotFound")).when(facade).unlistVersion(ctx);

    final var response =
        handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isEqualTo(NuGetErrorResponse.of("packageNotFound"));
  }

  @Test
  @DisplayName("handle() maps any other failure to 500 without leaking the cause")
  void handlesUnexpectedFailure() throws IOException {
    final var ctx = context(PATH);
    doThrow(new IllegalStateException("db down")).when(facade).unlistVersion(ctx);

    final var response =
        handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isEqualTo(NuGetErrorResponse.of("Unlist failed"));
  }
}
