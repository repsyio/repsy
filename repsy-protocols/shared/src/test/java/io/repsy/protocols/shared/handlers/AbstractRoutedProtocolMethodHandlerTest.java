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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.atomic.AtomicInteger;
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
            .requireAuthentication(true)
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
                HandlerPropertyKeys.REQUIRE_AUTHENTICATION, true,
                HandlerPropertyKeys.METHOD, "upload"));
  }

  @Test
  @DisplayName("without additional properties getProperties is the route's own map")
  void noAdditionalProperties() {
    final var route = HandlerRoute.write(HttpMethod.PUT);

    assertThat(new Handler(route, this.provider).getProperties()).isSameAs(route.properties());
  }

  private Handler handlerAdding(final HandlerRoute route, final Map<String, Object> additional) {
    return new Handler(route, this.provider) {
      @Override
      protected Map<String, Object> additionalProperties() {
        return additional;
      }
    };
  }

  @Test
  @DisplayName("additional properties add keys the route does not define")
  void addsKeys() {
    final var route = HandlerRoute.of(Permission.MANAGE, HttpMethod.DELETE).writeOperation(true);
    final var handler =
        this.handlerAdding(route, Map.of(HandlerPropertyKeys.METHOD, "chartDelete"));

    assertThat(handler.getProperties())
        .isEqualTo(
            Map.of(
                HandlerPropertyKeys.PERMISSION,
                Permission.MANAGE,
                HandlerPropertyKeys.WRITE_OPERATION,
                true,
                HandlerPropertyKeys.METHOD,
                "chartDelete"));
    assertThatThrownBy(() -> handler.getProperties().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(handler.getSupportedMethods()).containsExactly(HttpMethod.DELETE);
    assertThat(route.properties()).doesNotContainKey(HandlerPropertyKeys.METHOD);
  }

  @Test
  @DisplayName("additional properties may replace the permission with a stronger one")
  void strengthensPermission() {
    final var route = HandlerRoute.of(Permission.WRITE, HttpMethod.DELETE).writeOperation(true);
    final var handler =
        this.handlerAdding(
            route,
            Map.of(
                HandlerPropertyKeys.PERMISSION,
                Permission.MANAGE,
                HandlerPropertyKeys.METHOD,
                "chartDelete"));

    assertThat(handler.getProperties())
        .containsEntry(HandlerPropertyKeys.PERMISSION, Permission.MANAGE)
        .containsEntry(HandlerPropertyKeys.WRITE_OPERATION, true)
        .containsEntry(HandlerPropertyKeys.METHOD, "chartDelete");
    assertThat(route.properties()).containsEntry(HandlerPropertyKeys.PERMISSION, Permission.WRITE);
  }

  @Test
  @DisplayName("replacing the permission with the same one is allowed")
  void samePermission() {
    final var handler =
        this.handlerAdding(
            HandlerRoute.of(Permission.MANAGE, HttpMethod.DELETE),
            Map.of(HandlerPropertyKeys.PERMISSION, Permission.MANAGE));

    assertThat(handler.getProperties())
        .containsEntry(HandlerPropertyKeys.PERMISSION, Permission.MANAGE);
  }

  @Test
  @DisplayName("replacing the permission with a weaker one throws, naming handler and key")
  void weakerPermissionThrows() {
    final var handler =
        this.handlerAdding(
            HandlerRoute.of(Permission.MANAGE, HttpMethod.DELETE),
            Map.of(HandlerPropertyKeys.PERMISSION, Permission.READ));

    assertThatThrownBy(handler::getProperties)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(handler.getClass().getName())
        .hasMessageContaining("'permission'")
        .hasMessageContaining("MANAGE")
        .hasMessageContaining("READ");
  }

  @Test
  @DisplayName("NONE is the weakest permission, so replacing READ with NONE throws")
  void nonePermissionIsWeakest() {
    final var handler =
        this.handlerAdding(
            HandlerRoute.read(HttpMethod.GET),
            Map.of(HandlerPropertyKeys.PERMISSION, Permission.NONE));

    assertThatThrownBy(handler::getProperties).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a permission that is not a Permission value throws")
  void permissionOfWrongTypeThrows() {
    final var handler =
        this.handlerAdding(
            HandlerRoute.read(HttpMethod.GET), Map.of(HandlerPropertyKeys.PERMISSION, "manage"));

    assertThatThrownBy(handler::getProperties)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'permission'");
  }

  @Test
  @DisplayName("replacing a route key other than the permission throws, naming handler and key")
  void replacingOtherRouteKeyThrows() {
    final var route =
        HandlerRoute.write(HttpMethod.PUT)
            .skipPreProcessor(false)
            .skipHeaderPreProcessor(false)
            .skipUsagePostProcessor(false)
            .requireAuthentication(true)
            .method("upload");

    for (final var key :
        List.of(
            HandlerPropertyKeys.WRITE_OPERATION,
            HandlerPropertyKeys.SKIP_PRE_PROCESSOR,
            HandlerPropertyKeys.SKIP_HEADER_PRE_PROCESSOR,
            HandlerPropertyKeys.SKIP_USAGE_POST_PROCESSOR,
            HandlerPropertyKeys.REQUIRE_AUTHENTICATION,
            HandlerPropertyKeys.METHOD)) {
      final var handler = this.handlerAdding(route, Map.of(key, "other"));

      assertThatThrownBy(handler::getProperties)
          .as(key)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(handler.getClass().getName())
          .hasMessageContaining("'" + key + "'");
    }
  }

  @Test
  @DisplayName("the resolved properties are computed once and cached")
  void resolvedOnce() {
    final var calls = new AtomicInteger();
    final var handler =
        new Handler(HandlerRoute.read(HttpMethod.GET), this.provider) {
          @Override
          protected Map<String, Object> additionalProperties() {
            calls.incrementAndGet();
            return Map.of(HandlerPropertyKeys.METHOD, "download");
          }
        };

    assertThat(handler.getProperties()).isSameAs(handler.getProperties());
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("head properties follow the same rules: add keys, strengthen the permission")
  void headPropertiesAddAndStrengthen() {
    final var route = HandlerRoute.read(HttpMethod.GET).head();
    final var handler =
        this.handlerAdding(
            route,
            Map.of(
                HandlerPropertyKeys.PERMISSION,
                Permission.MANAGE,
                HandlerPropertyKeys.METHOD,
                "download"));

    assertThat(handler.getHeadProperties())
        .containsEntry(HandlerPropertyKeys.PERMISSION, Permission.MANAGE)
        .containsEntry(HandlerPropertyKeys.METHOD, "download")
        .containsEntry(HandlerPropertyKeys.SKIP_USAGE_POST_PROCESSOR, true);
    assertThat(route.headProperties()).doesNotContainKey(HandlerPropertyKeys.METHOD);
    assertThat(handler.getHeadProperties()).isSameAs(handler.getHeadProperties());
  }

  @Test
  @DisplayName("head properties: a weaker permission or another replaced key throws")
  void headPropertiesRejectReplacement() {
    final var route =
        HandlerRoute.of(Permission.MANAGE, HttpMethod.GET)
            .head(HandlerRoute.of(Permission.MANAGE, HttpMethod.HEAD).skipUsagePostProcessor(true));

    final var weaker =
        this.handlerAdding(route, Map.of(HandlerPropertyKeys.PERMISSION, Permission.READ));
    assertThatThrownBy(weaker::getHeadProperties)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(weaker.getClass().getName())
        .hasMessageContaining("'permission'");

    final var replaced =
        this.handlerAdding(route, Map.of(HandlerPropertyKeys.SKIP_USAGE_POST_PROCESSOR, false));
    assertThatThrownBy(replaced::getHeadProperties)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(replaced.getClass().getName())
        .hasMessageContaining("'skipUsagePostProcessor'");

    final var requireAuth =
        this.handlerAdding(
            HandlerRoute.read(HttpMethod.GET).requireAuthentication(true).head(),
            Map.of(HandlerPropertyKeys.REQUIRE_AUTHENTICATION, false));
    assertThatThrownBy(requireAuth::getProperties).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("without additional properties head properties are the route's own maps")
  void headPropertiesWithoutHook() {
    final var withHead = HandlerRoute.read(HttpMethod.GET).head();
    final var withoutHead = HandlerRoute.read(HttpMethod.GET);

    assertThat(new Handler(withHead, this.provider).getHeadProperties())
        .isSameAs(withHead.headProperties());
    assertThat(new Handler(withoutHead, this.provider).getHeadProperties())
        .isSameAs(withoutHead.properties());
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
