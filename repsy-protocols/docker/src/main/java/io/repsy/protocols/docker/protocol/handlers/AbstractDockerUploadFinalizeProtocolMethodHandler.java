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
import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.DOCKER_UPLOAD_UUID;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_LAYER;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractDockerUploadFinalizeProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern UPLOAD_FINALIZE_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;

  private final PathParser basePathParser;
  private final DockerProtocolFacade<ID> dockerFacade;
  private final LayerService<ID> layerService;

  public AbstractDockerUploadFinalizeProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final LayerService<ID> layerService,
      final DockerProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.dockerFacade = dockerFacade;
    this.layerService = layerService;

    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "skipHeaderPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractDockerUploadFinalizeProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());

      final var relativePath = urlProperties.getRelativePath().getPath();

      if (!UPLOAD_FINALIZE_PATTERN.matcher(relativePath).matches()) {
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

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var matcher = UPLOAD_FINALIZE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var sessionId = matcher.group(2);
    final var digest = request.getParameter("digest");

    if (digest == null) {
      return ResponseEntity.badRequest().build();
    }

    final var layerForm =
        LayerForm.builder()
            .imageName(imageName)
            .digest(digest)
            .mediaType(DOCKER_LAYER)
            .uuid(UUID.fromString(sessionId))
            .build();

    final var layerInfo = this.findOrCreateLayer(repoInfo.getId(), layerForm, 1);

    final var uploadPath = new RelativePath("/blobs/" + sessionId);

    if (request.getContentLength() > 0) {
      this.dockerFacade.uploadLayerChunk(
          context, uploadPath, request.getInputStream(), request.getContentLengthLong());
    }

    this.dockerFacade.finalizeLayerUpload(repoInfo, uploadPath, layerInfo);

    final var location = this.getServletURILocation(context, imageName, digest);

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(DOCKER_CONTENT_DIGEST, digest)
        .header(DOCKER_UPLOAD_UUID, this.getUploadUuid().toString())
        .header(LOCATION, location)
        .build();
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String digest) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/v2/{repoName}/{imageName}/blobs/{digest}")
        .buildAndExpand(urlProperties.getRepoName(), imageName, digest)
        .toUriString();
  }

  protected UUID getUploadUuid() {

    return UUID.randomUUID();
  }

  @SneakyThrows
  private LayerInfo findOrCreateLayer(
      final ID repoId, final LayerForm layerForm, final int counter) {
    try {
      return this.layerService.findOrCreate(layerForm, repoId);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }
      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateLayer(repoId, layerForm, counter + 1);
    }
  }
}
