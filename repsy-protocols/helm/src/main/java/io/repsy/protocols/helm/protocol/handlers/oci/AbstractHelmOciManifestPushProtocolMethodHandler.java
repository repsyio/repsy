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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@NullMarked
public abstract class AbstractHelmOciManifestPushProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern MANIFEST_PUSH_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");
  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final Pattern SHA256_DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
      log.info("Chart {}:{} already exists in repo {}", name, reference, repoInfo.getName());
      throw new ItemAlreadyExistException("chartAlreadyExists");
    }

    final var contentBytes = request.getInputStream().readAllBytes();
    final var manifestJson = new String(contentBytes, StandardCharsets.UTF_8);

    final var chartLayer = this.parseChartLayer(manifestJson);
    final var layerDigest = chartLayer.digest();
    final var layerSize = chartLayer.size();

    final var digest = this.calculateDigest(contentBytes);

    final var blobResource = this.helmFacade.getBlob(context, layerDigest);
    final var metadata = HelmChartParser.parseChartYaml(blobResource.getInputStream());

    this.requireMatchingChartName(name, metadata.getName());

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

  /**
   * The chart archive is the first layer of the manifest. A manifest without one, or whose layer
   * lacks a digest or a size, is the client's mistake and is answered with a 400 that names it.
   */
  private ChartLayer parseChartLayer(final String manifestJson) {
    final var layers = this.parseManifest(manifestJson).get("layers");
    if (layers == null || !layers.isArray() || layers.isEmpty()) {
      throw new BadRequestException("manifestLayersMissing");
    }

    final var layer = layers.get(0);
    final var digest = layer.get("digest");
    final var size = layer.get("size");
    if (!isSha256Digest(digest) || !isNonNegativeInteger(size)) {
      throw new BadRequestException("manifestLayerInvalid");
    }

    return new ChartLayer(digest.asString(), size.asLong());
  }

  private JsonNode parseManifest(final String manifestJson) {
    final JsonNode manifest;
    try {
      manifest = OBJECT_MAPPER.readTree(manifestJson);
    } catch (final JacksonException e) {
      throw new BadRequestException("manifestInvalidJson");
    }

    if (!manifest.isObject()) {
      throw new BadRequestException("manifestInvalidJson");
    }
    return manifest;
  }

  private static boolean isSha256Digest(@Nullable final JsonNode digest) {
    return digest != null
        && digest.isString()
        && SHA256_DIGEST_PATTERN.matcher(digest.asString()).matches();
  }

  private static boolean isNonNegativeInteger(@Nullable final JsonNode size) {
    return size != null && size.isIntegralNumber() && size.asLong() >= 0;
  }

  private record ChartLayer(String digest, long size) {}

  /**
   * The chart and its version are stored under the {@code Chart.yaml} name and the manifest and its
   * tags under the path name; letting the two differ leaves the chart unreachable by its own tags.
   */
  private void requireMatchingChartName(final String pathName, final String chartName) {
    if (!pathName.equals(chartName)) {
      throw new BadRequestException("chartNameMismatch");
    }
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
