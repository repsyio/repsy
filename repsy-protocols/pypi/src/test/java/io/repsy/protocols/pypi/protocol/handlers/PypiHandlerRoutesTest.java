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
package io.repsy.protocols.pypi.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.facades.PypiProtocolFacade;
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
import org.springframework.mock.web.MockMultipartHttpServletRequest;

/**
 * Pins the registration (methods and processor properties) and the path matching of every pypi
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 */
@DisplayName("PyPI handlers keep their methods, properties and path matching")
class PypiHandlerRoutesTest {

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
  private static final PypiProtocolFacade<UUID> FACADE = mock(PypiProtocolFacade.class);

  private static final PypiProtocolProvider PROVIDER = mock(PypiProtocolProvider.class);

  @Test
  @DisplayName("file download")
  void fileDownload() {
    final var h =
        handler(AbstractPypiFileDownloadProtocolMethodHandler.class, FACADE, BASE, PROVIDER);
    assertRoute(
        h, List.of(HttpMethod.GET), Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(h, "GET", "/repo/foo/-/foo-1.0.tar.gz")).isPresent();
    assertThat(parse(h, "GET", "/repo/foo/bar/-/foo-1.0.tar.gz")).isEmpty();
    assertThat(parse(h, "GET", "/repo/simple/foo")).isEmpty();
    assertThat(parse(h, "POST", "/repo/foo/-/foo-1.0.tar.gz")).isEmpty();
  }

  @Test
  @DisplayName("simple index")
  void simple() {
    final var h = handler(AbstractPypiSimpleProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false, "method", "simple"));
    assertThat(parse(h, "GET", "/repo/simple")).isPresent();
    assertThat(parse(h, "GET", "/repo/simple/")).isPresent();
    assertThat(parse(h, "GET", "/repo/simple/foo")).isPresent();
    assertThat(parse(h, "GET", "/repo/simple/foo/")).isPresent();
    assertThat(parse(h, "GET", "/repo/simple/foo/bar")).isEmpty();
    assertThat(parse(h, "GET", "/repo/foo/-/foo-1.0.tar.gz")).isEmpty();
    assertThat(parse(h, "POST", "/repo/simple")).isEmpty();
  }

  @Test
  @DisplayName("simple and file download also answer HEAD, and only their own paths")
  void head() {
    final var simple =
        handler(AbstractPypiSimpleProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    final var file =
        handler(AbstractPypiFileDownloadProtocolMethodHandler.class, FACADE, BASE, PROVIDER);
    final var expected =
        Map.of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);

    assertThat(simple.answersHead()).isTrue();
    assertThat(simple.getHeadProperties()).isEqualTo(expected);
    assertThat(file.answersHead()).isTrue();
    assertThat(file.getHeadProperties()).isEqualTo(expected);
    assertThat(parse(simple, "HEAD", "/repo/simple/foo")).isPresent();
    assertThat(parse(simple, "HEAD", "/repo/foo/-/foo-1.0.tar.gz")).isEmpty();
    assertThat(parse(file, "HEAD", "/repo/foo/-/foo-1.0.tar.gz")).isPresent();
    assertThat(parse(file, "HEAD", "/repo/anything/else")).isEmpty();
  }

  @Test
  @DisplayName("upload answers only a multipart POST to the repository root")
  void upload() {
    final var h =
        handler(
            AbstractPypiPackageUploadProtocolMethodHandler.class,
            BASE,
            FACADE,
            (ProtocolProvider) PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.POST),
        Map.of("permission", Permission.WRITE, "writeOperation", true));

    final var parser = h.getPathParser();
    final var multipartRoot = new MockMultipartHttpServletRequest();
    multipartRoot.setMethod("POST");
    multipartRoot.setServletPath("/repo");
    assertThat(parser.parse(multipartRoot)).isPresent();

    final var multipartSlash = new MockMultipartHttpServletRequest();
    multipartSlash.setMethod("POST");
    multipartSlash.setServletPath("/repo/");
    assertThat(parser.parse(multipartSlash)).isPresent();

    final var multipartOther = new MockMultipartHttpServletRequest();
    multipartOther.setMethod("POST");
    multipartOther.setServletPath("/repo/simple");
    assertThat(parser.parse(multipartOther)).isEmpty();

    final var multipartGet = new MockMultipartHttpServletRequest();
    multipartGet.setMethod("GET");
    multipartGet.setServletPath("/repo");
    assertThat(parser.parse(multipartGet)).isEmpty();

    assertThat(parse(h, "POST", "/repo")).isEmpty();
  }
}
