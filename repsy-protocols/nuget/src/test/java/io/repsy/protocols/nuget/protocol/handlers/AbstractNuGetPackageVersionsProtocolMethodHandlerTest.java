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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetPackageVersionsProtocolMethodHandler")
class AbstractNuGetPackageVersionsProtocolMethodHandlerTest {

  private static final String PATH = "/nuget/v3/package/some.package/index.json";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private AbstractNuGetPackageVersionsProtocolMethodHandler handler;

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  static class TestHandler extends AbstractNuGetPackageVersionsProtocolMethodHandler {

    TestHandler(final PathParser p, final NuGetProtocolFacade f, final NuGetProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static MockHttpServletRequest request(final String path) {
    final var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return request;
  }

  @Test
  @DisplayName("registers itself with the provider and exposes READ metadata")
  void metadata() {
    verify(this.provider).registerMethodHandler(this.handler);
    assertThat(this.handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(this.handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false);
  }

  @Test
  @DisplayName("getPathParser() returns empty when the base parser does not match")
  void pathParserDefersToBaseParser() {
    final var request = request(PATH);
    when(this.basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(this.handler.getPathParser().parse(request)).isEmpty();
  }

  @ParameterizedTest(name = "{0} -> matches={1}")
  @CsvSource({
    "/nuget/v3/package/some.package/index.json,        true",
    "/nuget/V3/PACKAGE/Some.Package/INDEX.JSON,        true",
    "/nuget/v3/package/some.package/1.0.0/index.json,  false",
    "/nuget/v3/package/some.package,                   false",
    "/nuget/v3/registration/some.package/index.json,   false"
  })
  @DisplayName("getPathParser() only matches .../v3/package/{id}/index.json")
  void pathParserMatchesEndpoint(final String path, final boolean matches) {
    final var request = request(path);
    final var ctx = context(path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(ctx));

    final var result = this.handler.getPathParser().parse(request);

    if (matches) {
      assertThat(result).containsSame(ctx);
    } else {
      assertThat(result).isEmpty();
    }
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("handle() returns 200 with the versions as JSON")
  void handlesSuccess() {
    final var ctx = context(PATH);
    when(this.facade.getPackageVersions(ctx)).thenReturn(List.of("1.0.0", "1.1.0"));

    final var response =
        this.handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isEqualTo(Map.of("versions", List.of("1.0.0", "1.1.0")));
  }

  @Test
  @DisplayName("handle() answers 404 when the package is not found")
  void handlesNotFound() {
    final var ctx = context(PATH);
    when(this.facade.getPackageVersions(ctx))
        .thenThrow(new ItemNotFoundException("packageNotFound"));

    final var response =
        this.handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "handle() answers 500 for any other failure instead of pretending the package is missing")
  void handlesUnexpectedFailure() {
    final var ctx = context(PATH);
    when(this.facade.getPackageVersions(ctx)).thenThrow(new IllegalStateException("db down"));

    final var response =
        this.handler.handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
