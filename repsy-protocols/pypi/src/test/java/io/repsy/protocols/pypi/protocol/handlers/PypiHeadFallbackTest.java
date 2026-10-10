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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("The HEAD of the PyPI simple and file download handlers (RPS-2059)")
class PypiHeadFallbackTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "pypi";

  @Mock private PathParser pathParser;
  @Mock private PypiProtocolFacade<UUID> facade;
  @Mock private PypiProtocolProvider provider;

  private static class TestSimple extends AbstractPypiSimpleProtocolMethodHandler<UUID> {

    private final @Nullable URI normalizedUri;

    TestSimple(
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

  private static class TestDownload extends AbstractPypiFileDownloadProtocolMethodHandler<UUID> {

    TestDownload(
        final PathParser pathParser,
        final PypiProtocolFacade<UUID> facade,
        final PypiProtocolProvider provider) {
      super(facade, pathParser, provider);
    }
  }

  /** Picks the handler the route predicates would pick, then asks it for the HEAD. */
  private static final class TestHandler {

    private final TestSimple simple;
    private final TestDownload download;

    TestHandler(final TestSimple simple, final TestDownload download) {
      this.simple = simple;
      this.download = download;
    }

    ResponseEntity<Object> handle(
        final ProtocolContext context,
        final HttpServletRequest request,
        final HttpServletResponse response)
        throws Exception {
      final var path = ProtocolContextUtils.getRelativePath(context).getPath();

      return path.startsWith("/simple")
          ? this.simple.handleHead(context, request, response)
          : this.download.handleHead(context, request, response);
    }
  }

  private TestHandler handler() {
    return this.handler(null);
  }

  private TestHandler handler(final @Nullable URI normalizedUri) {
    return new TestHandler(
        new TestSimple(this.pathParser, this.facade, this.provider, normalizedUri),
        new TestDownload(this.pathParser, this.facade, this.provider));
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
  void simpleRootAlwaysExists() throws Exception {
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
  void simpleRootWithoutTrailingSlashAlwaysExists() throws Exception {
    final var response =
        this.handler()
            .handle(
                contextFor("/simple"), new MockHttpServletRequest(), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verifyNoInteractions(this.facade);
  }

  @Test
  @DisplayName("/simple/<project>/ answers 200 when the package exists")
  void projectPageExisting() throws Exception {
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
  void projectPageMissing() throws Exception {
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
  void projectPageNonNormalizedNameRedirects() throws Exception {
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
  @DisplayName(
      "/<project>/-/<file> answers 200 with the Content-Length, Content-Type, Content-Disposition"
          + " and Accept-Ranges of the GET (RPS-1562)")
  void archiveFileExisting() throws Exception {
    final var bytes = new byte[] {1, 2, 3, 4, 5};
    when(this.facade.downloadArchiveFile(any(), eq("demo"), eq("demo-1.0.0-py3-none-any.whl")))
        .thenReturn(new ByteArrayResource(bytes));

    final var response =
        this.handler()
            .handle(
                contextFor("/demo/-/demo-1.0.0-py3-none-any.whl"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo-1.0.0-py3-none-any.whl\"");
    verify(this.facade, never()).archiveFileExists(any(), any(), any());
  }

  @Test
  @DisplayName("/<project>/-/<file> answers 404 when the archive file does not exist")
  void archiveFileMissing() throws Exception {
    when(this.facade.downloadArchiveFile(any(), eq("demo"), eq("no-such-file.tar.gz")))
        .thenThrow(new ItemNotFoundException("itemNotFound"));

    final var response =
        this.handler()
            .handle(
                contextFor("/demo/-/no-such-file.tar.gz"),
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
  }

  @Test
  @DisplayName("both answer HEAD, need READ and do not bill a download")
  void answersHeadWithoutBilling() {
    final var simple = new TestSimple(this.pathParser, this.facade, this.provider, null);
    final var download = new TestDownload(this.pathParser, this.facade, this.provider);

    assertThat(simple.answersHead()).isTrue();
    assertThat(download.answersHead()).isTrue();
    assertThat(simple.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission", Permission.READ,
                "writeOperation", false,
                "skipUsagePostProcessor", true));
    assertThat(download.getHeadProperties()).isEqualTo(simple.getHeadProperties());
  }

  @Test
  @DisplayName("the simple handler parses a HEAD like its GET, the other paths are not its own")
  void parsesHead() {
    final var simple = new TestSimple(this.pathParser, this.facade, this.provider, null);
    final var request = new MockHttpServletRequest("HEAD", "/simple/");
    final var context = contextFor("/simple/");
    when(this.pathParser.parse(request)).thenReturn(Optional.of(context));

    assertThat(simple.getPathParser().parse(request)).containsSame(context);
    assertThat(simple.getSupportedMethods()).containsExactly(HttpMethod.GET);
    verify(this.provider).registerMethodHandler(simple);
  }
}
