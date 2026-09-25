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
package io.repsy.protocols.golang.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@DisplayName("AbstractGoDownloadProtocolMethodHandler answers what the proxy lacks with 404")
class AbstractGoDownloadProtocolMethodHandlerTest {

  private static final String LIST_PATH = "/example.com/mod/@v/list";

  private final GoProtocolFacade<UUID> facade = mock();
  private AbstractGoDownloadProtocolMethodHandler<UUID> handler;

  static class TestHandler extends AbstractGoDownloadProtocolMethodHandler<UUID> {

    TestHandler(final GoProtocolFacade<UUID> facade) {
      super(mock(PathParser.class), facade, mock(GolangProtocolProvider.class));
    }
  }

  @BeforeEach
  void setUp() {
    this.handler = new TestHandler(this.facade);
  }

  private static ProtocolContext context(final String path) {
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("golang")
            .relativePath(new RelativePath(path))
            .repoInfo(BaseRepoInfo.<UUID>builder().id(UUID.randomUUID()).name("golang").build())
            .build();
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);

    return ctx;
  }

  @Test
  @DisplayName("an item the facade does not find is a 404 with a text/plain reason (RPS-1428)")
  void notFoundIsPlainText() {
    final var context = context(LIST_PATH);
    when(this.facade.download(context)).thenThrow(new ItemNotFoundException("itemNotFound"));

    final var response = this.handler.handle(context, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(response.getBody()).isEqualTo("not found: " + LIST_PATH);
  }

  @Test
  @DisplayName("a resource that does not exist is the same 404")
  void missingResourceIsPlainText() {
    final var context = context("/example.com/mod/@v/v1.0.0.info");
    when(this.facade.download(context))
        .thenReturn(
            new ByteArrayResource(new byte[0]) {
              @Override
              public boolean exists() {
                return false;
              }
            });

    final var response = this.handler.handle(context, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
  }

  @Test
  @DisplayName("a found list is a 200 text/plain body")
  void foundListIsOk() {
    final var context = context(LIST_PATH);
    when(this.facade.download(context)).thenReturn(new ByteArrayResource("v1.0.0".getBytes()));

    final var response = this.handler.handle(context, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
  }

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "/example.com/mod/@v/v1.0.0.zip, 'attachment; filename=\"v1.0.0.zip\"'",
    "/example.com/mod/@v/v1.0.0.info, 'inline; filename=\"v1.0.0.info\"'",
    "/example.com/mod/@v/v1.0.0.mod, 'inline; filename=\"v1.0.0.mod\"'",
    "/example.com/mod/@v/v1.2.3+incompatible.zip,"
        + " 'attachment; filename=\"v1.2.3+incompatible.zip\"'",
  })
  @DisplayName("the module files are named after themselves instead of f.txt (RPS-1389)")
  void namesTheFile(final String path, final String expected) {
    final var context = context(path);
    when(this.facade.download(context)).thenReturn(new ByteArrayResource(new byte[] {1}));

    final var response = this.handler.handle(context, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/example.com/mod/@v/list", "/example.com/mod/@latest"})
  @DisplayName("the version list and @latest have no file name and get no header")
  void listAndLatestHaveNoDisposition(final String path) {
    final var context = context(path);
    when(this.facade.download(context)).thenReturn(new ByteArrayResource(new byte[] {1}));

    final var response = this.handler.handle(context, null, null);

    assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
  }
}
