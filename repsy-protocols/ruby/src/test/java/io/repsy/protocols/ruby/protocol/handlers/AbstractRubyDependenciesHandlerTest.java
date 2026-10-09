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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyDependenciesHandler")
class AbstractRubyDependenciesHandlerTest {

  private static final String REPO_NAME = "demo-repo";
  private static final String PATH = "/api/v1/dependencies";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static class TestHandler extends AbstractRubyDependenciesHandler {

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
  @DisplayName("matches GET /api/v1/dependencies")
  void matchesDependenciesPath() {
    final var request = new MockHttpServletRequest("GET", PATH);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(contextFor(PATH)));

    assertThat(this.handler().getPathParser().parse(request)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/versions", "/names", "/api/v1/dependencies.json", "/api/v1/gems"})
  @DisplayName("rejects a GET path that is not exactly /api/v1/dependencies")
  void rejectsOtherPaths(final String path) {
    final var request = new MockHttpServletRequest("GET", path);
    when(this.basePathParser.parse(request)).thenReturn(Optional.of(contextFor(path)));

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  @Test
  @DisplayName("rejects a non-GET method without consulting the base parser")
  void rejectsNonGetMethod() {
    final var request = new MockHttpServletRequest("POST", PATH);

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
    verifyNoInteractions(this.basePathParser);
  }

  @Test
  @DisplayName("rejects when the base path parser finds no repo")
  void rejectsWhenBaseParserFindsNoRepo() {
    final var request = new MockHttpServletRequest("GET", PATH);
    when(this.basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(this.handler().getPathParser().parse(request)).isEmpty();
  }

  // --- handle() ---

  @Test
  @DisplayName("splits a comma-separated gems param and answers 200 octet-stream")
  void splitsGemsParam() {
    when(this.facade.getDependencies(any(), eq(List.of("rack", "rake"))))
        .thenReturn(new byte[] {0x04, 0x08, 0x5b, 0x00});
    final var request = new MockHttpServletRequest("GET", PATH);
    request.addParameter("gems", "rack,rake");

    final var response =
        this.handler().handle(contextFor(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    verify(this.facade).getDependencies(any(), eq(List.of("rack", "rake")));
  }

  @Test
  @DisplayName("trims whitespace around each requested gem name")
  void trimsGemNames() {
    when(this.facade.getDependencies(any(), eq(List.of("rack", "rake")))).thenReturn(new byte[0]);
    final var request = new MockHttpServletRequest("GET", PATH);
    request.addParameter("gems", " rack , rake ");

    this.handler().handle(contextFor(PATH), request, new MockHttpServletResponse());

    verify(this.facade).getDependencies(any(), eq(List.of("rack", "rake")));
  }

  @Test
  @DisplayName("a missing gems param resolves to an empty request, not an error")
  void missingGemsParamIsEmptyList() {
    when(this.facade.getDependencies(any(), eq(List.of()))).thenReturn(new byte[0]);
    final var request = new MockHttpServletRequest("GET", PATH);

    final var response =
        this.handler().handle(contextFor(PATH), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).getDependencies(any(), eq(List.of()));
  }

  @Test
  @DisplayName("a blank gems param resolves to an empty request")
  void blankGemsParamIsEmptyList() {
    when(this.facade.getDependencies(any(), eq(List.of()))).thenReturn(new byte[0]);
    final var request = new MockHttpServletRequest("GET", PATH);
    request.addParameter("gems", "   ");

    this.handler().handle(contextFor(PATH), request, new MockHttpServletResponse());

    verify(this.facade).getDependencies(any(), eq(List.of()));
  }

  @Test
  @DisplayName("returns the facade's bytes verbatim, with no gzip or deflate framing")
  void returnsBodyVerbatim() {
    final var raw = new byte[] {0x04, 0x08, 0x5b, 0x06, 0x7b, 0x00};
    when(this.facade.getDependencies(any(), eq(List.of("rack")))).thenReturn(raw);
    final var request = new MockHttpServletRequest("GET", PATH);
    request.addParameter("gems", "rack");

    final var response =
        this.handler().handle(contextFor(PATH), request, new MockHttpServletResponse());

    assertThat(response.getBody()).isEqualTo(raw);
  }
}
