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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.maven.protocol.MavenProtocolProvider;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the registration (methods and processor properties) and the path matching of every maven
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 */
@DisplayName("Maven handlers keep their methods, properties and path matching")
class MavenHandlerRoutesTest {

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder()
            .storageKey(UUID.randomUUID())
            .name("repo")
            .privateRepo(false)
            .build();
    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("repo")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();
    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

  /** The relative path is the servlet path without the repository segment. */
  private static final PathParser BASE =
      request -> {
        final var servletPath = request.getServletPath();
        final var slash = servletPath.indexOf('/', 1);
        return Optional.of(context(slash < 0 ? "/" : servletPath.substring(slash)));
      };

  private static <T extends ProtocolMethodHandler> T handler(
      final Class<T> type, final Object... args) {
    return mock(type, withSettings().useConstructor(args).defaultAnswer(CALLS_REAL_METHODS));
  }

  private static MockHttpServletRequest request(final String method, final String path) {
    final var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  private static Optional<ProtocolContext> parse(
      final ProtocolMethodHandler handler, final String method, final String path) {
    return handler.getPathParser().parse(request(method, path));
  }

  private static void assertRoute(
      final ProtocolMethodHandler handler,
      final List<HttpMethod> methods,
      final Map<String, Object> properties) {
    assertThat(handler.getSupportedMethods()).containsExactlyElementsOf(methods);
    assertThat(handler.getProperties()).isEqualTo(properties);
  }

  @SuppressWarnings("unchecked")
  private static final MavenProtocolFacade<UUID> FACADE = mock(MavenProtocolFacade.class);

  private static final MavenProtocolProvider PROVIDER = mock(MavenProtocolProvider.class);

  @Test
  @DisplayName("download, head and upload hand every path of their method to the base parser")
  void routes() {
    final var download =
        handler(AbstractMavenDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        download,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false, "method", "download"));
    assertThat(parse(download, "GET", "/repo/com/acme/app/1.0/app-1.0.jar")).isPresent();
    assertThat(parse(download, "GET", "/repo/")).isPresent();

    final var head = handler(AbstractMavenHeadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        head,
        List.of(HttpMethod.HEAD),
        Map.of(
            "permission",
            Permission.READ,
            "writeOperation",
            false,
            "skipUsagePostProcessor",
            true));
    assertThat(parse(head, "HEAD", "/repo/com/acme/app/1.0/app-1.0.jar")).isPresent();

    final var upload =
        handler(AbstractMavenUploadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        upload,
        List.of(HttpMethod.PUT),
        Map.of("permission", Permission.WRITE, "writeOperation", true, "method", "upload"));
    assertThat(parse(upload, "PUT", "/repo/com/acme/app/1.0/app-1.0.jar")).isPresent();
  }

  @Test
  @DisplayName("an unknown repository (base parser answers empty) is never claimed")
  void unknownRepository() {
    final PathParser none = _ -> Optional.empty();
    final var download =
        handler(AbstractMavenDownloadProtocolMethodHandler.class, none, FACADE, PROVIDER);
    assertThat(parse(download, "GET", "/repo/a")).isEmpty();
  }
}
