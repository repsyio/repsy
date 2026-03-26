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

import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.DOCKER_UPLOAD_UUID;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractDockerUploadStartProtocolMethodHandler
    implements ProtocolMethodHandler {

  private static final Pattern UPLOAD_START_PATTERN = Pattern.compile("^/([^/]+)/blobs/uploads/?$");

  private final PathParser basePathParser;

  public AbstractDockerUploadStartProtocolMethodHandler(
      final PathParser basePathParser, final DockerProtocolProvider provider) {
    this.basePathParser = basePathParser;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.POST.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractDockerUploadStartProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());

      final var relativePath = urlProperties.getRelativePath().getPath();

      if (!UPLOAD_START_PATTERN.matcher(relativePath).matches()) {
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

    final var sessionId = this.getUuid();

    final var location =
        ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{sessionId}")
            .buildAndExpand(sessionId)
            .toUriString();

    return ResponseEntity.accepted()
        .header(LOCATION, location)
        .header(DOCKER_UPLOAD_UUID, this.getUuid().toString())
        .build();
  }

  protected UUID getUuid() {

    return UUID.randomUUID();
  }
}
