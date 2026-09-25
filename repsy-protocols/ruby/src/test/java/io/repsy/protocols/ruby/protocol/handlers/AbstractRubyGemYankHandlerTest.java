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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contract.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Optional;
import java.util.UUID;
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
@DisplayName("AbstractRubyGemYankHandler")
class AbstractRubyGemYankHandlerTest {

  private static final String YANK_PATH = "/api/v1/gems/yank";

  @Mock private PathParser basePathParser;
  @Mock private RubyProtocolFacade facade;
  @Mock private RubyProtocolProvider provider;

  private static class TestHandler extends AbstractRubyGemYankHandler {

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
    repoInfo.setName("demo-repo");

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("demo-repo")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build());
    return context;
  }

  @Test
  @DisplayName("yank is a WRITE, not MANAGE, so a read-write deploy token may yank (RPS-1317)")
  void yankIsAWriteOperation() {
    final var properties = this.handler().getProperties();

    assertThat(properties).containsEntry("permission", Permission.WRITE);
    assertThat(properties).containsEntry("writeOperation", true);
  }

  @Test
  @DisplayName("registers itself with the provider and serves DELETE only")
  void registersForDelete() {
    final var handler = this.handler();

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
  }

  @Test
  @DisplayName("matches DELETE /api/v1/gems/yank and nothing else")
  void matchesYankPathOnly() {
    final var yank = new MockHttpServletRequest("DELETE", YANK_PATH);
    when(this.basePathParser.parse(yank)).thenReturn(Optional.of(contextFor(YANK_PATH)));
    final var other = new MockHttpServletRequest("DELETE", "/api/v1/gems");
    when(this.basePathParser.parse(other)).thenReturn(Optional.of(contextFor("/api/v1/gems")));
    final var get = new MockHttpServletRequest("GET", YANK_PATH);

    final var parser = this.handler().getPathParser();

    assertThat(parser.parse(yank)).isPresent();
    assertThat(parser.parse(other)).isEmpty();
    assertThat(parser.parse(get)).isEmpty();
  }

  @Test
  @DisplayName("yanks the named version, defaulting the platform to ruby")
  void yanksWithDefaultPlatform() {
    final var request = new MockHttpServletRequest("DELETE", YANK_PATH);
    request.setParameter("gem_name", "demo");
    request.setParameter("version", "1.0.0");
    final var context = contextFor(YANK_PATH);

    final var result = this.handler().handle(context, request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(this.facade).yankGem(context, "demo", "1.0.0", "ruby");
  }

  @Test
  @DisplayName("refuses a yank without gem_name or version with 400")
  void refusesMissingParameters() {
    final var request = new MockHttpServletRequest("DELETE", YANK_PATH);
    request.setParameter("gem_name", "demo");

    final var result =
        this.handler().handle(contextFor(YANK_PATH), request, new MockHttpServletResponse());

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
