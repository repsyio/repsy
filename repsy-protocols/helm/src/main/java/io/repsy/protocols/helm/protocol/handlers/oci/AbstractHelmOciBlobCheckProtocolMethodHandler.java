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

import static io.repsy.protocols.helm.shared.utils.HelmOciHttpValues.DOCKER_CONTENT_DIGEST;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

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
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Handles HEAD /v2/{repo}/{name}/blobs/{digest} — checks if a blob exists. */
@NullMarked
public abstract class AbstractHelmOciBlobCheckProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern BLOB_CHECK_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/(sha256:[0-9a-fA-F]{64})/?$");

  private final PathParser basePathParser;
  private final HelmFacade<ID> helmFacade;

  public AbstractHelmOciBlobCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
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
          AbstractHelmOciBlobCheckProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!BLOB_CHECK_PATTERN.matcher(relativePath).matches()) {
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

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = BLOB_CHECK_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var digest = matcher.group(2);
    final var blobOpt = this.helmFacade.checkBlob(context, digest);

    if (blobOpt.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    final var blob = blobOpt.get();

    return ResponseEntity.ok()
        .header(CONTENT_LENGTH, String.valueOf(blob.getSize()))
        .header(CONTENT_TYPE, blob.getMediaType())
        .header(DOCKER_CONTENT_DIGEST, digest)
        .build();
  }
}
