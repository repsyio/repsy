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
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.utils.HelmChartParser;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@NullMarked
public abstract class AbstractHelmOciManifestPushProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern MANIFEST_PUSH_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");
  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  private final PathParser basePathParser;
  private final HelmFacade<ID> helmFacade;

  public AbstractHelmOciManifestPushProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.WRITE, "skipHeaderPreProcessor", true, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractHelmOciManifestPushProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!MANIFEST_PUSH_PATTERN.matcher(relativePath).matches()) {
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
    final var matcher = MANIFEST_PUSH_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var name = matcher.group(1);
    final var reference = matcher.group(2);
    final var mediaType = request.getContentType();

    if (mediaType == null) {
      return ResponseEntity.badRequest().build();
    }

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var existingManifest = this.helmFacade.checkManifest(context, name, reference);
    if (existingManifest.isPresent() && !repoInfo.isAllowOverride()) {
      throw new ItemAlreadyExistException("chartAlreadyExists: " + name + ":" + reference);
    }

    final var contentBytes = request.getInputStream().readAllBytes();
    final var manifestJson = new String(contentBytes, StandardCharsets.UTF_8);

    final var jsonObject = new ObjectMapper().readTree(manifestJson);
    final var layers = jsonObject.get("layers");
    final var layerDigest = layers.get(0).get("digest").asText();
    final var layerSize = layers.get(0).get("size").asLong();

    final var digest = this.calculateDigest(contentBytes);

    final var blobResource = this.helmFacade.getBlob(context, layerDigest);
    final var metadata = HelmChartParser.parseChartYaml(blobResource.getInputStream());

    final var chartForm =
        HelmChartForm.builder()
            .name(metadata.getName())
            .version(metadata.getVersion())
            .description(metadata.getDescription())
            .appVersion(metadata.getAppVersion())
            .type(metadata.getType())
            .digest(layerDigest)
            .size(layerSize)
            .build();
    final var chartInfo = this.findOrCreateChart(repoInfo.getId(), chartForm, 1);

    final var manifestForm =
        HelmOciManifestForm.builder()
            .chartId(chartInfo.id())
            .name(name)
            .reference(reference)
            .digest(digest)
            .mediaType(mediaType)
            .content(manifestJson)
            .build();
    final var manifestInfo = this.findOrCreateManifest(repoInfo.getId(), manifestForm, 1);

    this.helmFacade.pushManifest(context, name, reference, contentBytes);

    if (!reference.startsWith(HelmConstants.SHA256_PREFIX)) {
      context.addProperty(ARTIFACT_NAME, metadata.getName());
      context.addProperty(ARTIFACT_VERSION, metadata.getVersion());
      context.addProperty("usages", BaseUsages.ofDisk(layerSize));
    }

    final var requestPath = request.getRequestURI();
    final var manifestsBasePath = requestPath.substring(0, requestPath.lastIndexOf('/') + 1);
    final var location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path(manifestsBasePath + manifestInfo.digest())
            .build()
            .toUriString();

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(LOCATION, location)
        .header(DOCKER_CONTENT_DIGEST, manifestInfo.digest())
        .header(CONTENT_TYPE, mediaType)
        .build();
  }

  @SneakyThrows(NoSuchAlgorithmException.class)
  private String calculateDigest(final byte[] contentBytes) {
    final var md = MessageDigest.getInstance("SHA-256");
    return HelmConstants.SHA256_PREFIX + HexFormat.of().formatHex(md.digest(contentBytes));
  }

  @SneakyThrows
  private HelmOciManifestInfo findOrCreateManifest(
      final ID repoId, final HelmOciManifestForm form, final int counter) {
    try {
      return this.helmFacade.findOrCreateManifest(form, repoId);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }
      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateManifest(repoId, form, counter + 1);
    }
  }

  @SneakyThrows
  private HelmChartInfo findOrCreateChart(
      final ID repoId, final HelmChartForm form, final int counter) {
    try {
      return this.helmFacade.findOrCreateChart(form, repoId);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }
      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateChart(repoId, form, counter + 1);
    }
  }
}
