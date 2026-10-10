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
package io.repsy.protocols.shared.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName(
    "AbstractRoutedProtocolMethodHandler builds registration and path parsing from the route")
class AbstractRoutedProtocolMethodHandlerTest {

  private static final PathParser BASE =
      request -> {
        final var props =
            BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
                .repoName("r")
                .relativePath(new RelativePath(request.getRequestURI()))
                .repoInfo(BaseRepoInfo.<UUID>builder().name("r").build())
                .build();
        final var context = new ProtocolContext();
        context.addProperty("urlProperties", props);
        return Optional.of(context);
      };

  private final ProtocolProvider provider = mock(ProtocolProvider.class);

  private static class Handler extends AbstractFacadeProtocolMethodHandler<String> {

    Handler(final HandlerRoute route, final ProtocolProvider provider) {
      super(route, BASE, "facade", provider);
    }

    @Override
    public ResponseEntity<Object> handle(
        final ProtocolContext parsedPath,
        final HttpServletRequest request,
        final HttpServletResponse response) {
      return ResponseEntity.ok().build();
    }
  }

  @Test
  @DisplayName("registers itself and exposes the methods and properties of the route")
  void registrationAndProperties() {
    final var route =
        HandlerRoute.write(HttpMethod.PUT, HttpMethod.DELETE)
            .skipPreProcessor(true)
            .skipHeaderPreProcessor(false)
            .skipUsagePostProcessor(true)
            .method("upload");

    final var handler = new Handler(route, this.provider);

    verify(this.provider).registerMethodHandler(handler);
    assertThat(handler.facade).isEqualTo("facade");
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.PUT, HttpMethod.DELETE);
    assertThat(handler.getProperties())
        .isEqualTo(
            Map.of(
                HandlerPropertyKeys.PERMISSION, Permission.WRITE,
                HandlerPropertyKeys.WRITE_OPERATION, true,
                HandlerPropertyKeys.SKIP_PRE_PROCESSOR, true,
                HandlerPropertyKeys.SKIP_HEADER_PRE_PROCESSOR, false,
                HandlerPropertyKeys.SKIP_USAGE_POST_PROCESSOR, true,
                HandlerPropertyKeys.METHOD, "upload"));
  }

  @Test
  @DisplayName("a key the route never set stays absent")
  void absentKeys() {
    final var route = HandlerRoute.of(Permission.NONE, HttpMethod.GET);

    assertThat(route.properties()).containsOnlyKeys(HandlerPropertyKeys.PERMISSION);
    assertThat(route.methods()).isEqualTo(List.of(HttpMethod.GET));
  }

  @Test
  @DisplayName("the path parser checks the method, then the relative path")
  void pathParser() {
    final var route = HandlerRoute.read(HttpMethod.GET).path(path -> path.endsWith("/a"));
    final var parser = new Handler(route, this.provider).getPathParser();

    assertThat(parser.parse(new MockHttpServletRequest("GET", "/x/a"))).isPresent();
    assertThat(parser.parse(new MockHttpServletRequest("GET", "/x/b"))).isEmpty();
    assertThat(parser.parse(new MockHttpServletRequest("POST", "/x/a"))).isEmpty();
  }

  @Test
  @DisplayName("without a path test the base parser alone decides")
  void noPathTest() {
    final var parser =
        new Handler(HandlerRoute.read(HttpMethod.GET), this.provider).getPathParser();

    assertThat(parser.parse(new MockHttpServletRequest("GET", "/anything"))).isPresent();
  }
}
