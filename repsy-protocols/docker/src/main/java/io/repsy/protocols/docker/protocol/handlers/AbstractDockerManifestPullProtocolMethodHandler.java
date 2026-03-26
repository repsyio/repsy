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

import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.DOCKER_CONTENT_DIGEST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_MANIFEST_SCHEMA1;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.utils.AcceptHeaderParser;
import io.repsy.protocols.docker.shared.utils.MediaTypes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractDockerManifestPullProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern MANIFEST_PULL_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");

  private static final List<String> DEFAULT_DOCKER_ACCEPT_TYPES =
      List.of(DOCKER_MANIFEST_SCHEMA2, DOCKER_MANIFEST_LIST, OCI_MANIFEST_SCHEMA1, OCI_IMAGE_INDEX);

  private final PathParser basePathParser;
  private final DockerProtocolFacade<ID> dockerFacade;

  public AbstractDockerManifestPullProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {

    this.basePathParser = basePathParser;
    this.dockerFacade = dockerFacade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractDockerManifestPullProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());

      final var relativePath = urlProperties.getRelativePath().getPath();

      if (!MANIFEST_PULL_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var matcher = MANIFEST_PULL_PATTERN.matcher(relativePath.getPath());

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var reference = matcher.group(2);

    final var acceptHeaders =
        AcceptHeaderParser.parse(request.getHeader("Accept"), DEFAULT_DOCKER_ACCEPT_TYPES);

    final var preferredMediaType = MediaTypes.getPreferredMediaType(acceptHeaders);

    if (preferredMediaType == null) {
      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body("unsupportedMediaType");
    }

    final var manifest =
        this.dockerFacade.getManifest(context, reference, imageName, request.getServletPath());

    final var contentLength = manifest.body().getBytes(StandardCharsets.UTF_8).length;

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, manifest.mediaType())
        .header(CONTENT_LENGTH, String.valueOf(contentLength))
        .header(DOCKER_CONTENT_DIGEST, manifest.digest())
        .body(manifest.body());
  }
}
