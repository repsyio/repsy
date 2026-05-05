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
package io.repsy.protocols.helm.protocol.handlers.oci;

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_UPLOAD_UUID;
import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.RANGE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
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

/** Handles PATCH /v2/{repo}/{name}/blobs/uploads/{uuid} — uploads a blob chunk. */
@NullMarked
public abstract class AbstractHelmOciBlobUploadChunkProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern UPLOAD_CHUNK_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  private final PathParser basePathParser;
  private final HelmFacade<ID> helmFacade;

  public AbstractHelmOciBlobUploadChunkProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
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
          AbstractHelmOciBlobUploadChunkProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

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
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = UPLOAD_CHUNK_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var chartName = matcher.group(1);
    final var uploadId = UUID.fromString(matcher.group(2));

    final var currentSize =
        this.helmFacade.uploadBlobChunk(
            context, uploadId, request.getInputStream(), request.getContentLengthLong());

    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/v2/{repoName}/{chartName}/blobs/uploads/{uploadId}")
            .buildAndExpand(urlProperties.getRepoName(), chartName, uploadId)
            .toUriString();

    return ResponseEntity.accepted()
        .header(LOCATION, location)
        .header(RANGE, "0-" + (currentSize - 1))
        .header(DOCKER_UPLOAD_UUID, uploadId.toString())
        .build();
  }
}
