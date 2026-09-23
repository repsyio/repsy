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
package io.repsy.protocols.pypi.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractPypiHeadProtocolMethodHandler")
class AbstractPypiHeadProtocolMethodHandlerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "pypi";

  @Mock private PathParser pathParser;
  @Mock private PypiProtocolFacade<UUID> facade;
  @Mock private PypiProtocolProvider provider;

  private static class TestHandler extends AbstractPypiHeadProtocolMethodHandler<UUID> {

    private final @Nullable URI normalizedUri;

    TestHandler(
        final PathParser pathParser,
        final PypiProtocolFacade<UUID> facade,
        final PypiProtocolProvider provider,
        final @Nullable URI normalizedUri) {
      super(pathParser, facade, provider);
      this.normalizedUri = normalizedUri;
    }

    @Override
    protected @Nullable URI getNormalizedUri(
        final HttpServletRequest request, final @Nullable String packageName) {
      return this.normalizedUri;
    }
  }

  private TestHandler handler() {
    return this.handler(null);
  }

  private TestHandler handler(final @Nullable URI normalizedUri) {
    return new TestHandler(this.pathParser, this.facade, this.provider, normalizedUri);
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

  @Test
  @DisplayName("/simple/ answers 200 without touching the facade's lookup methods")
  void simpleRootAlwaysExists() {
    final var response =
        this.handler()
            .handle(
                contextFor("/simple/"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("/simple answers 200 too (trailing slash optional)")
  void simpleRootWithoutTrailingSlashAlwaysExists() {
    final var response =
        this.handler()
            .handle(
                contextFor("/simple"), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("/simple/<project>/ answers 200 when the package exists")
  void projectPageExisting() {
    when(this.facade.packageExists(any(), eq("demo"))).thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/simple/demo/"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("/simple/<project>/ answers 404 when the package does not exist")
  void projectPageMissing() {
    when(this.facade.packageExists(any(), eq("missing"))).thenReturn(false);

    final var response =
        this.handler()
            .handle(
                contextFor("/simple/missing/"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
  }

  @Test
  @DisplayName(
      "/simple/<non-normalized-project>/ mirrors GET's 307 redirect instead of checking existence"
          + " against the raw name")
  void projectPageNonNormalizedNameRedirects() {
    final var target = URI.create("http://localhost/pypi/simple/my-pkg/");

    final var response =
        this.handler(target)
            .handle(
                contextFor("/simple/My_Pkg/"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
    assertThat(response.getHeaders().getLocation()).isEqualTo(target);
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("/<project>/-/<file> answers 200 when the archive file exists")
  void archiveFileExisting() {
    when(this.facade.archiveFileExists(any(), eq("demo"), eq("demo-1.0.0.tar.gz")))
        .thenReturn(true);

    final var response =
        this.handler()
            .handle(
                contextFor("/demo/-/demo-1.0.0.tar.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("/<project>/-/<file> answers 404 when the archive file does not exist")
  void archiveFileMissing() {
    when(this.facade.archiveFileExists(any(), eq("demo"), eq("no-such-file.tar.gz")))
        .thenReturn(false);

    final var response =
        this.handler()
            .handle(
                contextFor("/demo/-/no-such-file.tar.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("an unrecognized path answers 404 without touching the facade")
  void unknownPathAnswers404() {
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
  @DisplayName("uses the injected path parser as its own, and registers with the provider")
  void usesInjectedPathParser() {
    final var handler = this.handler();

    assertThat(handler.getPathParser()).isSameAs(this.pathParser);
    verify(this.provider).registerMethodHandler(handler);
  }
}
