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
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@NullMarked
public abstract class AbstractDockerManifestPushProtocolMethodHandler<ID>
    implements ProtocolMethodHandler, DockerPathParserManifest {

  private static final String MANIFEST_JSON = "manifestJson";
  private static final Pattern MANIFEST_PUSH_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");

  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;

  private final PathParser basePathParser;
  private final DockerProtocolFacade<ID> dockerFacade;
  private final ImageService<ID> imageTxService;
  private final AbstractDockerLayerRenamer<ID> layerRenamer;

  public AbstractDockerManifestPushProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider,
      final AbstractDockerLayerRenamer<ID> layerRenamer,
      final ImageService<ID> imageService) {

    this.imageTxService = imageService;
    this.basePathParser = basePathParser;
    this.dockerFacade = dockerFacade;
    this.layerRenamer = layerRenamer;

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

    return this::createProtocolContext;
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws NoSuchAlgorithmException, IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
    final var matcher = MANIFEST_PUSH_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var reference = matcher.group(2);
    final var contentType = request.getHeader(CONTENT_TYPE);

    if (contentType == null) {
      return ResponseEntity.badRequest().build();
    }

    final var imageInfo = this.findOrCreateImage(repoInfo.getId(), imageName, 1);

    final var fileName =
        ManifestNameGenerator.generate(repoInfo.getStorageKey(), imageName, reference);
    final var parsedManifestPath = this.parseForManifest(request.getServletPath(), fileName);

    final var manifestJson = this.getManifestJsonStr(context, request);

    final var manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    final var digest = DockerDigestCalculator.calculateDigest(manifestBytes);

    final var form =
        ManifestForm.builder()
            .tagName(reference)
            .contentType(contentType)
            .manifestJson(manifestJson)
            .relativePath(parsedManifestPath.getRelativePath())
            .servletPath(request.getServletPath())
            .digest(digest)
            .manifestBytes(manifestBytes)
            .build();

    final var manifestDigest = this.dockerFacade.saveManifest(context, imageInfo, form);

    if (manifestDigest == null) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body("invalidManifest");
    }

    // If Not Manifest List, rename Layers.
    if (!contentType.equals(OCI_IMAGE_INDEX) && !contentType.equals(DOCKER_MANIFEST_LIST)) {
      final var storagePathMap = this.layerRenamer.findLayersToRename(repoInfo, manifestJson);
      this.layerRenamer.renameLayers(repoInfo, storagePathMap);
    } else {
      this.imageTxService.updateImageSize(repoInfo.getId(), imageInfo.getId(), manifestDigest);
    }

    final var location = this.getServletURILocation(context, imageName, digest);

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(LOCATION, location)
        .header(DOCKER_CONTENT_DIGEST, manifestDigest)
        .header(CONTENT_TYPE, contentType)
        .build();
  }

  private String getManifestJsonStr(final ProtocolContext context, final HttpServletRequest request)
      throws IOException {

    final var manifestJson =
        (String) context.getContextMap().getOrDefault(MANIFEST_JSON, StringUtils.EMPTY);

    if (StringUtils.isEmpty(manifestJson)) {
      return this.readRequestBody(request);
    }

    return manifestJson;
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String digest) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/v2/{repoName}/{imageName}/manifests/{digest}")
        .buildAndExpand(urlProperties.getRepoName(), imageName, digest)
        .toUriString();
  }

  @SneakyThrows
  private BaseImageInfo<ID> findOrCreateImage(
      final ID repoId, final String imageName, final int counter) {
    try {
      return this.imageTxService.findOrCreateImage(repoId, imageName);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }
      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateImage(repoId, imageName, counter + 1);
    }
  }

  private String readRequestBody(final HttpServletRequest request) throws IOException {

    final var inputStream = request.getInputStream();
    final var bytes = inputStream.readAllBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private Optional<ProtocolContext> createProtocolContext(final HttpServletRequest request) {

    if (!HttpMethod.PUT.equals(HttpMethod.valueOf(request.getMethod()))) {
      return Optional.empty();
    }

    final var parsedPathOpt =
        AbstractDockerManifestPushProtocolMethodHandler.this.basePathParser.parse(request);
    if (parsedPathOpt.isEmpty()) {
      return Optional.empty();
    }

    final var urlProperties = ProtocolContextUtils.getUrlProperties(parsedPathOpt.get());
    final var relativePath = urlProperties.getRelativePath().getPath();

    if (!MANIFEST_PUSH_PATTERN.matcher(relativePath).matches()) {
      return Optional.empty();
    }

    return parsedPathOpt;
  }
}
