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
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("The HEAD of the Ruby index, gem and gemspec handlers (RPS-2059)")
class RubyHeadFallbackTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "gems";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static final class Info extends AbstractRubyCompactIndexInfoProtocolMethodHandler {
    Info(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static final class Names extends AbstractRubyCompactIndexNamesProtocolMethodHandler {
    Names(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static final class Versions
      extends AbstractRubyCompactIndexVersionsProtocolMethodHandler {
    Versions(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static final class Specs extends AbstractRubySpecsIndexProtocolMethodHandler {
    Specs(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static final class Gem extends AbstractRubyGemDownloadProtocolMethodHandler {
    Gem(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  private static final class Gemspec extends AbstractRubyGemspecProtocolMethodHandler {
    Gemspec(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  /**
   * Picks the handler the route predicates would pick and asks it for the HEAD; a path no handler
   * owns is the router's own 404 ("unknownPath").
   */
  private static final class TestHandler {

    private final List<ProtocolMethodHandler> all;
    private final Info info;
    private final Names names;
    private final Versions versions;
    private final Specs specs;
    private final Gem gem;
    private final Gemspec gemspec;

    TestHandler(final PathParser p, final RubyProtocolFacade f, final RubyProtocolProvider pr) {
      this.info = new Info(p, f, pr);
      this.names = new Names(p, f, pr);
      this.versions = new Versions(p, f, pr);
      this.specs = new Specs(p, f, pr);
      this.gem = new Gem(p, f, pr);
      this.gemspec = new Gemspec(p, f, pr);
      this.all = List.of(this.info, this.names, this.versions, this.specs, this.gem, this.gemspec);
    }

    List<ProtocolMethodHandler> all() {
      return this.all;
    }

    ResponseEntity<Object> handle(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      final var path = ProtocolContextUtils.getRelativePath(context).getPath();
      final ProtocolMethodHandler handler;

      if (path.equals("/names")) {
        handler = this.names;
      } else if (path.equals("/versions")) {
        handler = this.versions;
      } else if (path.endsWith("specs.4.8.gz")) {
        handler = this.specs;
      } else if (path.startsWith("/info/")) {
        handler = this.info;
      } else if (path.startsWith("/gems/") && path.endsWith(".gem")) {
        handler = this.gem;
      } else if (path.startsWith("/quick/Marshal.4.8/") && path.endsWith(".gemspec.rz")) {
        handler = this.gemspec;
      } else {
        return ResponseEntity.notFound().build();
      }

      try {
        return handler.handleHead(context, request, response);
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      }
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

  @ParameterizedTest
  @MethodSource("alwaysExistingPaths")
  @DisplayName("an index path answers the header its GET sends (RPS-1442)")
  void indexPathMirrorsGetHeader(final String path) throws Exception {
    final var response =
        this.handler()
            .handle(contextFor(path), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo(RubyContentDisposition.forPath(path));
  }

  @Test
  @DisplayName("/info/<gem>, .gem and .gemspec.rz answer the header their GET sends (RPS-1442)")
  void existingFilesMirrorGetHeader() throws Exception {
    when(this.facade.gemExists(any(), eq("demo.rb"))).thenReturn(true);
    when(this.facade.gemFileExists(any(), eq("demo-1.0.0.gem"))).thenReturn(true);
    when(this.facade.gemspecExists(any(), eq("demo-1.0.0"))).thenReturn(true);

    assertThat(this.headOf("/info/demo.rb")).isEqualTo("inline");
    assertThat(this.headOf("/gems/demo-1.0.0.gem"))
        .isEqualTo("attachment; filename=\"demo-1.0.0.gem\"");
    assertThat(this.headOf("/quick/Marshal.4.8/demo-1.0.0.gemspec.rz"))
        .isEqualTo("attachment; filename=\"demo-1.0.0.gemspec.rz\"");
  }

  @Test
  @DisplayName("a missing gem sends no Content-Disposition")
  void missingSendsNoHeader() throws Exception {
    when(this.facade.gemFileExists(any(), eq("demo-1.0.0.gem"))).thenReturn(false);

    assertThat(this.headOf("/gems/demo-1.0.0.gem")).isNull();
  }

  @Test
  @DisplayName("every existing path answers the Content-Type its GET sends (RPS-1465)")
  void existingPathsMirrorGetContentType() throws Exception {
    when(this.facade.gemExists(any(), eq("demo"))).thenReturn(true);
    when(this.facade.gemFileExists(any(), eq("demo-1.0.0.gem"))).thenReturn(true);
    when(this.facade.gemspecExists(any(), eq("demo-1.0.0"))).thenReturn(true);

    assertThat(this.contentTypeOf("/names")).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(this.contentTypeOf("/versions")).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(this.contentTypeOf("/info/demo")).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(this.contentTypeOf("/gems/demo-1.0.0.gem"))
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(this.contentTypeOf("/quick/Marshal.4.8/demo-1.0.0.gemspec.rz"))
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(this.contentTypeOf("/specs.4.8.gz")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(this.contentTypeOf("/latest_specs.4.8.gz"))
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(this.contentTypeOf("/prerelease_specs.4.8.gz"))
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
  }

  private @Nullable MediaType contentTypeOf(final String path) throws Exception {
    return this.handler()
        .handle(contextFor(path), new MockHttpServletRequest(), new MockHttpServletResponse())
        .getHeaders()
        .getContentType();
  }

  private String headOf(final String path) throws Exception {
    return this.handler()
        .handle(contextFor(path), new MockHttpServletRequest(), new MockHttpServletResponse())
        .getHeaders()
        .getFirst(HttpHeaders.CONTENT_DISPOSITION);
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
    when(this.facade.gemspecExists(any(), eq("demo-1.0.0"))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/demo-1.0.0.gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).gemspecExists(any(), eq("demo-1.0.0"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "demo-1.0.0-java",
        "demo-1.0.0-x86_64-linux",
        "demo-1.0.0-arm64-darwin",
        "demo-1.0.0-universal-darwin",
        "foo-2fa-1.0.0-x86_64-linux",
      })
  @DisplayName("a gemspec of a multi-segment platform is resolved by its whole name (RPS-1553)")
  void gemspecOfPlatformGem(final String gemspecName) throws Exception {
    when(this.facade.gemspecExists(any(), eq(gemspecName))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/quick/Marshal.4.8/" + gemspecName + ".gemspec.rz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).gemspecExists(any(), eq(gemspecName));
  }

  @Test
  @DisplayName("/quick/Marshal.4.8/<file>.gemspec.rz answers 404 when the version does not resolve")
  void gemspecMissing() throws Exception {
    when(this.facade.gemspecExists(any(), eq("demo-9.9.9"))).thenReturn(false);

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
  @DisplayName("they answer HEAD, need READ and do not bill a download")
  void answerHeadWithoutBilling() {
    for (final var handler : this.handler().all()) {
      assertThat(handler.answersHead()).isTrue();
      assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.GET);
      assertThat(handler.getHeadProperties())
          .isEqualTo(
              Map.of(
                  "permission", Permission.READ,
                  "writeOperation", false,
                  "skipUsagePostProcessor", true));
    }
  }
}
