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
package io.repsy.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
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
 * Pins the registration (methods and processor properties) and the path matching of every docker
 * handler, as they were before the handlers were built from a {@code HandlerRoute} (RPS-2057): a
 * key typo in a property silently changes a permission.
 *
 * <p>The upload routes (start, chunk, finalize, status) deliberately carry no {@code
 * writeOperation} key: Docker authenticates by {@code permission} (see {@code
 * DockerAuthPreProcessor}), and {@code writeOperation} only decides whether {@code
 * ArtifactPushedEventPostProcessor} publishes a push event, which only a manifest push may.
 */
@DisplayName("Docker handlers keep their methods, properties and path matching")
@SuppressWarnings({"rawtypes", "unchecked"})
class DockerHandlerRoutesTest {

  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String UPLOAD_ID = "123e4567-e89b-12d3-a456-426614174000";

  private static final PathParser BASE = request -> Optional.of(context(request.getRequestURI()));
  private static final DockerProtocolFacade FACADE = mock(DockerProtocolFacade.class);
  private static final LayerService LAYERS = mock(LayerService.class);
  private static final DockerProtocolProvider PROVIDER = mock(DockerProtocolProvider.class);

  private static ProtocolContext context(final String relativePath) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder()
            .storageKey(UUID.randomUUID())
            .name("docker")
            .privateRepo(false)
            .build();

    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("docker")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();

    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

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
  @DisplayName("layer check (HEAD) and layer pull (GET) match /<name>/blobs/<digest>")
  void layers() {
    final var check =
        handler(AbstractDockerLayerCheckProtocolMethodHandler.class, BASE, LAYERS, PROVIDER);
    assertRoute(check, List.of(HttpMethod.HEAD), Map.of("permission", Permission.READ));
    assertThat(parse(check, "HEAD", "/img/blobs/" + DIGEST)).isPresent();
    assertThat(parse(check, "HEAD", "/img/blobs/" + DIGEST + "/")).isPresent();
    assertThat(parse(check, "GET", "/img/blobs/" + DIGEST)).isEmpty();
    assertThat(parse(check, "HEAD", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(check, "HEAD", "/img/blobs/sha256:abc")).isEmpty();

    final var pull =
        handler(AbstractDockerLayerPullProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(pull, List.of(HttpMethod.GET), Map.of("permission", Permission.READ));
    assertThat(parse(pull, "GET", "/img/blobs/" + DIGEST)).isPresent();
    assertThat(parse(pull, "HEAD", "/img/blobs/" + DIGEST)).isEmpty();
    assertThat(parse(pull, "GET", "/img/manifests/latest")).isEmpty();
  }

  @Test
  @DisplayName("manifest check, pull, delete and push match /<name>/manifests/<ref>")
  void manifests() {
    final var check =
        handler(AbstractDockerManifestCheckProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(check, List.of(HttpMethod.HEAD), Map.of("permission", Permission.READ));
    assertThat(parse(check, "HEAD", "/img/manifests/latest")).isPresent();
    assertThat(parse(check, "HEAD", "/img/manifests/")).isEmpty();
    assertThat(parse(check, "GET", "/img/manifests/latest")).isEmpty();

    final var pull =
        handler(AbstractDockerManifestPullProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(pull, List.of(HttpMethod.GET), Map.of("permission", Permission.READ));
    assertThat(parse(pull, "GET", "/img/manifests/latest")).isPresent();
    assertThat(parse(pull, "GET", "/img/tags/list")).isEmpty();
    assertThat(parse(pull, "PUT", "/img/manifests/latest")).isEmpty();

    final var delete =
        handler(AbstractDockerManifestDeleteProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        delete,
        List.of(HttpMethod.DELETE),
        Map.of("permission", Permission.MANAGE, "writeOperation", false));
    assertThat(parse(delete, "DELETE", "/img/manifests/" + DIGEST)).isPresent();
    assertThat(parse(delete, "DELETE", "/img/blobs/" + DIGEST)).isEmpty();
    assertThat(parse(delete, "GET", "/img/manifests/latest")).isEmpty();

    final var push =
        handler(
            AbstractDockerManifestPushProtocolMethodHandler.class,
            BASE,
            FACADE,
            PROVIDER,
            mock(AbstractDockerLayerRenamer.class),
            mock(ImageService.class));
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
    assertThat(parse(push, "PUT", "/img/manifests/latest")).isPresent();
    assertThat(parse(push, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(push, "POST", "/img/manifests/latest")).isEmpty();
  }

  @Test
  @DisplayName("tags list")
  void tagsList() {
    final var h =
        handler(AbstractDockerTagsListProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        h, List.of(HttpMethod.GET), Map.of("permission", Permission.READ, "writeOperation", false));
    assertThat(parse(h, "GET", "/img/tags/list")).isPresent();
    assertThat(parse(h, "GET", "/img/tags/list/more")).isEmpty();
    assertThat(parse(h, "POST", "/img/tags/list")).isEmpty();
  }

  @Test
  @DisplayName("upload start, chunk, finalize and status carry no writeOperation key")
  void uploads() {
    final var start = handler(AbstractDockerUploadStartProtocolMethodHandler.class, BASE, PROVIDER);
    assertRoute(
        start,
        List.of(HttpMethod.POST),
        Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true));
    assertThat(parse(start, "POST", "/img/blobs/uploads")).isPresent();
    assertThat(parse(start, "POST", "/img/blobs/uploads/")).isPresent();
    assertThat(parse(start, "POST", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(start, "PUT", "/img/blobs/uploads/")).isEmpty();

    final var chunk =
        handler(AbstractDockerUploadChunkProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        chunk,
        List.of(HttpMethod.PATCH),
        Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true));
    assertThat(parse(chunk, "PATCH", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(chunk, "PATCH", "/img/blobs/uploads/not-a-uuid")).isEmpty();
    assertThat(parse(chunk, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();

    final var finalizer =
        handler(
            AbstractDockerUploadFinalizeProtocolMethodHandler.class,
            BASE,
            FACADE,
            LAYERS,
            PROVIDER);
    assertRoute(
        finalizer,
        List.of(HttpMethod.PUT),
        Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true));
    assertThat(parse(finalizer, "PUT", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(finalizer, "PUT", "/img/manifests/latest")).isEmpty();
    assertThat(parse(finalizer, "PATCH", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();

    final var status =
        handler(AbstractDockerUploadStatusProtocolMethodHandler.class, BASE, FACADE, PROVIDER);
    assertRoute(
        status, List.of(HttpMethod.GET, HttpMethod.HEAD), Map.of("permission", Permission.WRITE));
    assertThat(parse(status, "GET", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(status, "HEAD", "/img/blobs/uploads/" + UPLOAD_ID)).isPresent();
    assertThat(parse(status, "PATCH", "/img/blobs/uploads/" + UPLOAD_ID)).isEmpty();
    assertThat(parse(status, "GET", "/img/blobs/" + DIGEST)).isEmpty();
  }

  @Test
  @DisplayName("registry check answers GET and HEAD of /v2 and /v2/ with an empty repo context")
  void registryCheck() {
    final var h = handler(AbstractDockerRegistryCheckProtocolMethodHandler.class, PROVIDER);
    final var context = context("/");
    doReturn(Optional.of(context)).when(h).createWithEmptyRepo();

    assertRoute(
        h,
        List.of(HttpMethod.GET, HttpMethod.HEAD),
        Map.of(
            "permission",
            Permission.NONE,
            "skipPreProcessor",
            true,
            "skipUsagePostProcessor",
            true));
    assertThat(parse(h, "GET", "/v2")).containsSame(context);
    assertThat(parse(h, "HEAD", "/v2/")).containsSame(context);
    assertThat(parse(h, "GET", "/v2/token")).isEmpty();
    assertThat(parse(h, "POST", "/v2")).isEmpty();
  }

  @Test
  @DisplayName("token builds its own context for GET and POST of /v2/token")
  void token() {
    final var h =
        handler(
            AbstractDockerTokenProtocolMethodHandler.class,
            mock(DockerAuthService.class),
            mock(DockerScopeParser.class),
            PROVIDER);
    final var context = context("/token");
    doReturn(Optional.of(context)).when(h).findProtocolContext(any());

    assertRoute(
        h,
        List.of(HttpMethod.GET, HttpMethod.POST),
        Map.of(
            "permission",
            Permission.NONE,
            "skipHeaderPreProcessor",
            true,
            "skipUsagePostProcessor",
            true,
            "skipPreProcessor",
            true));
    assertThat(parse(h, "GET", "/v2/token")).containsSame(context);
    assertThat(parse(h, "POST", "/v2/token")).containsSame(context);
    assertThat(parse(h, "GET", "/v2")).isEmpty();
    assertThat(parse(h, "PUT", "/v2/token")).isEmpty();
  }
}
