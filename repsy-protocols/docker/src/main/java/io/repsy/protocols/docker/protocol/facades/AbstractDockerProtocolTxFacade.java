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
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA1;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
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
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestList;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestListManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
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
import org.json.JSONObject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractDockerProtocolTxFacade<ID>
    implements DockerProtocolFacade<ID>, DockerPathParserLayer, DockerPathParserManifest {

  private static final String MULTIPLATFORM = "Multiplatform";
  private static final String USAGES_PROPERTY = "usages";

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
      final long contentLength) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var afterUploadUsage = this.writeFileAndUpdateUsage(inputStream, repoInfo, relativePath);

    context.addProperty(USAGES_PROPERTY, afterUploadUsage);

    return afterUploadUsage.getDiskUsage();
  }

  @Override
  public void finalizeLayerUpload(
      final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo)
      throws IOException {

    final var resource = this.getResource(repoInfo, relativePath);
    layerInfo.setSize(resource.contentLength());

    this.layerService.update(layerInfo, repoInfo.getId());
  }

  @Override
  public @Nullable String saveManifest(
      final ProtocolContext context, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    this.checkRepoAllowOverride(repoInfo, form.getTagName(), imageInfo, form.getRelativePath());

    final var usage =
        switch (form.getContentType()) {
          case OCI_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA2 ->
              this.createManifest(repoInfo, imageInfo, form);

          case OCI_IMAGE_INDEX, DOCKER_MANIFEST_LIST ->
              this.createManifestList(repoInfo, imageInfo, form);

          default -> throw new IllegalArgumentException("unsupportedMediaType");
        };

    if (usage != null) {
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

  @Override
  public Pair<Optional<BaseTagDetail<ID>>, String> findTagAndManifest(
      final ProtocolContext context,
      final String reference,
      final String imageName,
      final RelativePath relativePath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var tagOpt =
        this.findTagByNameAndRepoAndImageName(repoInfo, reference, imageName, relativePath);

    if (tagOpt.isEmpty()) {
      throw new ItemNotFoundException("tagNotFound");
    }

    final var manifest = this.getManifestStr(repoInfo, relativePath);

    return Pair.of(tagOpt, manifest);
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

    final var usages = this.writeManifest(repoInfo, form);

    final var platform = this.extractPlatform(repoInfo, manifestInfo.getConfig().getDigest());
    final var tagForm = TagForm.of(form, imageInfo.getName(), platform, manifestInfo);

    if (tagForm.isSinglePlatformByTagName()) {
      this.manifestService.createSinglePlatformManifest(repoInfo.getId(), imageInfo, tagForm);
    }

    return usages;
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
      final BaseRepoInfo<ID> repoInfo, final ManifestInfo manifestInfo, final ManifestForm form) {

    this.verifyLayers(repoInfo.getId(), manifestInfo);

    this.verifyTag(form.getTagName(), form.getDigest());
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

  private void verifyTag(final String tagName, final String digest) {

    if (tagName.startsWith(DockerConstants.SHA256_PREFIX) && !tagName.equals(digest)) {
      throw new BadRequestException("digestMismatch");
    }
  }

  private String extractPlatform(final BaseRepoInfo<ID> repoInfo, final String configDigest)
      throws IOException {

    final var layer = this.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), configDigest);

    final var config = this.getConfig(repoInfo, layer);
    final var os = new JSONObject(config).getString("os");
    final var platform = new JSONObject(config).getString("architecture");

    return os + "/" + platform;
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

  private List<ManifestListManifestInfo> findPlatformManifests(
      final BaseRepoInfo<ID> repoInfo,
      final BaseImageInfo<ID> imageInfo,
      final ManifestList manifestList,
      final ManifestForm form)
      throws IOException {

    final var manifestInfoList = new ArrayList<ManifestListManifestInfo>();

    for (final var platformManifest : manifestList.getManifests()) {

      final var digest = platformManifest.getDigest();

      final var fileName = generate(repoInfo.getStorageKey(), imageInfo.getName(), digest);
      final var parsedPath = this.parseForManifest(form.getServletPath(), fileName);

      final var manifestResource = this.getResource(repoInfo, parsedPath.getRelativePath());
      final var manifestInfo =
          this.objectMapper.readValue(
              manifestResource.getContentAsByteArray(), ManifestListManifestInfo.class);

      manifestInfo.setDigest(digest);
      manifestInfo.setPlatform(platformManifest.getPlatform().toString());

      manifestInfoList.add(manifestInfo);
    }

    return manifestInfoList;
  }

  private boolean checkLayerExistsInStorage(
      final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath, final LayerInfo layerInfo) {

    final var idx = relativePath.getPath().indexOf(DockerConstants.SHA256_PREFIX);

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
      final String manifestDigest,
      final String imageName,
      final String requestPath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var imageInfo =
        this.imageService.findImageInfoByRepoIdAndName(repoInfo.getId(), imageName);

    final var manifest =
        this.manifestService.findManifestByRepoIdAndImageNameAndDigest(
            repoInfo.getId(), imageInfo, manifestDigest);

    final var fileName = generate(repoInfo.getStorageKey(), imageName, manifest.getName());

    final var parsedPath = this.parseForManifest(requestPath, fileName);

    final var manifestResource = this.getResource(repoInfo, parsedPath.getRelativePath());
    final var manifestStr = manifestResource.getContentAsString(StandardCharsets.UTF_8);

    return new ManifestDetails(manifest.getMediaType(), manifest.getDigest(), manifestStr);
  }

  private Resource getLayerResource(
      final String digest, final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath) {

    final var idx = relativePath.getPath().indexOf(DockerConstants.SHA256_PREFIX);

    final var mutatedPath =
        idx > 0 ? relativePath.getPath().substring(0, idx) + digest : relativePath.getPath();

    return this.getResource(repoInfo, new RelativePath(mutatedPath));
  }

  private String getManifestStr(final BaseRepoInfo<ID> repoInfo, final RelativePath relativePath)
      throws IOException {

    final var manifestResource = this.getResource(repoInfo, relativePath);

    return manifestResource.getContentAsString(StandardCharsets.UTF_8);
  }
}
