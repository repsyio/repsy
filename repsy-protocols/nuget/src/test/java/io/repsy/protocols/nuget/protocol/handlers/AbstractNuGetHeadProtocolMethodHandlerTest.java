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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contract.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("AbstractNuGetHeadProtocolMethodHandler (RPS-1465)")
class AbstractNuGetHeadProtocolMethodHandlerTest {

  private static final String BASE = "/nuget/v3/";
  private static final String NUPKG_PATH =
      BASE + "package/some.package/1.0.0/some.package.1.0.0.nupkg";
  private static final String NUSPEC_PATH = BASE + "package/some.package/1.0.0/some.package.nuspec";
  private static final String VERSIONS_PATH = BASE + "package/some.package/index.json";
  private static final String REGISTRATION_INDEX_PATH =
      BASE + "registration/some.package/index.json";
  private static final String REGISTRATION_LEAF_PATH =
      BASE + "registration/some.package/1.0.0.json";

  @Mock private PathParser basePathParser;
  @Mock private NuGetProtocolFacade facade;
  @Mock private NuGetProtocolProvider provider;

  private final NuGetBaseUrlResolver resolver = (request, repoName) -> "http://host/" + repoName;

  static class TestHandler extends AbstractNuGetHeadProtocolMethodHandler {

    TestHandler(
        final PathParser p,
        final NuGetProtocolFacade f,
        final NuGetProtocolProvider pr,
        final NuGetBaseUrlResolver r) {
      super(p, f, pr, r);
    }
  }

  private AbstractNuGetHeadProtocolMethodHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider, this.resolver);
  }

  private static MockHttpServletRequest request(final String path) {
    final var request = new MockHttpServletRequest("HEAD", path);
    request.setServletPath(path);
    return request;
  }

  private org.springframework.http.ResponseEntity<Object> head(final String path) throws Exception {
    return this.handler()
        .handle(
            context("/v3/" + path.substring(BASE.length())),
            request(path),
            new MockHttpServletResponse());
  }

  @Test
  @DisplayName("registers itself, supports only HEAD, needs READ and is not a billed download")
  void metadata() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        NUPKG_PATH,
        NUSPEC_PATH,
        VERSIONS_PATH,
        REGISTRATION_INDEX_PATH,
        REGISTRATION_LEAF_PATH,
      })
  @DisplayName("path parser: hands the read routes to the base parser")
  void pathParserAccepts(final String path) {
    final var request = request(path);
    final var ctx = context(path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(ctx));

    assertThat(this.handler().getPathParser().parse(request)).containsSame(ctx);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        BASE + "index.json",
        BASE + "query",
        BASE + "autocomplete",
        BASE + "package/some.package/1.0.0",
      })
  @DisplayName("path parser: leaves the other routes alone")
  void pathParserRejects(final String path) {
    assertThat(this.handler().getPathParser().parse(request(path))).isEmpty();
  }

  @Test
  @DisplayName(
      "a .nupkg answers 200 with the headers of its GET and its length, without a download")
  void nupkgExists() throws Exception {
    when(this.facade.getNuPackage(any())).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));

    final var response = this.head(NUPKG_PATH);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"some.package.1.0.0.nupkg\"");
    verify(this.facade, never()).downloadNuPackage(any());
  }

  @Test
  @DisplayName("a .nuspec answers 200 with the headers of its GET and its length")
  void nuspecExists() throws Exception {
    when(this.facade.downloadNuspec(any())).thenReturn(new ByteArrayResource(new byte[] {1, 2}));

    final var response = this.head(NUSPEC_PATH);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentLength()).isEqualTo(2);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("inline; filename=\"some.package.nuspec\"");
  }

  @Test
  @DisplayName("a missing .nupkg or .nuspec answers 404 without a Content-Disposition")
  void filesMissing() throws Exception {
    when(this.facade.getNuPackage(any())).thenThrow(new ItemNotFoundException("itemNotFound"));
    when(this.facade.downloadNuspec(any())).thenThrow(new ItemNotFoundException("itemNotFound"));

    for (final var path : List.of(NUPKG_PATH, NUSPEC_PATH)) {
      final var response = this.head(path);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }
  }

  @Test
  @DisplayName("the version list answers 200 application/json when the package exists, 404 if not")
  void versions() throws Exception {
    when(this.facade.getPackageVersions(any())).thenReturn(List.of("1.0.0"));

    final var ok = this.head(VERSIONS_PATH);

    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ok.getBody()).isNull();
    assertThat(ok.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

    when(this.facade.getPackageVersions(any())).thenThrow(new ItemNotFoundException("nope"));

    assertThat(this.head(VERSIONS_PATH).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("the registration index and leaf answer 200 application/json, or 404 when missing")
  void registration() throws Exception {
    assertThat(this.head(REGISTRATION_INDEX_PATH).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(this.head(REGISTRATION_LEAF_PATH).getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_JSON);
    verify(this.facade).getRegistrationIndex(any(), eq("http://host/nuget"));
    verify(this.facade).getRegistrationLeaf(any(), eq("http://host/nuget"));

    when(this.facade.getRegistrationIndex(any(), any()))
        .thenThrow(new ItemNotFoundException("nope"));
    when(this.facade.getRegistrationLeaf(any(), any()))
        .thenThrow(new IllegalArgumentException("bad version"));

    assertThat(this.head(REGISTRATION_INDEX_PATH).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(this.head(REGISTRATION_LEAF_PATH).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
