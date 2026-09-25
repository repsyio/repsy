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
package io.repsy.protocols.maven.protocol.handlers;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.exceptions.RedirectToSlashEndedLocationException;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RPS-1368: a HEAD is answered like the GET of the same path, without the body, and both share the
 * response headers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractMavenHeadProtocolMethodHandler (RPS-1368)")
class AbstractMavenHeadProtocolMethodHandlerTest {

  @Mock private MavenProtocolProvider provider;
  @Mock private MavenProtocolFacade<UUID> facade;
  @Mock private PathParser pathParser;

  @TempDir private Path dir;

  private final ProtocolContext context = new ProtocolContext();

  private static class TestHead extends AbstractMavenHeadProtocolMethodHandler<UUID> {
    TestHead(
        final PathParser parser,
        final MavenProtocolFacade<UUID> facade,
        final MavenProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  private static class TestDownload extends AbstractMavenDownloadProtocolMethodHandler<UUID> {
    TestDownload(
        final PathParser parser,
        final MavenProtocolFacade<UUID> facade,
        final MavenProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  private static MockHttpServletRequest request(final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  private ResponseEntity<Object> head(final String path) throws Exception {
    return new TestHead(this.pathParser, this.facade, this.provider)
        .handle(this.context, request("HEAD", path), new MockHttpServletResponse());
  }

  private ResponseEntity<Object> get(final String path) throws Exception {
    return new TestDownload(this.pathParser, this.facade, this.provider)
        .handle(this.context, request("GET", path), new MockHttpServletResponse());
  }

  /** A file resource the way the storage hands it out: a lazy file, never opened. */
  private Resource file(final String name, final int length) throws IOException {
    final var path = Files.write(this.dir.resolve(name), new byte[length]);

    return new FileSystemResource(path);
  }

  /** A rendered directory listing: a plain {@link ByteArrayResource}. */
  private static Resource listing(final String html) {
    return new ByteArrayResource(html.getBytes(UTF_8));
  }

  @Test
  @DisplayName("registers for HEAD, needs read permission and is not a download")
  void registers() {
    final var handler = new TestHead(this.pathParser, this.facade, this.provider);

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
    assertThat(handler.getPathParser()).isSameAs(this.pathParser);
    assertThat(handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @Test
  @DisplayName("is 200 with the file's headers and its length, and no body, for a file")
  void file() throws Exception {
    when(this.facade.download(this.context)).thenReturn(file("demo-1.0.jar", 1234));

    final var response = this.head("/g/demo/1.0/demo-1.0.jar");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=demo-1.0.jar");
    assertThat(response.getHeaders().getContentLength()).isEqualTo(1234);
  }

  @Test
  @DisplayName("is 200 text/html with the length of the listing for a directory")
  void directory() throws Exception {
    when(this.facade.download(this.context)).thenReturn(listing("<html>demo</html>"));

    final var response = this.head("/g/demo/");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
    assertThat(response.getHeaders().getContentLength()).isEqualTo("<html>demo</html>".length());
  }

  @Test
  @DisplayName("is 404 for a path that was never published")
  void missing() throws Exception {
    when(this.facade.download(this.context)).thenThrow(new ItemNotFoundException("itemNotFound"));

    final var response = this.head("/g/nope/1.0/nope-1.0.pom");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNull();
  }

  @Test
  @DisplayName("is 307 to the slash-ended location for a directory without the slash")
  void redirect() throws Exception {
    when(this.facade.download(this.context)).thenThrow(new RedirectToSlashEndedLocationException());

    final var response = this.head("/repo/g/demo");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
    assertThat(response.getHeaders().getLocation()).hasToString("/repo/g/demo/");
  }

  @Test
  @DisplayName("lets any other failure through instead of answering it as a missing file")
  void otherFailure() {
    when(this.facade.download(this.context)).thenThrow(new IllegalStateException("boom"));

    assertThatThrownBy(() -> this.head("/g/demo/1.0/demo-1.0.jar"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("fails, instead of answering, when the size of the file cannot be read")
  void sizeFailure() throws Exception {
    final var broken = mock(Resource.class);
    when(broken.getFilename()).thenReturn("demo-1.0.jar");
    when(broken.contentLength()).thenThrow(new IOException("gone"));
    when(this.facade.download(this.context)).thenReturn(broken);

    assertThatThrownBy(() -> this.head("/g/demo/1.0/demo-1.0.jar")).isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("answers a GET with the same status and headers as the HEAD, plus the body")
  void getMirrorsHead() throws Exception {
    final var jar = file("demo-1.0.jar", 8);
    final var html = listing("<html/>");
    when(this.facade.download(this.context)).thenReturn(jar);

    final var head = this.head("/g/demo/1.0/demo-1.0.jar");
    final var get = this.get("/g/demo/1.0/demo-1.0.jar");

    assertThat(get.getStatusCode()).isEqualTo(head.getStatusCode());
    assertThat(get.getHeaders().getContentType()).isEqualTo(head.getHeaders().getContentType());
    assertThat(get.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo(head.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    assertThat(get.getBody()).isSameAs(jar);

    when(this.facade.download(this.context)).thenReturn(html);

    final var listingGet = this.get("/g/demo/");

    assertThat(listingGet.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(listingGet.getBody()).isSameAs(html);
  }

  @Test
  @DisplayName("answers a GET 404 and 307 like the HEAD")
  void getErrors() throws Exception {
    when(this.facade.download(this.context))
        .thenThrow(new ItemNotFoundException("itemNotFound"))
        .thenThrow(new RedirectToSlashEndedLocationException());

    assertThat(this.get("/repo/g/none").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    final var redirect = this.get("/repo/g/demo");
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
    assertThat(redirect.getHeaders().getLocation()).hasToString("/repo/g/demo/");
  }
}
