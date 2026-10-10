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
package io.repsy.protocols.cargo.protocol.handlers;

import static io.repsy.protocols.cargo.protocol.handlers.CargoHandlerTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("The HEAD of the Cargo download and sparse index handlers (RPS-1465, RPS-2059)")
class CargoHeadFallbackTest {

  private static final String DOWNLOAD_PATH = "/api/v1/crates/serde/1.0.0/download";

  @Mock private PathParser basePathParser;
  @Mock private CargoProtocolFacade facade;
  @Mock private CargoProtocolProvider provider;

  private AbstractCargoDownloadProtocolMethodHandler download;
  private AbstractCargoSparseIndexProtocolMethodHandler sparse;

  @BeforeEach
  void setUp() {
    download = new TestDownload(basePathParser, facade, provider);
    sparse = new TestSparse(basePathParser, facade, provider);
  }

  static class TestDownload extends AbstractCargoDownloadProtocolMethodHandler {

    TestDownload(final PathParser p, final CargoProtocolFacade f, final CargoProtocolProvider pr) {
      super(p, f, pr);
    }
  }

  static class TestSparse extends AbstractCargoSparseIndexProtocolMethodHandler {

    TestSparse(final PathParser p, final CargoProtocolFacade f, final CargoProtocolProvider pr) {
      super(p, f, new ObjectMapper(), pr);
    }
  }

  /** What the router asks: the download handler first, then the sparse index. */
  private Optional<ProtocolContext> parse(final MockHttpServletRequest request) {
    final var parsed = download.getPathParser().parse(request);

    return parsed.isPresent() ? parsed : sparse.getPathParser().parse(request);
  }

  @Test
  @DisplayName("both answer HEAD, need READ and are not a billed download")
  void metadata() {
    final var expected =
        Map.of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);

    assertThat(download.answersHead()).isTrue();
    assertThat(sparse.answersHead()).isTrue();
    assertThat(download.getHeadProperties()).isEqualTo(expected);
    assertThat(sparse.getHeadProperties()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "recognises ''{0}''")
  @ValueSource(
      strings = {
        DOWNLOAD_PATH,
        "/1/a",
        "/2/ab",
        "/3/a/abc",
        "/se/rd/serde",
        "/config.json.bak/x/y",
      })
  @DisplayName("path parser: recognises the download and the sparse index paths")
  void pathParserAccepts(final String path) {
    final var request = new MockHttpServletRequest("HEAD", path);
    final var ctx = context(path);
    when(basePathParser.parse(request)).thenReturn(Optional.of(ctx));

    final var parsed = parse(request);

    if (path.startsWith("/config.json")) {
      assertThat(parsed).isEmpty();
    } else {
      assertThat(parsed).containsSame(ctx);
    }
  }

  @ParameterizedTest(name = "rejects ''{0}''")
  @ValueSource(
      strings = {
        "/config.json",
        "/api/v1/crates/serde/1.0.0/yank",
        "/api/v1/crates/serde/1.0.0/download/extra",
        "/api/v1/crates",
        "/me",
      })
  @DisplayName("path parser: rejects the paths of other routes")
  void pathParserRejects(final String path) {
    final var request = new MockHttpServletRequest("HEAD", path);
    when(basePathParser.parse(request)).thenReturn(Optional.of(context(path)));

    assertThat(parse(request)).isEmpty();
  }

  @Test
  @DisplayName("path parser: returns empty when the base parser does")
  void pathParserEmptyWhenBaseEmpty() {
    final var request = new MockHttpServletRequest("HEAD", DOWNLOAD_PATH);
    when(basePathParser.parse(request)).thenReturn(Optional.empty());

    assertThat(parse(request)).isEmpty();
  }

  @Test
  @DisplayName("a crate answers 200 with the headers of its GET and its length, without a download")
  void crateExists() {
    final var ctx = context(DOWNLOAD_PATH);
    when(facade.getCrate(ctx)).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));

    final var response =
        download.handleHead(
            ctx, new MockHttpServletRequest("HEAD", DOWNLOAD_PATH), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"serde-1.0.0.crate\"");
    verify(facade, org.mockito.Mockito.never()).download(ctx);
  }

  @Test
  @DisplayName("a missing crate answers 404 with no Content-Disposition")
  void crateMissing() {
    final var ctx = context(DOWNLOAD_PATH);
    when(facade.getCrate(ctx)).thenThrow(new ItemNotFoundException("crateNotFound"));

    final var response =
        download.handleHead(
            ctx, new MockHttpServletRequest("HEAD", DOWNLOAD_PATH), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
  }

  @Test
  @DisplayName("a sparse index file with entries answers 200 text/plain")
  void indexExists() {
    final var ctx = context("/se/rd/serde");
    when(facade.getIndexEntries(ctx))
        .thenReturn(List.of(org.mockito.Mockito.mock(CrateIndexEntry.class)));

    final var response =
        sparse.handleHead(
            ctx, new MockHttpServletRequest("HEAD", "/se/rd/serde"), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
  }

  @Test
  @DisplayName("a sparse index file without entries answers 404")
  void indexEmpty() {
    final var ctx = context("/se/rd/serde");
    when(facade.getIndexEntries(ctx)).thenReturn(List.of());

    final var response =
        sparse.handleHead(
            ctx, new MockHttpServletRequest("HEAD", "/se/rd/serde"), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("an unexpected sparse index failure propagates like on its GET")
  void indexLookupFails() {
    final var ctx = context("/se/rd/serde");
    when(facade.getIndexEntries(ctx)).thenThrow(new IllegalStateException("boom"));
    final var request = new MockHttpServletRequest("HEAD", "/se/rd/serde");

    assertThatThrownBy(() -> sparse.handleHead(ctx, request, new MockHttpServletResponse()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a sparse index lookup that finds no crate answers 404 like its GET")
  void indexLookupNotFound() {
    final var ctx = context("/se/rd/serde");
    when(facade.getIndexEntries(ctx)).thenThrow(new ItemNotFoundException("crateNotFound"));

    final var response =
        sparse.handleHead(
            ctx, new MockHttpServletRequest("HEAD", "/se/rd/serde"), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
