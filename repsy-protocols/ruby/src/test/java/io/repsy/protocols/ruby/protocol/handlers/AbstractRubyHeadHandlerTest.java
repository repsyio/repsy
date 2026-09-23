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
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractRubyHeadHandler")
class AbstractRubyHeadHandlerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "gems";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static class TestHandler extends AbstractRubyHeadHandler {

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
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
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

  private static Stream<String> alwaysExistingPaths() {
    return Stream.of(
        "/versions", "/names", "/specs.4.8.gz", "/latest_specs.4.8.gz", "/prerelease_specs.4.8.gz");
  }

  @ParameterizedTest
  @MethodSource("alwaysExistingPaths")
  @DisplayName("index paths answer 200 without touching the facade's lookup methods")
  void indexPathsAlwaysExist(final String path) throws Exception {
    final var response =
        this.handler()
            .handle(contextFor(path), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("/info/<gem> answers 200 when the gem exists")
  void infoExisting() throws Exception {
    when(this.facade.gemExists(any(), eq("demo"))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/info/demo"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("/info/<gem> answers 404 when the gem does not exist")
  void infoMissing() throws Exception {
    when(this.facade.gemExists(any(), eq("missing"))).thenReturn(false);

    final var response =
        this.handler()
            .handle(
                contextFor("/info/missing"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
  }

  @Test
  @DisplayName("/gems/<file>.gem answers 200 when the filename resolves")
  void gemFileExisting() throws Exception {
    when(this.facade.gemFileExists(any(), eq("demo-1.0.0.gem"))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/gems/demo-1.0.0.gem"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("/gems/<file>.gem answers 404 when the filename does not resolve")
  void gemFileMissing() throws Exception {
    when(this.facade.gemFileExists(any(), eq("never-published-9.9.9.gem"))).thenReturn(false);

    final var response =
        this.handler()
            .handle(
                contextFor("/gems/never-published-9.9.9.gem"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("/quick/Marshal.4.8/<file>.gemspec.rz answers 200 when the version resolves")
  void gemspecExisting() throws Exception {
    when(this.facade.gemspecExists(any(), eq("demo"), eq("1.0.0"))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.0.0.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).gemspecExists(any(), eq("demo"), eq("1.0.0"));
  }

  @Test
  @DisplayName("/quick/Marshal.4.8/<file>.gemspec.rz answers 404 when the version does not resolve")
  void gemspecMissing() throws Exception {
    when(this.facade.gemspecExists(any(), eq("demo"), eq("9.9.9"))).thenReturn(false);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-9.9.9.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("an unrecognized path answers 404 without touching the facade")
  void unknownPathAnswers404() throws Exception {
    final var response =
        this.handler()
            .handle(
                contextFor("/this/path/never/existed"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("supports only HEAD, and does not bill a download")
  void supportsOnlyHead() {
    final var handler = this.handler();

    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
    assertThat(handler.getProperties()).containsEntry("skipUsagePostProcessor", true);
  }

  @Test
  @DisplayName("uses the injected base path parser as its own, and registers with the provider")
  void usesBasePathParser() {
    final var handler = this.handler();

    assertThat(handler.getPathParser()).isSameAs(this.basePathParser);
    verify(this.provider).registerMethodHandler(handler);
  }
}
