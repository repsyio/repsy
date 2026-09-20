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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetDownloadProtocolMethodHandler")
class AbstractNuGetDownloadProtocolMethodHandlerTest {

  private static final String NUPKG_PATH =
      "/nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nupkg";
  private static final String NUSPEC_PATH =
      "/nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nuspec";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  static class TestHandler extends AbstractNuGetDownloadProtocolMethodHandler {

    TestHandler(
        final PathParser p,
        final NuGetProtocolFacade f,
        final NuGetProtocolProvider pr,
        final boolean isNupkg) {
      super(p, f, pr, isNupkg);
    }
  }

  private AbstractNuGetDownloadProtocolMethodHandler handler(final boolean isNupkg) {
    return new TestHandler(this.basePathParser, this.facade, this.provider, isNupkg);
  }

  private static MockHttpServletRequest request(final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @Test
  @DisplayName("registers itself with the provider and exposes READ metadata")
  void metadata() {
    final var handler = this.handler(true);

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false);
  }

  @Test
  @DisplayName("getPathParser() returns empty for other HTTP methods")
  void pathParserRejectsOtherMethods() {
    assertThat(this.handler(true).getPathParser().parse(request("POST", NUPKG_PATH))).isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @ParameterizedTest(name = "nupkg={0} {1} -> matches={2}")
  @CsvSource({
    "true,  /nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nupkg,  true",
    "true,  /nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nuspec, false",
    "false, /nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nuspec, true",
    "false, /nuget/v3/package/some.package/1.0.0/some.package.1.0.0.nupkg,  false",
    "true,  /nuget/v3/package/some.package/index.json,                      false"
  })
  @DisplayName("getPathParser() matches the nupkg or the nuspec endpoint, not both")
  void pathParserMatchesEndpoint(final boolean isNupkg, final String path, final boolean matches) {
    final var request = request("GET", path);
    final var ctx = context(path);
    if (matches) {
      when(this.basePathParser.parse(request)).thenReturn(Optional.of(ctx));
    }

    final var result = this.handler(isNupkg).getPathParser().parse(request);

    if (matches) {
      assertThat(result).containsSame(ctx);
    } else {
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("handle() for the .nupkg endpoint")
  class Nupkg {

    @Test
    @DisplayName("returns 200 with the package bytes as application/octet-stream")
    void success() {
      final var ctx = context(NUPKG_PATH);
      final var resource = new ByteArrayResource(new byte[] {1, 2, 3});
      when(facade.downloadNuPackage(ctx)).thenReturn(resource);

      final var response =
          handler(true).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getHeaders().getContentType())
          .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
      assertThat(response.getBody()).isSameAs(resource);
    }

    @Test
    @DisplayName("answers 404 when the package or version is not found")
    void notFound() {
      final var ctx = context(NUPKG_PATH);
      when(facade.downloadNuPackage(ctx)).thenThrow(new ItemNotFoundException("nupkgNotFound"));

      final var response =
          handler(true).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("answers 500 for any other failure instead of pretending the package is missing")
    void unexpectedFailure() {
      final var ctx = context(NUPKG_PATH);
      when(facade.downloadNuPackage(ctx)).thenThrow(new IllegalStateException("storage down"));

      final var response =
          handler(true).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Nested
  @DisplayName("handle() for the .nuspec endpoint")
  class Nuspec {

    @Test
    @DisplayName("returns 200 with the nuspec as application/xml")
    void success() {
      final var ctx = context(NUSPEC_PATH);
      final var resource = new ByteArrayResource("<package/>".getBytes());
      when(facade.downloadNuspec(ctx)).thenReturn(resource);

      final var response =
          handler(false).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
      assertThat(response.getBody()).isSameAs(resource);
    }

    @Test
    @DisplayName("answers 404 when the package or version is not found")
    void notFound() {
      final var ctx = context(NUSPEC_PATH);
      when(facade.downloadNuspec(ctx)).thenThrow(new ItemNotFoundException("nuspecNotFound"));

      final var response =
          handler(false).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("answers 500 for any other failure instead of pretending the package is missing")
    void unexpectedFailure() {
      final var ctx = context(NUSPEC_PATH);
      when(facade.downloadNuspec(ctx)).thenThrow(new IllegalStateException("storage down"));

      final var response =
          handler(false).handle(ctx, new MockHttpServletRequest(), new MockHttpServletResponse());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
