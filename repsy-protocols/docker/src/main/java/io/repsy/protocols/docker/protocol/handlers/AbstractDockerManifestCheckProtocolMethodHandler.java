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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
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
public abstract class AbstractDockerManifestCheckProtocolMethodHandler<ID>
    implements ProtocolMethodHandler, DockerPathParserManifest {

  private static final Pattern MANIFEST_CHECK_PATTERN =
      Pattern.compile("^/([^/]+)/manifests/(.+)$");

  private final PathParser basePathParser;
  private final DockerProtocolFacade<ID> dockerFacade;

  public AbstractDockerManifestCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.dockerFacade = dockerFacade;

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
          AbstractDockerManifestCheckProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());

      final var relativePath = urlProperties.getRelativePath().getPath();

      if (!MANIFEST_CHECK_PATTERN.matcher(relativePath).matches()) {
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

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    final var matcher = MANIFEST_CHECK_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var reference = matcher.group(2);

    final var fileName =
        ManifestNameGenerator.generate(repoInfo.getStorageKey(), imageName, reference);
    final var parsedManifestPath = this.parseForManifest(request.getServletPath(), fileName);

    final var tagManifestPair =
        this.dockerFacade.findTagAndManifest(
            context, reference, imageName, parsedManifestPath.getRelativePath());

    if (tagManifestPair.getFirst().isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    final var tagDetail = tagManifestPair.getFirst().get();
    final var manifestContent = tagManifestPair.getSecond();

    return ResponseEntity.ok()
        .header(CONTENT_TYPE, tagDetail.getMediaType())
        .header(CONTENT_LENGTH, String.valueOf(manifestContent.getBytes(UTF_8).length))
        .header(DOCKER_CONTENT_DIGEST, tagDetail.getDigest())
        .build();
  }
}
