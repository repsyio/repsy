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
package io.repsy.protocols.npm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.audit.NpmAdvisorySource;
import io.repsy.protocols.npm.shared.auth.services.NpmAuthenticator;
import io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver;
import io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker;
import io.repsy.protocols.npm.shared.search.NpmSearchService;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the registration (methods and processor properties) and the path matching of every npm
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 */
@DisplayName("npm handlers keep their methods, properties and path matching")
class NpmHandlerRoutesTest {

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

  private static final NpmProtocolFacade FACADE = mock(NpmProtocolFacade.class);
  private static final NpmProtocolProvider PROVIDER = mock(NpmProtocolProvider.class);

  @SuppressWarnings("unchecked")
  private static final NpmAdvisorySource<UUID> ADVISORIES = mock(NpmAdvisorySource.class);

  private static final Map<String, Object> READ =
      Map.of("permission", Permission.READ, "writeOperation", false);
  private static final Map<String, Object> WRITE =
      Map.of("permission", Permission.WRITE, "writeOperation", true);
  private static final Map<String, Object> MANAGE =
      Map.of("permission", Permission.MANAGE, "writeOperation", true);

  @Test
  @DisplayName("package metadata")
  void metadata() {
    final var h =
        handler(AbstractNpmPackageMetadataProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(h, List.of(HttpMethod.GET), READ);
    assertThat(parse(h, "GET", "/repo/demo")).isPresent();
    assertThat(parse(h, "GET", "/repo/@acme/demo")).isPresent();
    assertThat(parse(h, "GET", "/repo/demo/-/demo-1.0.0.tgz")).isEmpty();
    assertThat(parse(h, "GET", "/repo/-/package/demo/dist-tags")).isEmpty();
    assertThat(parse(h, "GET", "/repo/dist-tags")).isEmpty();
    assertThat(parse(h, "PUT", "/repo/demo")).isEmpty();
  }

  @Test
  @DisplayName("package download")
  void download() {
    final var h =
        handler(AbstractNpmPackageDownloadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(h, List.of(HttpMethod.GET), READ);
    assertThat(parse(h, "GET", "/repo/demo/-/demo-1.0.0.tgz")).isPresent();
    assertThat(parse(h, "GET", "/repo/@acme/demo/-/demo-1.0.0.tgz")).isPresent();
    assertThat(parse(h, "GET", "/repo/demo")).isEmpty();
    assertThat(parse(h, "GET", "/repo/demo/-/dist-tags.tgz")).isEmpty();
    assertThat(parse(h, "POST", "/repo/demo/-/demo-1.0.0.tgz")).isEmpty();
  }

  @Test
  @DisplayName("head hands every path to the base parser")
  void head() {
    final var h = handler(AbstractNpmHeadProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.HEAD),
        Map.of(
            "permission",
            Permission.READ,
            "writeOperation",
            false,
            "skipUsagePostProcessor",
            true));
    assertThat(parse(h, "HEAD", "/repo/demo")).isPresent();
    assertThat(parse(h, "HEAD", "/repo/demo/-/demo-1.0.0.tgz")).isPresent();
  }

  @Test
  @DisplayName("publish or deprecate")
  void publish() {
    final var h =
        handler(
            AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            1024L);
    assertRoute(h, List.of(HttpMethod.PUT), WRITE);
    assertThat(parse(h, "PUT", "/repo/demo")).isPresent();
    assertThat(parse(h, "PUT", "/repo/@acme/demo")).isPresent();
    assertThat(parse(h, "PUT", "/repo/-/user/org.couchdb.user:bob")).isEmpty();
    assertThat(parse(h, "PUT", "/repo/-/package/demo/dist-tags/latest")).isEmpty();
    assertThat(parse(h, "PUT", "/repo/demo/-rev/1-abc")).isEmpty();
    assertThat(parse(h, "GET", "/repo/demo")).isEmpty();
  }

  @Test
  @DisplayName("unpublish")
  void unpublish() {
    final var h =
        handler(
            AbstractNpmPackageUnpublishProtocolMethodHandler.class,
            BASE,
            FACADE,
            new ObjectMapper(),
            PROVIDER);
    assertRoute(h, List.of(HttpMethod.PUT), MANAGE);
    assertThat(parse(h, "PUT", "/repo/demo/-rev/1-abc")).isPresent();
    assertThat(parse(h, "PUT", "/repo/demo")).isEmpty();
    assertThat(parse(h, "PUT", "/repo/demo/-/demo-1.0.0.tgz/-rev/1-abc")).isEmpty();
    assertThat(parse(h, "DELETE", "/repo/demo/-rev/1-abc")).isEmpty();
  }

  @Test
  @DisplayName("package delete")
  void packageDelete() {
    final var h =
        handler(AbstractNpmPackageDeleteProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(h, List.of(HttpMethod.DELETE), MANAGE);
    assertThat(parse(h, "DELETE", "/repo/demo/-rev/1-abc")).isPresent();
    assertThat(parse(h, "DELETE", "/repo/demo")).isEmpty();
    assertThat(parse(h, "DELETE", "/repo/-/package/demo/dist-tags/latest")).isEmpty();
    assertThat(parse(h, "PUT", "/repo/demo/-rev/1-abc")).isEmpty();
  }

  @Test
  @DisplayName("dist-tags get, add and remove")
  void distTags() {
    final var get =
        handler(AbstractNpmDistTagsGetProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(get, List.of(HttpMethod.GET), READ);
    assertThat(parse(get, "GET", "/repo/-/package/demo/dist-tags")).isPresent();
    assertThat(parse(get, "GET", "/repo/-/package/@acme/demo/dist-tags")).isPresent();
    assertThat(parse(get, "GET", "/repo/-/package/demo/dist-tags/latest")).isEmpty();
    assertThat(parse(get, "PUT", "/repo/-/package/demo/dist-tags")).isEmpty();

    final var add =
        handler(AbstractNpmDistTagsAddProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(add, List.of(HttpMethod.PUT), WRITE);
    assertThat(parse(add, "PUT", "/repo/-/package/demo/dist-tags/latest")).isPresent();
    assertThat(parse(add, "PUT", "/repo/-/package/demo/dist-tags")).isEmpty();
    assertThat(parse(add, "DELETE", "/repo/-/package/demo/dist-tags/latest")).isEmpty();

    final var remove =
        handler(AbstractNpmDistTagsRemoveProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(remove, List.of(HttpMethod.DELETE), WRITE);
    assertThat(parse(remove, "DELETE", "/repo/-/package/demo/dist-tags/latest")).isPresent();
    assertThat(parse(remove, "DELETE", "/repo/-/package/demo/dist-tags")).isEmpty();
    assertThat(parse(remove, "PUT", "/repo/-/package/demo/dist-tags/latest")).isEmpty();
  }

  @Test
  @DisplayName("login")
  void login() {
    final var h =
        handler(
            AbstractNpmLoginProtocolMethodHandler.class,
            BASE,
            mock(NpmAuthenticator.class),
            new ObjectMapper(),
            PROVIDER);
    assertRoute(
        h,
        List.of(HttpMethod.PUT),
        Map.of("permission", Permission.NONE, "writeOperation", false, "skipPreProcessor", true));
    assertThat(parse(h, "PUT", "/repo/-/user/org.couchdb.user:bob")).isPresent();
    assertThat(parse(h, "PUT", "/repo/demo")).isEmpty();
    assertThat(parse(h, "GET", "/repo/-/user/org.couchdb.user:bob")).isEmpty();
  }

  @Test
  @DisplayName("ping, whoami, search and token revoke are exact-path endpoints")
  void exactEndpoints() {
    final var skipUsage =
        Map.<String, Object>of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);
    final var requireAuth =
        Map.<String, Object>of(
            "permission",
            Permission.READ,
            "writeOperation",
            false,
            "requireAuthentication",
            true,
            "skipUsagePostProcessor",
            true);

    final var ping = handler(AbstractNpmPingProtocolMethodHandler.class, BASE, PROVIDER);
    assertRoute(ping, List.of(HttpMethod.GET), skipUsage);
    assertThat(parse(ping, "GET", "/repo/-/ping")).isPresent();
    assertThat(parse(ping, "GET", "/repo/-/ping/x")).isEmpty();
    assertThat(parse(ping, "GET", "/repo/ping")).isEmpty();
    assertThat(parse(ping, "POST", "/repo/-/ping")).isEmpty();

    final var whoami =
        handler(
            AbstractNpmWhoamiProtocolMethodHandler.class,
            BASE,
            mock(NpmIdentityResolver.class),
            PROVIDER);
    assertRoute(whoami, List.of(HttpMethod.GET), requireAuth);
    assertThat(parse(whoami, "GET", "/repo/-/whoami")).isPresent();
    assertThat(parse(whoami, "GET", "/repo/whoami")).isEmpty();
    assertThat(parse(whoami, "POST", "/repo/-/whoami")).isEmpty();

    final var search =
        handler(
            AbstractNpmSearchProtocolMethodHandler.class,
            BASE,
            mock(NpmSearchService.class),
            PROVIDER);
    assertRoute(search, List.of(HttpMethod.GET), skipUsage);
    assertThat(parse(search, "GET", "/repo/-/v1/search")).isPresent();
    assertThat(parse(search, "GET", "/repo/-/v1/search/x")).isEmpty();
    assertThat(parse(search, "POST", "/repo/-/v1/search")).isEmpty();

    final var revoke =
        handler(
            AbstractNpmTokenRevokeProtocolMethodHandler.class,
            BASE,
            mock(NpmTokenRevoker.class),
            PROVIDER);
    assertRoute(revoke, List.of(HttpMethod.DELETE), requireAuth);
    assertThat(parse(revoke, "DELETE", "/repo/-/user/token/abc")).isPresent();
    assertThat(parse(revoke, "DELETE", "/repo/-/user/token/")).isEmpty();
    assertThat(parse(revoke, "DELETE", "/repo/-/user/token/a/b")).isEmpty();
    assertThat(parse(revoke, "GET", "/repo/-/user/token/abc")).isEmpty();
  }

  @Test
  @DisplayName("audit bulk and legacy")
  void audit() {
    final var skipUsage =
        Map.<String, Object>of(
            "permission", Permission.READ, "writeOperation", false, "skipUsagePostProcessor", true);

    final var bulk =
        handler(AbstractNpmAuditBulkProtocolMethodHandler.class, BASE, ADVISORIES, PROVIDER);
    assertRoute(bulk, List.of(HttpMethod.POST), skipUsage);
    assertThat(parse(bulk, "POST", "/repo/-/npm/v1/security/advisories/bulk")).isPresent();
    assertThat(parse(bulk, "POST", "/repo/-/npm/v1/security/audits")).isEmpty();
    assertThat(parse(bulk, "GET", "/repo/-/npm/v1/security/advisories/bulk")).isEmpty();

    final var legacy =
        handler(AbstractNpmAuditLegacyProtocolMethodHandler.class, BASE, ADVISORIES, PROVIDER);
    assertRoute(legacy, List.of(HttpMethod.POST), skipUsage);
    assertThat(parse(legacy, "POST", "/repo/-/npm/v1/security/audits")).isPresent();
    assertThat(parse(legacy, "POST", "/repo/-/npm/v1/security/audits/quick")).isPresent();
    assertThat(parse(legacy, "POST", "/repo/-/npm/v1/security/advisories/bulk")).isEmpty();
  }
}
