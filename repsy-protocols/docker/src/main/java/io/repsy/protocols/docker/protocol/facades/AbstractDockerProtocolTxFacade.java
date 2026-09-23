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
package io.repsy.protocols.docker.protocol.facades;

import static io.repsy.protocols.docker.shared.utils.ManifestNameGenerator.generate;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_CONFIG_JSON;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA1;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_CONFIG_JSON;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_EMPTY;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_MANIFEST_SCHEMA1;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserLayer;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.Config;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestList;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestListManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import io.repsy.protocols.docker.shared.utils.DockerPushGuards;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractDockerProtocolTxFacade<ID>
    implements DockerProtocolFacade<ID>, DockerPathParserLayer, DockerPathParserManifest {

  private static final String MULTIPLATFORM = "Multiplatform";
  private static final String USAGES_PROPERTY = "usages";
  private static final String ARTIFACT_NAME_PROPERTY = "artifactName";
  private static final String ARTIFACT_VERSION_PROPERTY = "artifactVersion";

  protected final DockerStorageService<ID> dockerStorageService;
  protected final LayerService<ID> layerService;
  protected final ImageService<ID> imageService;
  protected final ManifestService<ID> manifestService;
  protected final ObjectMapper objectMapper;

  @Override
  public long uploadLayerChunk(
      final ProtocolContext context,
      final RelativePath relativePath,
      final InputStream inputStream,
      final long contentLength)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    final var chunkUsages =
        this.dockerStorageService.appendInputStreamToPath(
            repoInfo.getName(), storagePath, inputStream);

    ProtocolContextUtils.addUsages(context, chunkUsages);

    return this.getResource(repoInfo, relativePath).contentLength();
  }

  @Override
  public void verifyLayerDigest(
      final ProtocolContext context, final RelativePath relativePath, final String digest)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var resource = this.getResource(repoInfo, relativePath);

    if (!BlobDigests.matches(digest, resource.getInputStream())) {
      throw new BadRequestException("digestMismatch");
    }
  }

  @Override
  public long getUploadSize(final ProtocolContext context, final RelativePath relativePath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.getResource(repoInfo, relativePath).contentLength();
  }

  @Override
  public void finalizeLayerUpload(
      final ProtocolContext context, final RelativePath relativePath, final LayerInfo layerInfo)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var resource = this.getResource(repoInfo, relativePath);
    layerInfo.setSize(resource.contentLength());

    this.layerService.update(layerInfo, repoInfo.getId());
  }

  @Override
  public String saveManifest(
      final ProtocolContext context, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    this.checkRepoAllowOverride(repoInfo, form.getTagName(), imageInfo, form.getRelativePath());

    // DockerManifestValidator.validate already refused a Content-Type the registry does not
    // store, before anything for this push was looked up or written.
    final var usage =
        switch (form.getContentType()) {
          case OCI_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA2 ->
              this.createManifest(repoInfo, imageInfo, form);

          case OCI_IMAGE_INDEX, DOCKER_MANIFEST_LIST ->
              this.createManifestList(repoInfo, imageInfo, form);

          default -> throw new BadRequestException("manifestMediaTypeUnsupported");
        };

    if (usage != null) {

      context.addProperty(ARTIFACT_NAME_PROPERTY, imageInfo.getName());
      context.addProperty(ARTIFACT_VERSION_PROPERTY, form.getTagName());
      context.addProperty(USAGES_PROPERTY, usage);
    }

    return form.getDigest();
  }

  @Override
  public Resource getLayer(
      final ProtocolContext context, final String digest, final String servletPath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var layer = this.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), digest);

    final var parsedPath = this.parseForLayer(servletPath, layer.getDigest());

    if (!this.checkLayerExistsInStorage(repoInfo, parsedPath.getRelativePath(), layer)) {
      throw new ItemNotFoundException("layerNotFound");
    }

    return this.getLayerResource(layer.getDigest(), repoInfo, parsedPath.getRelativePath());
  }

  @Override
  public ManifestDetails getManifest(
      final ProtocolContext context,
      final String manifestReference,
      final String imageName,
      final String requestPath)
      throws IOException {

    return this.performDatabaseLookupForManifest(
        context, manifestReference, imageName, requestPath);
  }

  private @Nullable BaseUsages createManifest(
      final BaseRepoInfo<ID> repoInfo, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    if (this.isManifestExists(form.getDigest(), imageInfo.getId(), repoInfo.getId())) {
      return null;
    }

    final var manifestInfo =
        this.objectMapper.readValue(form.getManifestJson(), ManifestInfo.class);

    this.checkDeploymentRules(repoInfo, manifestInfo, form);

    if (this.isAttestationManifest(manifestInfo)) {
      return this.writeManifest(repoInfo, form);
    }

    // Extracted BEFORE the manifest is written: a config blob RPS-1116 refuses must leave nothing
    // on disk, so a rejected push cannot be found again by a later GET even though it was refused.
    final var platform = this.extractPlatform(repoInfo, manifestInfo.getConfig());

    final var usages = this.writeManifest(repoInfo, form);

    final var tagForm = TagForm.of(form, imageInfo.getName(), platform, manifestInfo);

    if (tagForm.isSinglePlatformByTagName()) {
      this.manifestService.createSinglePlatformManifest(repoInfo.getId(), imageInfo, tagForm);
    }

    return usages;
  }

  private boolean isAttestationManifest(final ManifestInfo manifestInfo) {

    return manifestInfo.getSubject() != null
        || OCI_EMPTY.equals(manifestInfo.getConfig().getMediaType());
  }

  private BaseUsages createManifestList(
      final BaseRepoInfo<ID> repoInfo, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    final var manifestList =
        this.objectMapper.readValue(form.getManifestJson(), ManifestList.class);

    final var platformManifests =
        this.findPlatformManifests(repoInfo, imageInfo, manifestList, form);

    final var usages = this.writeManifest(repoInfo, form);

    final var tagForm = TagForm.of(form, imageInfo.getName(), MULTIPLATFORM, manifestList);

    this.manifestService.createManifestList(
        repoInfo.getId(), imageInfo.getId(), tagForm, platformManifests);

    return usages;
  }

  private BaseUsages writeFileAndUpdateUsage(
      final InputStream inputStream,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService.writeInputStreamToPath(
        repoInfo.getName(), storagePath, inputStream);
  }

  private Resource getResource(final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService
        .getResource(storagePath, repoInfo.getName())
        .orElseThrow(() -> new ItemNotFoundException("resourceNotFound"));
  }

  private void checkRepoAllowOverride(
      final BaseRepoInfo<ID> repoInfo,
      final String tagName,
      final BaseImageInfo<ID> imageInfo,
      final RelativePath relativePath) {

    if (!repoInfo.isAllowOverride()) {
      final var existingTagOpt =
          this.findTagByNameAndRepoAndImageName(
              repoInfo, tagName, imageInfo.getName(), relativePath);

      if (existingTagOpt.isPresent()) {
        throw new AccessNotAllowedException("packageOverrideDisabled");
      }
    }
  }

  private Optional<BaseTagDetail<ID>> findTagByNameAndRepoAndImageName(
      final BaseRepoInfo<ID> repoInfo,
      final String tagName,
      final String imageName,
      final RelativePath relativePath) {

    final var tagOpt =
        this.manifestService.findActiveTagByNameAndRepoAndImage(
            repoInfo.getId(), imageName, tagName);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    final var isResourceExists =
        this.dockerStorageService.existsResource(storagePath, repoInfo.getName());

    return tagOpt.isPresent() && isResourceExists ? tagOpt : Optional.empty();
  }

  private boolean isManifestExists(final String manifestDigest, final ID imageId, final ID repoId) {

    return this.manifestService.findByDigestAndImageIdAndRepoId(manifestDigest, imageId, repoId);
  }

  private void checkDeploymentRules(
      final BaseRepoInfo<ID> repoInfo, final ManifestInfo manifestInfo, final ManifestForm form)
      throws IOException {

    this.verifyLayers(repoInfo.getId(), manifestInfo);

    this.verifyTag(form);
  }

  private BaseUsages writeManifest(final BaseRepoInfo<ID> repoInfo, final ManifestForm form)
      throws IOException {

    try (final var inputStream = new ByteArrayInputStream(form.getManifestBytes())) {
      return this.writeFileAndUpdateUsage(inputStream, repoInfo, form.getRelativePath());
    }
  }

  private void verifyLayers(final ID repoId, final ManifestInfo manifestInfo) {

    final var configDigest = manifestInfo.getConfig().getDigest();

    final var digests = manifestInfo.getLayerDigests();
    digests.add(configDigest);

    this.layerService.isAllExistsByRepoIdAndDigests(repoId, digests);
  }

  /**
   * Refuses a manifest pushed by a digest reference that is not the manifest's own digest. A sha256
   * reference must equal the digest this registry calculates; a reference of another supported
   * algorithm (sha512, RPS-1242) is checked by hashing the pushed bytes with that algorithm.
   */
  private void verifyTag(final ManifestForm form) throws IOException {

    final var tagName = form.getTagName();

    if (!BlobDigests.startsWithDigestPrefix(tagName) || tagName.equals(form.getDigest())) {
      return;
    }

    if (!tagName.startsWith(DockerConstants.SHA256_PREFIX)
        && BlobDigests.matches(tagName, new ByteArrayInputStream(form.getManifestBytes()))) {
      return;
    }

    throw new BadRequestException("digestMismatch");
  }

  /**
   * Resolves the platform a manifest is stored under. Only an <em>image config</em> media type
   * ({@code DOCKER_CONFIG_JSON}/{@code OCI_CONFIG_JSON}) is required to carry {@code os}/{@code
   * architecture}: any other config media type is an OCI artifact, legitimately without either, and
   * is stored under {@link DockerConstants#UNKNOWN_PLATFORM} (RPS-1116).
   */
  private String extractPlatform(final BaseRepoInfo<ID> repoInfo, final Config config)
      throws IOException {

    if (!isImageConfigMediaType(config.getMediaType())) {
      return DockerConstants.UNKNOWN_PLATFORM;
    }

    final var layer = this.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), config.getDigest());
    final var configJson = this.getConfig(repoInfo, layer);

    return parsePlatform(configJson);
  }

  private static boolean isImageConfigMediaType(final @Nullable String mediaType) {

    return DOCKER_CONFIG_JSON.equals(mediaType) || OCI_CONFIG_JSON.equals(mediaType);
  }

  /**
   * A config blob that is not JSON, or a JSON object without {@code os} or {@code architecture}, is
   * the client's mistake, not a server failure (RPS-1116): {@code org.json} throws a bare {@code
   * JSONException} for both, which is turned into a 400 that names the problem.
   */
  private static String parsePlatform(final String config) {

    final JSONObject json;
    try {
      json = new JSONObject(config);
    } catch (final JSONException _) {
      throw new BadRequestException("manifestConfigInvalid");
    }

    try {
      final var os = json.getString("os");
      final var architecture = json.getString("architecture");

      if (StringUtils.isBlank(os) || StringUtils.isBlank(architecture)) {
        throw new BadRequestException("manifestConfigInvalid");
      }

      final var platform = os + "/" + architecture;

      // RPS-1139: os/architecture come from the config blob, not the manifest JSON that
      // DockerManifestValidator already checked, so the length is guarded here, before it reaches
      // docker_manifest.platform.
      DockerPushGuards.rejectPlatformTooLong(platform);

      return platform;
    } catch (final JSONException _) {
      throw new BadRequestException("manifestConfigInvalid");
    }
  }

  private String getConfig(final BaseRepoInfo<ID> repoInfo, final LayerInfo layerInfo)
      throws IOException {

    Resource resource;

    try {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(DockerConstants.BLOBS, layerInfo.getDigest()).toString());

      resource = this.getResource(repoInfo, storagePath.getRelativePath());
    } catch (final ItemNotFoundException _) {
      final var storagePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              Paths.get(DockerConstants.BLOBS, layerInfo.getUuid().toString()).toString());

      resource = this.getResource(repoInfo, storagePath.getRelativePath());
    }

    return resource.getContentAsString(StandardCharsets.UTF_8);
  }

  private LayerInfo findLayerInfoByRepoIdAndDigest(final ID repoId, final String digest) {

    return this.layerService
        .findLayerInfoByRepoIdAndDigest(repoId, digest)
        .orElseThrow(() -> new ItemNotFoundException("layerNotFound"));
  }

  /**
   * Resolves each platform manifest a manifest-list push declares by DIGEST. Two real push shapes
   * reach here, and only one of them has a {@code Manifest} DB row reachable by the tag-joined
   * query {@link #performDatabaseLookupForManifest} also uses:
   *
   * <ul>
   *   <li>A child pushed under a TAG first (e.g. a real client's own per-platform {@code crane push
   *       ... :amd64} before combining it into an index): its storage file lives under that tag's
   *       own generated name, never a digest-generated one, so the DB row (found via {@link
   *       ManifestService#findManifestByRepoIdAndImageNameAndDigest}, using {@code
   *       manifest.getName()} for the filename) is the only way to resolve it correctly. Generating
   *       the lookup filename directly from the digest (this method's ORIGINAL implementation) only
   *       ever found this shape when a child had ALSO been separately re-pushed under its own
   *       digest as a distinct reference -- which happened to occur as a side effect of {@code
   *       AbstractDockerManifestCheckProtocolMethodHandler}'s old HEAD-by-digest bug (RPS-1215): a
   *       client whose HEAD-by-digest probe wrongly 404'd would defensively re-PUT each child under
   *       its digest before assembling the index, incidentally creating the very digest-keyed
   *       storage entry the digest-generated filename expected. Fixing that HEAD bug means a
   *       well-behaved client's HEAD-by-digest now correctly reports "already exists" and skips
   *       that re-PUT, exposing this gap -- confirmed live once RPS-1215 landed (a real `crane
   *       index append` started failing the final index PUT with 404 resourceNotFound, even though
   *       both children's HEAD/GET succeeded).
   *   <li>A child pushed directly BY digest reference, with no tag at all (a client that pushes
   *       each platform manifest as a bare {@code PUT .../manifests/sha256:<digest>}, never a named
   *       tag, then references it from an index -- {@code DockerManifestPushIT}'s own {@code
   *       wellFormedManifestIsStored} pins exactly this). Such a manifest has no {@code
   *       tagPlatform}/{@code tag} row at push time, so the tag-joined DB query can never find it
   *       -- its storage file only exists under the digest-generated filename, exactly what this
   *       method's ORIGINAL implementation looked up directly.
   * </ul>
   *
   * So this tries the DB-row resolution first (fixing the first shape, matching {@link
   * #performDatabaseLookupForManifest}), and falls back to the original digest-generated filename
   * lookup on {@link ItemNotFoundException} (keeping the second shape working, exactly as before).
   */
  private List<ManifestListManifestInfo> findPlatformManifests(
      final BaseRepoInfo<ID> repoInfo,
      final BaseImageInfo<ID> imageInfo,
      final ManifestList manifestList,
      final ManifestForm form)
      throws IOException {

    final var manifestInfoList = new ArrayList<ManifestListManifestInfo>();

    for (final var platformManifest : manifestList.getManifests()) {

      final var digest = platformManifest.getDigest();

      final var manifestResource =
          this.resolvePlatformManifestResource(repoInfo, imageInfo, digest, form);
      final var manifestInfo =
          this.objectMapper.readValue(
              manifestResource.getContentAsByteArray(), ManifestListManifestInfo.class);

      final var platform = platformManifest.getPlatform();

      manifestInfo.setDigest(digest);
      // An index entry without a platform is legitimate (RPS-1117: an index grouping an artifact
      // and its referrers, not per-platform images), and shares one tag_platform row with every
      // other platform-less entry of the same push.
      manifestInfo.setPlatform(
          platform != null ? platform.toString() : DockerConstants.UNKNOWN_PLATFORM);

      manifestInfoList.add(manifestInfo);
    }

    return manifestInfoList;
  }

  private Resource resolvePlatformManifestResource(
      final BaseRepoInfo<ID> repoInfo,
      final BaseImageInfo<ID> imageInfo,
      final String digest,
      final ManifestForm form)
      throws IOException {

    try {
      final var manifest =
          this.manifestService.findManifestByRepoIdAndImageNameAndDigest(
              repoInfo.getId(), imageInfo, digest);

      final var fileName =
          generate(repoInfo.getStorageKey(), imageInfo.getName(), manifest.getName());
      final var parsedPath = this.parseForManifest(form.getServletPath(), fileName);

      return this.getResource(repoInfo, parsedPath.getRelativePath());
    } catch (final ItemNotFoundException e) {

      final var fileName = generate(repoInfo.getStorageKey(), imageInfo.getName(), digest);
      final var parsedPath = this.parseForManifest(form.getServletPath(), fileName);

      return this.getResource(repoInfo, parsedPath.getRelativePath());
    }
  }

  private boolean checkLayerExistsInStorage(
      final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo) {

    final var idx = BlobDigests.indexOfDigestPrefix(relativePath.getPath());

    if (this.checkLayerForSha(idx, repoInfo, relativePath, layerInfo)) {
      return true;
    }

    return this.checkLayerForUuid(idx, repoInfo, relativePath, layerInfo);
  }

  private boolean checkLayerForUuid(
      final int idx,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath,
      final LayerInfo layerInfo) {

    final var mutPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getUuid().toString()
            : relativePath.getPath();

    final var sp = StoragePath.of(repoInfo.getStorageKey(), mutPath);

    return this.dockerStorageService.existsResource(sp, repoInfo.getName());
  }

  private boolean checkLayerForSha(
      final int idx,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath,
      final LayerInfo layerInfo) {

    final var mutatedPath =
        idx > 0
            ? relativePath.getPath().substring(0, idx) + layerInfo.getDigest()
            : relativePath.getPath();

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), mutatedPath);

    return this.dockerStorageService.existsResource(storagePath, repoInfo.getName());
  }

  private ManifestDetails performDatabaseLookupForManifest(
      final ProtocolContext context,
      final String manifestReference,
      final String imageName,
      final String requestPath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var imageInfo =
        this.imageService.findImageInfoByRepoIdAndName(repoInfo.getId(), imageName);

    final var digest = this.resolveManifestDigest(repoInfo, imageName, manifestReference);

    final var manifest =
        this.manifestService.findManifestByRepoIdAndImageNameAndDigest(
            repoInfo.getId(), imageInfo, digest);

    final var fileName = generate(repoInfo.getStorageKey(), imageName, manifest.getName());

    final var parsedPath = this.parseForManifest(requestPath, fileName);

    final var manifestResource = this.getResource(repoInfo, parsedPath.getRelativePath());
    final var manifestStr = manifestResource.getContentAsString(StandardCharsets.UTF_8);

    return new ManifestDetails(manifest.getMediaType(), manifest.getDigest(), manifestStr);
  }

  private String resolveManifestDigest(
      final BaseRepoInfo<ID> repoInfo, final String imageName, final String reference) {

    if (reference.startsWith(DockerConstants.SHA256_PREFIX)) {
      return reference;
    }

    return this.manifestService
        .findActiveTagByNameAndRepoAndImage(repoInfo.getId(), imageName, reference)
        .map(BaseTagDetail::getDigest)
        .orElseThrow(() -> new ItemNotFoundException("tagNotFound"));
  }

  private Resource getLayerResource(
      final String digest, final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    final var idx = BlobDigests.indexOfDigestPrefix(relativePath.getPath());

    final var mutatedPath =
        idx > 0 ? relativePath.getPath().substring(0, idx) + digest : relativePath.getPath();

    return this.getResource(repoInfo, new RelativePath(mutatedPath));
  }
}
