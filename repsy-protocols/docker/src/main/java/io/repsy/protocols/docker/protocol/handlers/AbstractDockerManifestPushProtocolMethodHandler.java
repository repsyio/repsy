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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.image.exceptions.ImageDeletedException;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.SavedManifest;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.docker.shared.utils.DockerManifestValidator;
import io.repsy.protocols.docker.shared.utils.DockerPushGuards;
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
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
  private static final int MAX_IMAGE_RECREATIONS = 20;
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
    return Map.of(
        "permission", Permission.WRITE, "skipHeaderPreProcessor", true, "writeOperation", true);
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
      throw new BadRequestException("manifestContentTypeMissing");
    }

    // Before anything is looked up or written (RPS-1139): an over-long or grammatically invalid
    // name, reference or Content-Type must never reach the docker_image / docker_tag insert and
    // fail there with a generic, unnamed 400.
    DockerPushGuards.rejectInvalidImageName(imageName);
    DockerPushGuards.rejectInvalidReference(reference);
    DockerPushGuards.rejectMediaTypeTooLong(contentType);

    final var manifestJson = this.getManifestJsonStr(context, request);

    // Before the image is created: a manifest that cannot be stored must leave nothing behind.
    DockerManifestValidator.validate(contentType, manifestJson);

    final var manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    final var digest = DockerDigestCalculator.calculateDigest(manifestBytes);

    // A manifest is stored once per digest, whatever it was pushed under, so an override or a
    // second tag never rewrites the file of the manifest it replaces (RPS-1216).
    final var parsedManifestPath = this.parseForManifest(request.getServletPath(), digest);

    final var form =
        ManifestForm.builder()
            .tagName(reference)
            .contentType(contentType)
            .manifestJson(manifestJson)
            .relativePath(parsedManifestPath.getRelativePath())
            .servletPath(request.getServletPath())
            .digest(digest)
            .digestSha512(DockerDigestCalculator.calculateSha512Digest(manifestBytes))
            .manifestBytes(manifestBytes)
            .build();

    final var saved = this.saveManifest(context, imageName, form);
    final var manifestDigest = saved.digest();

    if (!contentType.equals(OCI_IMAGE_INDEX) && !contentType.equals(DOCKER_MANIFEST_LIST)) {
      final var storagePathMap = this.layerRenamer.findLayersToRename(repoInfo, manifestJson);
      // A legacy layer still stored under its upload UUID is dropped when its digest already
      // exists, so the bytes it freed are refunded, netted against the manifest's own usage.
      final var renameUsages = this.layerRenamer.renameLayers(repoInfo, storagePathMap);

      ProtocolContextUtils.addUsages(context, renameUsages);
    }

    // Every manifest push, single-platform or index (RPS-1314): the size and digest the panel
    // lists the image with are computed the same way a delete computes them.
    this.imageTxService.refreshImageSize(repoInfo.getId(), saved.image().getId());

    // A push by a digest reference is answered in that reference's algorithm (RPS-1244): the
    // stored manifest is addressable by both, the client verifies against the one it named.
    final var reportedDigest = DockerDigestCalculator.reportedDigest(reference, manifestDigest);
    final var location = this.getServletURILocation(context, imageName, reportedDigest);

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(LOCATION, location)
        .header(DOCKER_CONTENT_DIGEST, reportedDigest)
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

  /**
   * Saves the manifest in one transaction of the facade, and runs that whole transaction again when
   * it loses a race. Two first pushes of the same digest into one image (or of one new tag) both
   * find no row and both insert, and the loser fails on a unique index of {@code docker_manifest}
   * or {@code docker_tag} (RPS-1314). Two pushes that move the same existing tag both read its
   * {@code @Version}, and the loser fails the version check at commit (RPS-1322). The second run
   * sees what the winner committed, so the client gets its {@code 201} instead of an error.
   *
   * <p>The transaction also creates the image when this is its first manifest (RPS-1350), so a push
   * that fails leaves no image without a manifest. Two first pushes into a new image both insert
   * it: the second insert waits for the first transaction, and fails on the image's unique index if
   * that commits (a run again finds the image), or goes through if it rolls back. The same retry
   * covers it.
   *
   * <p>An image goes with its last manifest (RPS-1288), so the image this push found may be deleted
   * before the transaction can lock it. The transaction reports that with {@link
   * ImageDeletedException}; this method runs it again, which creates the image again, without
   * counting it against the retries above: each such run means another request has just deleted the
   * image's last manifest, and it can only happen a bounded number of times for that.
   *
   * <p>This is the only place that retries: the facade is the {@code @Transactional} proxy, and a
   * retry inside its transaction (a retry annotation on the service, which nothing enabled anyway)
   * would reuse the failed persistence context and could never succeed.
   */
  private SavedManifest<ID> saveManifest(
      final ProtocolContext context, final String imageName, final ManifestForm form)
      throws IOException {

    var recreations = 0;

    for (var attempt = 1; ; ) {
      try {
        return this.dockerFacade.saveManifest(context, imageName, form);
      } catch (final ImageDeletedException e) {
        if (++recreations > MAX_IMAGE_RECREATIONS) {
          throw e;
        }
      } catch (final DataIntegrityViolationException | OptimisticLockingFailureException e) {
        if (attempt >= RETRY_COUNT) {
          throw e;
        }

        this.pauseBeforeRetry(WAIT_RETRY * attempt++);
      }
    }
  }

  private void pauseBeforeRetry(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying the manifest save", e);
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
