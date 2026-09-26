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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@DisplayName("AbstractGoHeadProtocolMethodHandler answers a HEAD like its GET (RPS-1465)")
class AbstractGoHeadProtocolMethodHandlerTest {

  private static final String MOD = "/example.com/mod/@v/";

  private final GoProtocolFacade<UUID> facade = mock();
  private final GolangProtocolProvider provider = mock();
  private final PathParser pathParser = mock();
  private AbstractGoHeadProtocolMethodHandler<UUID> handler;
  private AbstractGoDownloadProtocolMethodHandler<UUID> get;

  static class TestHead extends AbstractGoHeadProtocolMethodHandler<UUID> {

    TestHead(
        final PathParser parser,
        final GoProtocolFacade<UUID> facade,
        final GolangProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  static class TestGet extends AbstractGoDownloadProtocolMethodHandler<UUID> {

    TestGet(
        final PathParser parser,
        final GoProtocolFacade<UUID> facade,
        final GolangProtocolProvider provider) {
      super(parser, facade, provider);
    }
  }

  @BeforeEach
  void setUp() {
    this.handler = new TestHead(this.pathParser, this.facade, this.provider);
    this.get = new TestGet(this.pathParser, this.facade, this.provider);
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
  @DisplayName("registers itself, supports only HEAD, needs READ and is not a billed download")
  void metadata() {
    verify(this.provider).registerMethodHandler(this.handler);
    assertThat(this.handler.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
    assertThat(this.handler.getPathParser()).isSameAs(this.pathParser);
    assertThat(this.handler.getProperties())
        .containsEntry("permission", Permission.READ)
        .containsEntry("writeOperation", false)
        .containsEntry("skipUsagePostProcessor", true);
  }

  @ParameterizedTest
  @CsvSource({
    "v1.0.0.zip,application/octet-stream,'attachment; filename=\"v1.0.0.zip\"'",
    "v1.0.0.info,application/json,'inline; filename=\"v1.0.0.info\"'",
    "v1.0.0.mod,text/plain,'inline; filename=\"v1.0.0.mod\"'",
    "list,text/plain,",
  })
  @DisplayName("an existing file answers the headers of its GET and its Content-Length, no body")
  void existingFileMirrorsGet(final String file, final String type, final String disposition)
      throws Exception {
    final var context = context(MOD + file);
    when(this.facade.download(context)).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3, 4}));

    final var head = this.handler.handle(context, null, null);
    final var get = this.get.handle(context, null, null);

    assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(head.getBody()).isNull();
    assertThat(head.getHeaders().getContentLength()).isEqualTo(4);
    assertThat(head.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(type));
    assertThat(head.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo(disposition);
    assertThat(head.getHeaders().getContentType()).isEqualTo(get.getHeaders().getContentType());
    assertThat(head.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo(get.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1.0.0.zip", "v1.0.0.info", "v1.0.0.mod", "list"})
  @DisplayName("a missing file answers the 404 of its GET, without a body")
  void missingFileMirrorsGet(final String file) throws Exception {
    final var context = context(MOD + file);
    when(this.facade.download(context)).thenThrow(new ItemNotFoundException("itemNotFound"));

    final var head = this.handler.handle(context, null, null);
    final var get = this.get.handle(context, null, null);

    assertThat(head.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(head.getBody()).isNull();
    assertThat(head.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(head.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
    assertThat(head.getHeaders().getContentType()).isEqualTo(get.getHeaders().getContentType());
  }

  @Test
  @DisplayName("a resource that does not exist is the same 404")
  void missingResource() throws Exception {
    final var context = context(MOD + "v1.0.0.info");
    when(this.facade.download(context))
        .thenReturn(
            new ByteArrayResource(new byte[0]) {
              @Override
              public boolean exists() {
                return false;
              }
            });

    final var head = this.handler.handle(context, null, null);

    assertThat(head.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
