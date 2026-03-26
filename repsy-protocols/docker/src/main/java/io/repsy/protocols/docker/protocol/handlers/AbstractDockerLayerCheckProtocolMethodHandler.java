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
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractDockerLayerCheckProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern LAYER_CHECK_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/(sha256:[0-9a-fA-F]{64})/?$");

  private final PathParser basePathParser;
  private final LayerService<ID> layerService;

  public AbstractDockerLayerCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final LayerService<ID> layerTxService,
      final DockerProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.layerService = layerTxService;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.HEAD);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.HEAD.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractDockerLayerCheckProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!LAYER_CHECK_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    final var matcher = LAYER_CHECK_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var digest = matcher.group(2);

    final var layerOpt = this.layerService.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), digest);

    if (layerOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    final var layerInfo = layerOpt.get();

    return ResponseEntity.ok()
        .header(CONTENT_LENGTH, String.valueOf(layerInfo.getSize()))
        .header(CONTENT_TYPE, layerInfo.getMediaType())
        .header(DOCKER_CONTENT_DIGEST, digest)
        .build();
  }
}
