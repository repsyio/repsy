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

import static io.repsy.protocols.oci.constants.OciConstants.DOCKER_CONTENT_DIGEST;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.utils.HelmChartParser;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public abstract class AbstractHelmOciManifestPushProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern MANIFEST_PUSH_PATTERN = Pattern.compile("^/([^/]+)/manifests/(.+)$");
  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";
  private static final Pattern SHA256_DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public AbstractHelmOciManifestPushProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.PUT)
            .skipHeaderPreProcessor(true)
            .writeOperation(true)
            .path(MANIFEST_PUSH_PATTERN.asMatchPredicate()),
        basePathParser,
        helmFacade,
        provider);
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
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CONTENT_TYPE_MISSING);
    }

    rejectOverLongIdentifiers(name, reference, mediaType);

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var existingManifest = this.facade.checkManifest(context, name, reference);
    if (existingManifest.isPresent() && !repoInfo.isAllowOverride()) {
      log.info("Chart {}:{} already exists in repo {}", name, reference, repoInfo.getName());
      throw new ItemAlreadyExistException(ProtocolErrorCodes.CHART_ALREADY_EXISTS);
    }

    final var contentBytes = request.getInputStream().readAllBytes();
    final var manifestJson = new String(contentBytes, StandardCharsets.UTF_8);

    final var chartLayer = this.parseChartLayer(manifestJson);
    final var layerDigest = chartLayer.digest();
    final var layerSize = chartLayer.size();

    final var digest = this.calculateDigest(contentBytes);

    final var blobResource = this.facade.getBlob(context, layerDigest);
    final var metadata = HelmChartParser.parseChartYaml(blobResource.getInputStream());

    this.requireMatchingChartName(name, metadata.getName());

    this.checkChartOverride(context, repoInfo, metadata, layerDigest);

    final var chartForm =
        HelmChartForm.builder()
            .name(metadata.getName())
            .version(metadata.getVersion())
            .description(metadata.getDescription())
            .appVersion(metadata.getAppVersion())
            .type(metadata.getType())
            .apiVersion(metadata.getApiVersion())
            .dependencies(metadata.getDependencies())
            .digest(layerDigest)
            .size(layerSize)
            .build();

    final var pushForm =
        HelmOciManifestPushForm.builder()
            .chart(chartForm)
            .name(name)
            .reference(reference)
            .digest(digest)
            .mediaType(mediaType)
            .content(manifestJson)
            .build();
    final var pushed = this.pushManifest(context, pushForm, contentBytes, 1);
    final var manifestInfo = pushed.manifest();
    // Reported here, once the push has committed, and not from inside the transaction: a push
    // that lost a race is repeated, and its file would be charged once per run.
    ProtocolContextUtils.addUsages(context, pushed.usages());

    if (!reference.startsWith(HelmConstants.SHA256_PREFIX)) {
      context.addProperty(ARTIFACT_NAME, metadata.getName());
      context.addProperty(ARTIFACT_VERSION, metadata.getVersion());
      // The chart archive of an OCI push is its chart layer blob, so that is what a scan reads
      // (RPS-1736); without it the event would name the manifest request path, which holds no file.
      context.addProperty(STORAGE_PATH, HelmConstants.OCI_BLOBS_PATH + "/" + layerDigest);
    }

    final var requestPath = request.getRequestURI();
    final var manifestsBasePath = requestPath.substring(0, requestPath.lastIndexOf('/') + 1);
    final var location =
        PublicUrls.currentContextRoot() + manifestsBasePath + manifestInfo.digest();

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(LOCATION, location)
        .header(DOCKER_CONTENT_DIGEST, manifestInfo.digest())
        .header(CONTENT_TYPE, mediaType)
        .build();
  }

  /**
   * A real OCI push is two separate manifest-push requests hitting this same handler -- one by
   * digest, one by the tag reference -- and {@code handle}'s own {@code checkManifest}-by-reference
   * refusal only ever fires for the tag-referenced one (a fresh digest never already exists as its
   * own reference). Without this, the digest-referenced request's own chart write call would
   * silently upsert the chart row even when the overall push is ultimately refused (RPS-1218's own
   * upsert fix exposed this: the row's digest/size got updated by the by-digest sub-request before
   * the by-tag sub-request's refusal ever ran). Checks the CHART's own (name, version) identity
   * too, refusing only when it already exists with a DIFFERENT digest -- an identical re-push (the
   * normal, successful two-step push's own second request) must stay a no-op, not a refusal.
   */
  private void checkChartOverride(
      final ProtocolContext context,
      final BaseRepoInfo<ID> repoInfo,
      final HelmChartMetadata metadata,
      final String layerDigest) {

    final var existingChart =
        this.facade.findChartByNameAndVersion(context, metadata.getName(), metadata.getVersion());
    if (existingChart.isEmpty()) {
      return;
    }
    if (existingChart.get().digest().equals(layerDigest)) {
      return;
    }
    if (repoInfo.isAllowOverride()) {
      return;
    }

    log.info(
        "Chart {}:{} already exists in repo {}",
        metadata.getName(),
        metadata.getVersion(),
        repoInfo.getName());
    throw new ItemAlreadyExistException(ProtocolErrorCodes.CHART_ALREADY_EXISTS);
  }

  /**
   * The name, reference and media type of a push are stored in helm_oci_manifest columns of 255
   * characters, and the client chooses all three, so a longer one is refused before anything is
   * looked up or written (RPS-1072). None of them can be cut or dropped: the name and reference are
   * how the manifest is found again, and the media type is echoed back to the client.
   */
  private static void rejectOverLongIdentifiers(
      final String name, final String reference, final String mediaType) {

    if (name.length() > HelmConstants.MAX_OCI_MANIFEST_NAME_LENGTH) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_NAME_TOO_LONG);
    }
    if (reference.length() > HelmConstants.MAX_OCI_MANIFEST_REFERENCE_LENGTH) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_REFERENCE_TOO_LONG);
    }
    if (mediaType.length() > HelmConstants.MAX_OCI_MEDIA_TYPE_LENGTH) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_MEDIA_TYPE_TOO_LONG);
    }
  }

  /**
   * The chart archive layer of the manifest. A manifest without any layer, or whose chosen layer
   * lacks a digest or a size, is the client's mistake and is answered with a 400 that names it.
   *
   * <p>A manifest with exactly one layer uses it unconditionally, whatever its media type (or
   * none): every existing client and fixture here builds a bare single-layer manifest with no
   * {@code mediaType} on the layer itself, and there is no other layer it could be.
   *
   * <p>A manifest with MORE than one layer -- a signed push ({@code helm package --sign} + {@code
   * helm push}), which adds a second layer for the {@code .prov} file (RPS-1719) -- picks the layer
   * whose media type is the Helm chart-content type, never {@code layers[0]} by position. Confirmed
   * live: a real Helm client's OCI pusher does NOT keep the chart layer first -- {@code
   * pkg/registry/client.go} orders {@code layers} by ascending digest, so which layer lands at
   * index 0 is effectively arbitrary per push. Trusting {@code layers[0]} unconditionally (the
   * pre-RPS-1719 behavior) made a signed push fail with an unhandled {@code GZIPException} (a bare
   * 500) whenever the {@code .prov} layer's digest happened to sort before the chart layer's --
   * roughly half the time, confirmed live with the real client against both orderings.
   */
  private ChartLayer parseChartLayer(final String manifestJson) {
    final var layers = this.parseManifest(manifestJson).get("layers");
    if (layers == null || !layers.isArray() || layers.isEmpty()) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_LAYERS_MISSING);
    }

    final var layer = this.findChartLayer(layers);
    final var digest = layer.get("digest");
    final var size = layer.get("size");
    if (!isSha256Digest(digest) || !isNonNegativeInteger(size)) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_LAYER_INVALID);
    }

    return new ChartLayer(digest.asString(), size.asLong());
  }

  private JsonNode findChartLayer(final JsonNode layers) {
    if (layers.size() == 1) {
      return layers.get(0);
    }
    for (final var candidate : layers) {
      final var mediaType = candidate.get("mediaType");
      if (mediaType != null
          && mediaType.isString()
          && HelmConstants.CHART_CONTENT_MEDIA_TYPE.equals(mediaType.asString())) {
        return candidate;
      }
    }
    throw new BadRequestException(ProtocolErrorCodes.MANIFEST_CHART_LAYER_MISSING);
  }

  private JsonNode parseManifest(final String manifestJson) {
    final JsonNode manifest;
    try {
      manifest = OBJECT_MAPPER.readTree(manifestJson);
    } catch (final JacksonException e) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_INVALID_JSON);
    }

    if (!manifest.isObject()) {
      throw new BadRequestException(ProtocolErrorCodes.MANIFEST_INVALID_JSON);
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
      throw new BadRequestException(ProtocolErrorCodes.CHART_NAME_MISMATCH);
    }
  }

  private String calculateDigest(final byte[] contentBytes) {
    return HelmConstants.SHA256_PREFIX + BlobDigests.sha256Hex(contentBytes);
  }

  /**
   * Writes the chart version, the manifest and its file as one unit (RPS-1354), and repeats the
   * whole unit when it loses a race on a row. Two first pushes of one tag both insert and the loser
   * fails on the unique index ({@code DataIntegrityViolationException}); two pushes that override
   * the same tag, or a push and a panel delete of its version, both read a row's {@code @Version}
   * and the loser fails the version check ({@code OptimisticLockingFailureException}, RPS-1342);
   * and the chart row lock a push now holds until its manifest is written (RPS-1273) can be one
   * side of a deadlock with a request that takes the manifest row first ({@code
   * PessimisticLockingFailureException}, which the database resolves by aborting one side).
   *
   * <p>The retry has to be here: the facade is the {@code @Transactional} proxy, and a retry inside
   * its transaction would reuse the failed persistence context. Because the chart version row and
   * the manifest are written in that one transaction, a run that fails leaves neither of them
   * behind, so the repeat starts from the state the client saw and the second run sees what the
   * winner committed. When the row stays contended the exception reaches the error handler, which
   * answers a 503 with {@code Retry-After}, and the chart version row still is what it was: a
   * repeated push finds nothing half done.
   */
  @SneakyThrows
  private HelmOciManifestPushResult pushManifest(
      final ProtocolContext context,
      final HelmOciManifestPushForm form,
      final byte[] contentBytes,
      final int counter) {
    try {
      return this.facade.pushManifest(context, form, contentBytes);
    } catch (final DataIntegrityViolationException
        | OptimisticLockingFailureException
        | PessimisticLockingFailureException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }
      Thread.sleep(WAIT_RETRY * counter);
      return this.pushManifest(context, form, contentBytes, counter + 1);
    }
  }
}
