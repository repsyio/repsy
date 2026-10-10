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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.ruby.protocol.RubyProtocolProvider;
import io.repsy.protocols.ruby.protocol.facades.contracts.RubyProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the registration (method, properties) and the path matching of every Ruby handler, as they
 * were before the handlers were built from a {@code HandlerRoute} (RPS-2057).
 */
@DisplayName("Ruby handlers keep their method, properties and path matching")
class RubyHandlerRoutesTest {

  private static final PathParser BASE = request -> Optional.of(context(request.getRequestURI()));
  private static final RubyProtocolFacade FACADE = mock(RubyProtocolFacade.class);
  private static final RubyProtocolProvider PROVIDER = mock(RubyProtocolProvider.class);

  private static final Map<String, Object> READ =
      Map.of("permission", Permission.READ, "writeOperation", false);
  private static final Map<String, Object> WRITE =
      Map.of("permission", Permission.WRITE, "writeOperation", true);

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder().storageKey(UUID.randomUUID()).name("r").build();
    final var props =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("r")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", props);
    return ctx;
  }

  private static <T extends ProtocolMethodHandler> T handler(final Class<T> type) {
    return mock(
        type,
        withSettings().useConstructor(BASE, FACADE, PROVIDER).defaultAnswer(CALLS_REAL_METHODS));
  }

  private static boolean matches(
      final ProtocolMethodHandler handler, final String method, final String path) {
    return handler.getPathParser().parse(new MockHttpServletRequest(method, path)).isPresent();
  }

  private static void assertRoute(
      final ProtocolMethodHandler handler,
      final HttpMethod method,
      final Map<String, Object> properties,
      final String matchingPath,
      final String otherPath) {
    assertThat(handler.getSupportedMethods()).containsExactly(method);
    assertThat(handler.getProperties()).isEqualTo(properties);
    assertThat(matches(handler, method.name(), matchingPath)).isTrue();
    assertThat(matches(handler, method.name(), otherPath)).isFalse();
    final var wrongMethod = method == HttpMethod.GET ? "POST" : "GET";
    assertThat(matches(handler, wrongMethod, matchingPath)).isFalse();
  }

  @Test
  @DisplayName("the compact index (info, names, versions) and the dependencies API")
  void indexes() {
    assertRoute(
        handler(AbstractRubyCompactIndexInfoProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/info/rails",
        "/info");
    assertRoute(
        handler(AbstractRubyCompactIndexNamesProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/names",
        "/names/x");
    assertRoute(
        handler(AbstractRubyCompactIndexVersionsProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/versions",
        "/versions/x");
    assertRoute(
        handler(AbstractRubyDependenciesProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/api/v1/dependencies",
        "/api/v1/dependencies/x");
    assertRoute(
        handler(AbstractRubySpecsIndexProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/latest_specs.4.8.gz",
        "/other.gz");
  }

  @Test
  @DisplayName("gem download and gemspec")
  void downloads() {
    assertRoute(
        handler(AbstractRubyGemDownloadProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/gems/rails-7.0.gem",
        "/gems/rails-7.0.zip");
    assertRoute(
        handler(AbstractRubyGemspecProtocolMethodHandler.class),
        HttpMethod.GET,
        READ,
        "/quick/Marshal.4.8/rails-7.0.gemspec.rz",
        "/quick/rails.gemspec.rz");
  }

  @Test
  @DisplayName("publish and yank")
  void writes() {
    assertThat(PROVIDER).isNotNull();
    final var publish =
        mock(
            AbstractRubyGemPublishProtocolMethodHandler.class,
            withSettings()
                .useConstructor(BASE, FACADE, PROVIDER, 1024L)
                .defaultAnswer(CALLS_REAL_METHODS));
    assertRoute(publish, HttpMethod.POST, WRITE, "/api/v1/gems", "/api/v1/gems/yank");
    assertRoute(
        handler(AbstractRubyGemYankProtocolMethodHandler.class),
        HttpMethod.DELETE,
        WRITE,
        "/api/v1/gems/yank",
        "/api/v1/gems");
  }

  @Test
  @DisplayName("head is a read that is not counted, and uses the parser it is given")
  void head() {
    final var h = handler(AbstractRubyHeadProtocolMethodHandler.class);
    assertThat(h.getSupportedMethods()).containsExactly(HttpMethod.HEAD);
    assertThat(h.getProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "writeOperation",
                false,
                "skipUsagePostProcessor",
                true));
  }
}
