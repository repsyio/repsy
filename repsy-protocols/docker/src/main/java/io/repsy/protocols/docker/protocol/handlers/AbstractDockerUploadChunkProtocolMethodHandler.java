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
import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.RANGE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractDockerUploadChunkProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern UPLOAD_CHUNK_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  private final PathParser basePathParser;
  private final DockerProtocolFacade<ID> dockerFacade;

  public AbstractDockerUploadChunkProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.dockerFacade = dockerFacade;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PATCH);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PATCH.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractDockerUploadChunkProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());

      final var relativePath = urlProperties.getRelativePath().getPath();

      if (!UPLOAD_CHUNK_PATTERN.matcher(relativePath).matches()) {
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

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);
    final var relativePath = urlProperties.getRelativePath().getPath();
    final var matcher = UPLOAD_CHUNK_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var sessionId = matcher.group(2);

    final var uploadPath = new RelativePath("/blobs/" + sessionId);

    final var currentSize =
        this.dockerFacade.uploadLayerChunk(
            context, uploadPath, request.getInputStream(), request.getContentLengthLong());

    final var location = this.getServletURILocation(context, imageName, sessionId);

    return ResponseEntity.accepted()
        .header(LOCATION, location)
        .header(RANGE, "0-" + (currentSize - 1))
        .header(DOCKER_UPLOAD_UUID, this.getUploadUUID().toString())
        .build();
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String sessionId) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/v2/{repoName}/{imageName}/blobs/uploads/{sessionId}")
        .buildAndExpand(urlProperties.getRepoName(), imageName, sessionId)
        .toUriString();
  }

  protected UUID getUploadUUID() {

    return UUID.randomUUID();
  }
}
