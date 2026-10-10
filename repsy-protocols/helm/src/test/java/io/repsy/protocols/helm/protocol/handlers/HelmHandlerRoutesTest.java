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
package io.repsy.protocols.helm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.helm.protocol.handlers.classic.AbstractHelmChartDeleteProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.classic.AbstractHelmChartPullProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.classic.AbstractHelmChartPushProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.classic.AbstractHelmIndexPullProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobCheckProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobPullProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobUploadChunkProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobUploadStartProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciBlobUploadStatusProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciManifestCheckProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciManifestPullProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciManifestPushProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.handlers.oci.AbstractHelmOciTagsListProtocolMethodHandler;
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
 * Pins the registration (methods and processor properties) and the path matching of every classic
 * and OCI helm handler, as they were before the handlers were built from a {@code HandlerRoute}
 * (RPS-2057).
 *
 * <p>The OCI blob upload status route carries {@code permission=WRITE} but no {@code
 * writeOperation} key, unlike the other OCI upload routes; {@code HelmAuthPreProcessor} therefore
 * lets it through unauthenticated on a public repository. That drift is preserved here (and pinned)
 * so the migration changes no behaviour; it is reported as a follow-up.
 */
@DisplayName("Helm handlers keep their methods, properties and path matching")
@SuppressWarnings({"rawtypes", "unchecked"})
class HelmHandlerRoutesTest {

  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String UPLOAD_ID = "123e4567-e89b-12d3-a456-426614174000";

  private static final PathParser BASE = request -> Optional.of(context(request.getRequestURI()));
  private static final HelmProtocolFacade FACADE = mock(HelmProtocolFacade.class);
  private static final HelmProtocolProvider PROVIDER = mock(HelmProtocolProvider.class);

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder()
            .storageKey(UUID.randomUUID())
            .name("helm")
            .privateRepo(false)
            .build();

    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("helm")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();

    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

  private static <T extends ProtocolMethodHandler> T handler(final Class<T> type) {
    return mock(
        type,
        withSettings().useConstructor(BASE, FACADE, PROVIDER).defaultAnswer(CALLS_REAL_METHODS));
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
  @DisplayName("classic chart pull, index pull, push and delete")
  void classic() {
    final var pull = handler(AbstractHelmChartPullProtocolMethodHandler.class);
    assertRoute(
        pull,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(pull, "GET", "/charts/app-1.0.0.tgz")).isPresent();
    assertThat(parse(pull, "GET", "/charts/app-1.0.0.zip")).isEmpty();
    assertThat(parse(pull, "GET", "/charts/a/b.tgz")).isEmpty();
    assertThat(parse(pull, "POST", "/charts/app-1.0.0.tgz")).isEmpty();

    final var index = handler(AbstractHelmIndexPullProtocolMethodHandler.class);
    assertRoute(
        index,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(index, "GET", "/index.yaml")).isPresent();
    assertThat(parse(index, "GET", "/index.yml")).isEmpty();
    assertThat(parse(index, "GET", "/indexXyaml")).isEmpty();

    final var push = handler(AbstractHelmChartPushProtocolMethodHandler.class);
    assertRoute(
        push,
        List.of(HttpMethod.POST),
        Map.of("permission", Permission.WRITE, "writeOperation", true));
    assertThat(parse(push, "POST", "/api/charts")).isPresent();
    assertThat(parse(push, "POST", "/api/charts/app/1.0.0")).isEmpty();
    assertThat(parse(push, "PUT", "/api/charts")).isEmpty();

    final var delete = handler(AbstractHelmChartDeleteProtocolMethodHandler.class);
    assertRoute(
        delete,
        List.of(HttpMethod.DELETE),
        Map.of("permission", Permission.MANAGE, "writeOperation", true));
    assertThat(parse(delete, "DELETE", "/api/charts/app/1.0.0")).isPresent();
    assertThat(parse(delete, "DELETE", "/api/charts/app")).isEmpty();
    assertThat(parse(delete, "GET", "/api/charts/app/1.0.0")).isEmpty();
  }

  @Test
  @DisplayName("OCI blob check and pull match /<name>/blobs/sha256:<64 hex>")
  void blobReads() {
    final var check = handler(AbstractHelmOciBlobCheckProtocolMethodHandler.class);
    assertRoute(
        check,
        List.of(HttpMethod.HEAD),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(check, "HEAD", "/img/blobs/" + DIGEST)).isPresent();
    assertThat(parse(check, "HEAD", "/img/blobs/" + DIGEST + "/")).isPresent();
    assertThat(parse(check, "HEAD", "/img/blobs/sha256:abc")).isEmpty();
    assertThat(parse(check, "GET", "/img/blobs/" + DIGEST)).isEmpty();

    final var pull = handler(AbstractHelmOciBlobPullProtocolMethodHandler.class);
    assertRoute(
        pull,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(pull, "GET", "/img/blobs/" + DIGEST)).isPresent();
    assertThat(parse(pull, "GET", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(pull, "HEAD", "/img/blobs/" + DIGEST)).isEmpty();
  }

  @Test
  @DisplayName("OCI manifest check, pull, push and tags list")
  void manifestsAndTags() {
    final var check = handler(AbstractHelmOciManifestCheckProtocolMethodHandler.class);
    assertRoute(
        check,
        List.of(HttpMethod.HEAD),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(check, "HEAD", "/img/manifests/1.0.0")).isPresent();
    assertThat(parse(check, "GET", "/img/manifests/1.0.0")).isEmpty();

    final var pull = handler(AbstractHelmOciManifestPullProtocolMethodHandler.class);
    assertRoute(
        pull,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(pull, "GET", "/img/manifests/1.0.0")).isPresent();
    assertThat(parse(pull, "GET", "/img/tags/list")).isEmpty();

    final var push = handler(AbstractHelmOciManifestPushProtocolMethodHandler.class);
    assertRoute(
        push,
        List.of(HttpMethod.PUT),
        Map.of(
            "permission",
            Permission.WRITE,
            "skipHeaderPreProcessor",
            true,
            "writeOperation",
            true));
    assertThat(parse(push, "PUT", "/img/manifests/1.0.0")).isPresent();
    assertThat(parse(push, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(push, "POST", "/img/manifests/1.0.0")).isEmpty();

    final var tags = handler(AbstractHelmOciTagsListProtocolMethodHandler.class);
    assertRoute(
        tags,
        List.of(HttpMethod.GET),
        Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(tags, "GET", "/img/tags/list")).isPresent();
    assertThat(parse(tags, "GET", "/img/tags/list/x")).isEmpty();
  }

  @Test
  @DisplayName("OCI blob uploads: start, chunk and finalize write; status has no writeOperation")
  void blobUploads() {
    final var writeProperties =
        Map.<String, Object>of(
            "permission", Permission.WRITE, "skipHeaderPreProcessor", true, "writeOperation", true);

    final var start = handler(AbstractHelmOciBlobUploadStartProtocolMethodHandler.class);
    assertRoute(start, List.of(HttpMethod.POST), writeProperties);
    assertThat(parse(start, "POST", "/img/blobs/uploads")).isPresent();
    assertThat(parse(start, "POST", "/img/blobs/uploads/")).isPresent();
    assertThat(parse(start, "POST", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();

    final var chunk = handler(AbstractHelmOciBlobUploadChunkProtocolMethodHandler.class);
    assertRoute(chunk, List.of(HttpMethod.PATCH), writeProperties);
    assertThat(parse(chunk, "PATCH", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(chunk, "PATCH", "/img/blobs/uploads/nope")).isEmpty();
    assertThat(parse(chunk, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();

    final var finalizer = handler(AbstractHelmOciBlobUploadFinalizeProtocolMethodHandler.class);
    assertRoute(finalizer, List.of(HttpMethod.PUT), writeProperties);
    assertThat(parse(finalizer, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(finalizer, "PUT", "/img/manifests/1.0.0")).isEmpty();

    final var status = handler(AbstractHelmOciBlobUploadStatusProtocolMethodHandler.class);
    assertRoute(
        status, List.of(HttpMethod.GET, HttpMethod.HEAD), Map.of("permission", Permission.WRITE));
    assertThat(parse(status, "GET", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(status, "HEAD", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(status, "PATCH", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
  }
}
