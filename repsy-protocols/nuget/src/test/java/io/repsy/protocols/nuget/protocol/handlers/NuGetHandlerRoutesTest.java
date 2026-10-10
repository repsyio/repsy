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
package io.repsy.protocols.nuget.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.nuget.protocol.facades.contracts.NuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
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
 * Pins the registration (methods and processor properties) and the path matching of every NuGet
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 */
@DisplayName("NuGet handlers keep their methods, properties and path matching")
class NuGetHandlerRoutesTest {

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

  private static final NuGetProtocolFacade FACADE = mock(NuGetProtocolFacade.class);
  private static final NuGetProtocolProvider PROVIDER = mock(NuGetProtocolProvider.class);
  private static final NuGetBaseUrlResolver RESOLVER = mock(NuGetBaseUrlResolver.class);

  private static final Map<String, Object> READ =
      Map.of("permission", Permission.READ, "writeOperation", false);
  private static final Map<String, Object> WRITE =
      Map.of("permission", Permission.WRITE, "writeOperation", true);

  @Test
  @DisplayName("download of a nupkg and of a nuspec")
  void download() {
    final var nupkg =
        handler(AbstractNuGetDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER, true);
    assertRoute(nupkg, List.of(HttpMethod.GET), READ);
    assertThat(parse(nupkg, "GET", "/repo/v3/package/foo/1.0.0/foo.1.0.0.nupkg")).isPresent();
    assertThat(parse(nupkg, "GET", "/repo/v3/package/foo/1.0.0/foo.nuspec")).isEmpty();
    assertThat(parse(nupkg, "GET", "/repo/v3/package/foo/index.json")).isEmpty();
    assertThat(parse(nupkg, "PUT", "/repo/v3/package/foo/1.0.0/foo.1.0.0.nupkg")).isEmpty();

    final var nuspec =
        handler(AbstractNuGetDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER, false);
    assertRoute(nuspec, List.of(HttpMethod.GET), READ);
    assertThat(parse(nuspec, "GET", "/repo/v3/package/foo/1.0.0/foo.nuspec")).isPresent();
    assertThat(parse(nuspec, "GET", "/repo/v3/package/foo/1.0.0/foo.1.0.0.nupkg")).isEmpty();
  }

  @Test
  @DisplayName("the download, versions and registration routes answer HEAD, nothing else does")
  void head() {
    final var nupkg =
        handler(AbstractNuGetDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER, true);
    final var nuspec =
        handler(AbstractNuGetDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER, false);
    final var versions =
        handler(AbstractNuGetPackageVersionsProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    final var index =
        handler(
            AbstractNuGetRegistrationProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            RESOLVER,
            true);
    final var leaf =
        handler(
            AbstractNuGetRegistrationProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            RESOLVER,
            false);
    final var expected =
        Map.of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);

    for (final var h : List.of(nupkg, nuspec, versions, index, leaf)) {
      assertThat(h.answersHead()).isTrue();
      assertThat(h.getHeadProperties()).isEqualTo(expected);
      assertThat(parse(h, "HEAD", "/repo/v3/search")).isEmpty();
      assertThat(parse(h, "HEAD", "/repo/v3/index.json")).isEmpty();
    }
    assertThat(parse(nupkg, "HEAD", "/repo/v3/package/foo/1.0.0/foo.1.0.0.nupkg")).isPresent();
    assertThat(parse(nuspec, "HEAD", "/repo/v3/package/foo/1.0.0/foo.nuspec")).isPresent();
    assertThat(parse(versions, "HEAD", "/repo/v3/package/foo/index.json")).isPresent();
    assertThat(parse(index, "HEAD", "/repo/v3/registration/foo/index.json")).isPresent();
    assertThat(parse(leaf, "HEAD", "/repo/v3/registration/foo/1.0.0.json")).isPresent();
  }

  @Test
  @DisplayName("package versions")
  void versions() {
    final var h =
        handler(AbstractNuGetPackageVersionsProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(h, List.of(HttpMethod.GET), READ);
    assertThat(parse(h, "GET", "/repo/v3/package/foo/index.json")).isPresent();
    assertThat(parse(h, "GET", "/repo/V3/PACKAGE/foo/INDEX.JSON")).isPresent();
    assertThat(parse(h, "GET", "/repo/v3/package/foo/1.0.0/index.json")).isEmpty();
  }

  @Test
  @DisplayName("publish")
  void publish() {
    final var h = handler(AbstractNuGetPublishProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(h, List.of(HttpMethod.PUT), WRITE);
    assertThat(parse(h, "PUT", "/repo/v3/package")).isPresent();
    assertThat(parse(h, "PUT", "/repo/v3/package/")).isPresent();
    assertThat(parse(h, "PUT", "/repo/v3/package/foo")).isEmpty();
    assertThat(parse(h, "POST", "/repo/v3/package")).isEmpty();
  }

  @Test
  @DisplayName("registration index and leaf")
  void registration() {
    final var index =
        handler(
            AbstractNuGetRegistrationProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            RESOLVER,
            true);
    assertRoute(index, List.of(HttpMethod.GET), READ);
    assertThat(parse(index, "GET", "/repo/v3/registration/foo/index.json")).isPresent();
    assertThat(parse(index, "GET", "/repo/v3/registration/foo/1.0.0.json")).isEmpty();
    assertThat(parse(index, "POST", "/repo/v3/registration/foo/index.json")).isEmpty();

    final var leaf =
        handler(
            AbstractNuGetRegistrationProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            RESOLVER,
            false);
    assertRoute(leaf, List.of(HttpMethod.GET), READ);
    assertThat(parse(leaf, "GET", "/repo/v3/registration/foo/1.0.0.json")).isPresent();
    assertThat(parse(leaf, "GET", "/repo/v3/registration/foo/index.json")).isEmpty();
  }

  @Test
  @DisplayName("relist (POST) and unlist (DELETE) share one path pattern")
  void relistAndUnlist() {
    final var relist =
        handler(AbstractNuGetRelistProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(relist, List.of(HttpMethod.POST), WRITE);
    assertThat(parse(relist, "POST", "/repo/v3/package/foo/1.0.0")).isPresent();
    assertThat(parse(relist, "POST", "/repo/v3/package/foo")).isEmpty();
    assertThat(parse(relist, "DELETE", "/repo/v3/package/foo/1.0.0")).isEmpty();

    final var unlist =
        handler(AbstractNuGetUnlistProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(unlist, List.of(HttpMethod.DELETE), WRITE);
    assertThat(parse(unlist, "DELETE", "/repo/v3/package/foo/1.0.0")).isPresent();
    assertThat(parse(unlist, "DELETE", "/repo/v3/package/foo")).isEmpty();
    assertThat(parse(unlist, "POST", "/repo/v3/package/foo/1.0.0")).isEmpty();
  }

  @Test
  @DisplayName("search and autocomplete match by containment")
  void searchAndAutocomplete() {
    final var search =
        handler(AbstractNuGetSearchProtocolMethodHandler.class, BASE, FACADE, PROVIDER, RESOLVER);
    assertRoute(search, List.of(HttpMethod.GET), READ);
    assertThat(parse(search, "GET", "/repo/v3/search")).isPresent();
    assertThat(parse(search, "GET", "/repo/v3/searchx")).isPresent();
    assertThat(parse(search, "GET", "/repo/v3/package/foo/index.json")).isEmpty();
    assertThat(parse(search, "POST", "/repo/v3/search")).isEmpty();

    final var autocomplete =
        handler(AbstractNuGetAutocompleteProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(autocomplete, List.of(HttpMethod.GET), READ);
    assertThat(parse(autocomplete, "GET", "/repo/v3/autocomplete")).isPresent();
    assertThat(parse(autocomplete, "GET", "/repo/v3/search")).isEmpty();
    assertThat(parse(autocomplete, "POST", "/repo/v3/autocomplete")).isEmpty();
  }

  @Test
  @DisplayName("service index GET and HEAD match the request URI, case-insensitively")
  void serviceIndex() {
    final var get =
        handler(
            AbstractNuGetServiceIndexProtocolMethodHandler.class, BASE, FACADE, PROVIDER, RESOLVER);
    assertRoute(
        get,
        List.of(HttpMethod.GET),
        Map.of(
            "permission",
            Permission.READ,
            "writeOperation",
            false,
            "skipPreProcessor",
            true,
            "skipHeaderPreProcessor",
            true));
    assertThat(parse(get, "GET", "/repo/v3/index.json")).isPresent();
    assertThat(parse(get, "GET", "/repo/V3/Index.JSON")).isPresent();
    assertThat(parse(get, "GET", "/repo/v3/search")).isEmpty();

    assertThat(get.answersHead()).isTrue();
    assertThat(get.getHeadProperties())
        .isEqualTo(
            Map.of(
                "permission",
                Permission.READ,
                "writeOperation",
                false,
                "skipUsagePostProcessor",
                true,
                "skipPreProcessor",
                true));
    assertThat(parse(get, "HEAD", "/repo/v3/index.json")).isPresent();
    assertThat(parse(get, "HEAD", "/repo/V3/INDEX.json")).isPresent();
    assertThat(parse(get, "HEAD", "/repo/v3/search")).isEmpty();
  }
}
