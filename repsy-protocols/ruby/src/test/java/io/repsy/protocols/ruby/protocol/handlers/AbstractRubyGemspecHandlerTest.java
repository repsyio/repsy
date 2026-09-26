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
package io.repsy.protocols.ruby.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyGemspecHandler")
class AbstractRubyGemspecHandlerTest {

  private static final String REPO_NAME = "demo-repo";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static class TestHandler extends AbstractRubyGemspecHandler {

    TestHandler(
        final PathParser basePathParser,
        final RubyProtocolFacade facade,
        final RubyProtocolProvider provider) {
      super(basePathParser, facade, provider);
    }
  }

  private TestHandler handler() {
    return new TestHandler(this.basePathParser, this.facade, this.provider);
  }

  private static ProtocolContext contextFor(final String relativePath) {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setName(REPO_NAME);

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  // --- getPathParser() ---

  @Test
  @DisplayName("matches GET /quick/Marshal.4.8/<file>.gemspec.rz")
  void matchesGemspecPath() {
    final var path = "/quick/Marshal.4.8/demo-1.2.3.gemspec.rz";
    final var request = new MockHttpServletRequest("GET", path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(contextFor(path)));

    assertThat(this.handler().getPathParser().parse(request)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/versions",
        "/gems/demo-1.2.3.gem",
        "/quick/Marshal.4.8/demo-1.2.3.gemspec",
      })
  @DisplayName("rejects a GET path that is not a .gemspec.rz under quick/Marshal.4.8")
  void rejectsOtherPaths(final String path) {
    final var request = new MockHttpServletRequest("GET", path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(contextFor(path)));

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @Test
  @DisplayName("rejects a non-GET method without consulting the base parser")
  void rejectsNonGetMethod() {
    final var request =
        new MockHttpServletRequest("POST", "/quick/Marshal.4.8/demo-1.2.3.gemspec.rz");

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @Test
  @DisplayName("rejects when the base path parser finds no repo")
  void rejectsWhenBaseParserFindsNoRepo() {
    final var path = "/quick/Marshal.4.8/demo-1.2.3.gemspec.rz";
    final var request = new MockHttpServletRequest("GET", path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  // --- handle() ---

  @Test
  @DisplayName("splits a plain name-version filename and answers 200 octet-stream")
  void splitsPlainFilename() {
    when(this.facade.getGemspec(any(), eq("demo"), eq("1.2.3")))
        .thenReturn("stub".getBytes(StandardCharsets.UTF_8));

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.2.3.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    verify(this.facade).getGemspec(any(), eq("demo"), eq("1.2.3"));
  }

  @Test
  @DisplayName("names the download after the .gemspec.rz file instead of f.txt (RPS-1442)")
  void namesTheGemspecFile() {
    when(this.facade.getGemspec(any(), eq("demo"), eq("1.2.3"))).thenReturn(new byte[0]);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.2.3.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo-1.2.3.gemspec.rz\"");
  }

  @Test
  @DisplayName("strips a platform suffix from the version")
  void stripsPlatformSuffix() {
    when(this.facade.getGemspec(any(), eq("demo"), eq("1.2.3"))).thenReturn(new byte[0]);

    this.handler()
        .handle(
            contextFor("/quick/Marshal.4.8/demo-1.2.3-java.gemspec.rz"),
            new MockHttpServletRequest(),
            new MockHttpServletResponse());

    verify(this.facade).getGemspec(any(), eq("demo"), eq("1.2.3"));
  }

  @Test
  @DisplayName("finds the last name/version boundary so a name containing digits stays whole")
  void findsLastBoundary() {
    when(this.facade.getGemspec(any(), eq("foo-2fa"), eq("1.0.0"))).thenReturn(new byte[0]);

    this.handler()
        .handle(
            contextFor("/quick/Marshal.4.8/foo-2fa-1.0.0.gemspec.rz"),
            new MockHttpServletRequest(),
            new MockHttpServletResponse());

    verify(this.facade).getGemspec(any(), eq("foo-2fa"), eq("1.0.0"));
  }

  @Test
  @DisplayName("404s a filename with no name/version boundary, without calling the facade")
  void answersNotFoundWhenNoBoundary() {
    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/noboundary.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName(
      "deflates the facade's bytes with raw-zlib framing that InflaterInputStream reads back")
  void deflatesRawZlib() throws Exception {
    final var raw = "the-gemspec-marshal-bytes".repeat(20).getBytes(StandardCharsets.UTF_8);
    when(this.facade.getGemspec(any(), eq("demo"), eq("1.2.3"))).thenReturn(raw);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.2.3.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    final var body = (byte[]) response.getBody();
    try (var inflater = new InflaterInputStream(new ByteArrayInputStream(body))) {
      assertThat(inflater.readAllBytes()).isEqualTo(raw);
    }
  }

  @Test
  @DisplayName("maps a facade ItemNotFoundException to 404")
  void mapsNotFoundExceptionTo404() {
    when(this.facade.getGemspec(any(), eq("demo"), eq("1.2.3")))
        .thenThrow(new ItemNotFoundException("gemVersionNotFound"));

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.2.3.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
