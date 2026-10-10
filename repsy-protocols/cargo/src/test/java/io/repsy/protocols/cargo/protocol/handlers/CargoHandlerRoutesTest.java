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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the registration (methods and processor properties) and the path matching of every cargo
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 */
@DisplayName("Cargo handlers keep their methods, properties and path matching")
class CargoHandlerRoutesTest {

  private static final PathParser BASE =
      request -> Optional.of(CargoHandlerTestSupport.context(request.getRequestURI()));
  private static final CargoProtocolFacade FACADE = mock(CargoProtocolFacade.class);
  private static final CargoProtocolProvider PROVIDER = mock(CargoProtocolProvider.class);

  private static <T extends ProtocolMethodHandler> T handler(
      final Class<T> type, final Object... args) {
    return mock(type, withSettings().useConstructor(args).defaultAnswer(CALLS_REAL_METHODS));
  }

  private static Optional<ProtocolContext> parse(
      final ProtocolMethodHandler handler, final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return handler.getPathParser().parse(request);
  }

  private static void assertRoute(
      final ProtocolMethodHandler handler,
      final List<HttpMethod> methods,
      final Map<String, Object> properties) {
    assertThat(handler.getSupportedMethods()).containsExactlyElementsOf(methods);
    assertThat(handler.getProperties()).isEqualTo(properties);
  }

  @Test
  @DisplayName("download")
  void download() {
    final var h = handler(AbstractCargoDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        h, List.of(HttpMethod.GET), Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(h, "GET", "/api/v1/crates/foo/1.0.0/download")).isPresent();
    assertThat(parse(h, "GET", "/api/v1/crates/foo")).isEmpty();
    assertThat(parse(h, "PUT", "/api/v1/crates/foo/1.0.0/download")).isEmpty();
  }

  @Test
  @DisplayName("download and sparse index also answer HEAD through the router fallback")
  void head() {
    final var download =
        handler(AbstractCargoDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    final var expected =
        Map.of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
    assertThat(download.answersHead()).isTrue();
    assertThat(download.getHeadProperties()).isEqualTo(expected);
    assertThat(parse(download, "HEAD", "/api/v1/crates/foo/1.0.0/download")).isPresent();
    assertThat(parse(download, "HEAD", "/api/v1/crates/foo")).isEmpty();
    assertThat(parse(download, "HEAD", "/1/a")).isEmpty();

    final var sparse =
        handler(
            AbstractCargoSparseIndexProtocolMethodHandler.class,
            BASE,
            FACADE,
            new ObjectMapper(),
            PROVIDER);
    assertThat(sparse.answersHead()).isTrue();
    assertThat(sparse.getHeadProperties()).isEqualTo(expected);
    assertThat(parse(sparse, "HEAD", "/1/a")).isPresent();
    assertThat(parse(sparse, "HEAD", "/config.json")).isEmpty();
    assertThat(parse(sparse, "HEAD", "/api/v1/crates/foo")).isEmpty();
  }

  @Test
  @DisplayName("owners list and modify")
  void owners() {
    final var list = handler(AbstractCargoOwnersListProtocolMethodHandler.class, BASE, PROVIDER);
    assertRoute(
        list,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(list, "GET", "/api/v1/crates/foo/owners")).isPresent();
    assertThat(parse(list, "PUT", "/api/v1/crates/foo/owners")).isEmpty();
    assertThat(parse(list, "GET", "/api/v1/crates/foo")).isEmpty();

    final var modify =
        handler(AbstractCargoOwnersModifyProtocolMethodHandler.class, BASE, PROVIDER);
    assertRoute(
        modify,
        List.of(HttpMethod.PUT, HttpMethod.DELETE),
        Map.of("permission", Permission.WRITE, "writeOperation", true));
    assertThat(parse(modify, "PUT", "/api/v1/crates/foo/owners")).isPresent();
    assertThat(parse(modify, "DELETE", "/api/v1/crates/foo/owners")).isPresent();
    assertThat(parse(modify, "GET", "/api/v1/crates/foo/owners")).isEmpty();
  }

  @Test
  @DisplayName("publish and search")
  void publishAndSearch() {
    final var publish =
        handler(AbstractCargoPublishProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        publish,
        List.of(HttpMethod.PUT),
        Map.of("permission", Permission.WRITE, "writeOperation", true));
    assertThat(parse(publish, "PUT", "/api/v1/crates/new")).isPresent();
    assertThat(parse(publish, "PUT", "/api/v1/crates")).isEmpty();
    assertThat(parse(publish, "GET", "/api/v1/crates/new")).isEmpty();

    final var search =
        handler(AbstractCargoSearchProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        search,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(search, "GET", "/api/v1/crates")).isPresent();
    assertThat(parse(search, "GET", "/api/v1/crates/new")).isEmpty();
    assertThat(parse(search, "PUT", "/api/v1/crates")).isEmpty();
  }

  @Test
  @DisplayName("sparse index")
  void sparseIndex() {
    final var h =
        handler(
            AbstractCargoSparseIndexProtocolMethodHandler.class,
            BASE,
            FACADE,
            new ObjectMapper(),
            PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.GET),
        Map.of(
            "permission",
            Permission.READ,
            "writeOperation",
            false,
            "skipPreProcessor",
            false,
            "skipHeaderPreProcessor",
            true));
    assertThat(parse(h, "GET", "/1/a")).isPresent();
    assertThat(parse(h, "GET", "/ab/cd/abcd")).isPresent();
    assertThat(parse(h, "GET", "/config.json")).isEmpty();
    assertThat(parse(h, "GET", "/api/v1/crates")).isEmpty();
    assertThat(parse(h, "PUT", "/1/a")).isEmpty();
  }

  @Test
  @DisplayName("yank")
  void yank() {
    final var h = handler(AbstractCargoYankProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.DELETE, HttpMethod.PUT),
        Map.of("permission", Permission.WRITE, "writeOperation", true));
    assertThat(parse(h, "DELETE", "/api/v1/crates/foo/1.0.0/yank")).isPresent();
    assertThat(parse(h, "PUT", "/api/v1/crates/foo/1.0.0/yank")).isEmpty();
    assertThat(parse(h, "PUT", "/api/v1/crates/foo/1.0.0/unyank")).isPresent();
    assertThat(parse(h, "DELETE", "/api/v1/crates/foo/1.0.0/unyank")).isEmpty();
    assertThat(parse(h, "GET", "/api/v1/crates/foo/1.0.0/yank")).isEmpty();
  }

  @Test
  @DisplayName("config and config HEAD answer only the servlet path ending in /config.json")
  void config() {
    final var get = handler(AbstractCargoConfigProtocolMethodHandler.class, BASE, PROVIDER);
    assertRoute(
        get,
        List.of(HttpMethod.GET),
        Map.of(
            "permission",
            Permission.READ,
            "skipHeaderPreProcessor",
            true,
            "skipUsagePostProcessor",
            true,
            "skipPreProcessor",
            true));
    assertThat(parse(get, "GET", "/config.json")).isPresent();
    assertThat(parse(get, "GET", "/other")).isEmpty();
    assertThat(parse(get, "PUT", "/config.json")).isEmpty();

    assertThat(get.answersHead()).isTrue();
    assertThat(get.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "skipUsagePostProcessor",
                true,
                "skipPreProcessor",
                true));
    assertThat(parse(get, "HEAD", "/config.json")).isPresent();
    assertThat(parse(get, "HEAD", "/other")).isEmpty();
  }

  @Test
  @DisplayName("me builds its own context for GET and HEAD of /me")
  void me() {
    final var h =
        handler(
            AbstractCargoMeProtocolMethodHandler.class,
            (AbstractCargoMeProtocolMethodHandler.CargoAuthenticator) header -> "token",
            PROVIDER);
    final var context = CargoHandlerTestSupport.context("/me");
    doReturn(Optional.of(context)).when(h).findProtocolContext(org.mockito.ArgumentMatchers.any());

    assertRoute(
        h,
        List.of(HttpMethod.GET, HttpMethod.HEAD),
        Map.of(
            "permission",
            Permission.NONE,
            "skipHeaderPreProcessor",
            true,
            "skipUsagePostProcessor",
            true,
            "skipPreProcessor",
            true));
    assertThat(parse(h, "GET", "/me")).containsSame(context);
    assertThat(parse(h, "HEAD", "/me")).containsSame(context);
    assertThat(parse(h, "GET", "/other")).isEmpty();
    assertThat(parse(h, "POST", "/me")).isEmpty();
  }
}
