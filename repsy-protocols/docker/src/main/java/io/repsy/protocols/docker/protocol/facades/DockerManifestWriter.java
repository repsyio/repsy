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

import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA1;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_MANIFEST_SCHEMA1;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.shared.constants.DockerConstants;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestList;
import io.repsy.protocols.docker.shared.tag.dtos.OciImageConfig;
import io.repsy.protocols.docker.shared.tag.dtos.SavedManifest;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * The manifest push of the Docker facade: the checks that need no image, the image row, the
 * platform of the config, the manifest file and the rows, in the order the push depends on.
 */
@RequiredArgsConstructor
final class DockerManifestWriter<ID> {

  private static final String MULTIPLATFORM = "Multiplatform";
  private static final String USAGES_PROPERTY = "usages";
  private static final String ARTIFACT_NAME_PROPERTY = "artifactName";
  private static final String ARTIFACT_VERSION_PROPERTY = "artifactVersion";

  private final DockerStorageService<ID> dockerStorageService;
  private final LayerService<ID> layerService;
  private final ImageService<ID> imageService;
  private final ManifestService<ID> manifestService;
  private final ObjectMapper objectMapper;
  private final DockerBlobResolver<ID> blobs;

  SavedManifest<ID> saveManifest(
      final ProtocolContext context, final String imageName, final ManifestForm form)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    this.verifyReference(form);
    this.checkRepoAllowOverride(repoInfo, form.getTagName(), imageName, form.getDigest());

    // Created in this transaction, after every check that needs no image: a push that fails from
    // here on rolls the new image back with everything else, so it never leaves an image that
    // stores no manifest (RPS-1350).
    final var imageInfo = this.imageService.getOrCreateImage(repoInfo.getId(), imageName);

    // DockerManifestValidator.validate already refused a Content-Type the registry does not
    // store, before anything for this push was looked up or written.
    final var usage =
        switch (form.getContentType()) {
          case OCI_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA1, DOCKER_MANIFEST_SCHEMA2 ->
              this.createManifest(repoInfo, imageInfo, form);

          case OCI_IMAGE_INDEX, DOCKER_MANIFEST_LIST ->
              this.createManifestList(repoInfo, imageInfo, form);

          default ->
              throw new BadRequestException(ProtocolErrorCodes.MANIFEST_MEDIA_TYPE_UNSUPPORTED);
        };

    context.addProperty(ARTIFACT_NAME_PROPERTY, imageInfo.getName());
    context.addProperty(ARTIFACT_VERSION_PROPERTY, form.getTagName());
    context.addProperty(USAGES_PROPERTY, usage);

    return new SavedManifest<>(form.getDigest(), imageInfo);
  }

  BaseUsages createManifest(
      final BaseRepoInfo<ID> repoInfo, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    final var manifestInfo =
        this.objectMapper.readValue(form.getManifestJson(), ManifestInfo.class);

    this.verifyLayers(repoInfo.getId(), manifestInfo);

    // Extracted BEFORE the manifest is written: a config blob RPS-1116 refuses must leave nothing
    // on disk, so a rejected push cannot be found again by a later GET even though it was refused.
    final var platform =
        DockerManifestParser.isAttestationManifest(manifestInfo)
            ? DockerConstants.UNKNOWN_PLATFORM
            : this.extractPlatform(repoInfo, manifestInfo.getConfig());

    final var usages = this.writeManifest(repoInfo, form);

    final var tagForm = TagForm.of(form, imageInfo.getName(), platform, manifestInfo);

    this.manifestService.createSinglePlatformManifest(repoInfo.getId(), imageInfo, tagForm);

    return usages;
  }

  BaseUsages createManifestList(
      final BaseRepoInfo<ID> repoInfo, final BaseImageInfo<ID> imageInfo, final ManifestForm form)
      throws IOException {

    final var manifestList =
        this.objectMapper.readValue(form.getManifestJson(), ManifestList.class);

    final var tagForm = TagForm.of(form, imageInfo.getName(), MULTIPLATFORM, manifestList);

    // Before the index is written: an index that references a manifest the image does not have
    // must leave nothing behind.
    this.manifestService.verifyManifestsExist(
        repoInfo.getId(), imageInfo.getId(), tagForm.getManifestDigests());

    final var usages = this.writeManifest(repoInfo, form);

    this.manifestService.createManifestList(repoInfo.getId(), imageInfo.getId(), tagForm);

    return usages;
  }

  BaseUsages writeFileAndUpdateUsage(
      final InputStream inputStream,
      final BaseRepoInfo<ID> repoInfo,
      final RelativePath relativePath) {

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.dockerStorageService.writeInputStreamToPath(
        repoInfo.getName(), storagePath, inputStream);
  }

  /**
   * Refuses a tag push that would move an existing tag to another manifest while the repo forbids
   * overriding. Pushing the manifest a tag already points at changes nothing and is accepted, and
   * so is any push by digest: there is no tag to move.
   */
  void checkRepoAllowOverride(
      final BaseRepoInfo<ID> repoInfo,
      final String reference,
      final String imageName,
      final String digest) {

    if (repoInfo.isAllowOverride()) {
      return;
    }

    final var existingTag =
        this.manifestService.findActiveTagByNameAndRepoAndImage(
            repoInfo.getId(), imageName, reference);

    if (existingTag.isPresent() && !digest.equals(existingTag.get().getDigest())) {
      throw new AccessNotAllowedException(ProtocolErrorCodes.PACKAGE_OVERRIDE_DISABLED);
    }
  }

  BaseUsages writeManifest(final BaseRepoInfo<ID> repoInfo, final ManifestForm form)
      throws IOException {

    try (final var inputStream = new ByteArrayInputStream(form.getManifestBytes())) {
      return this.writeFileAndUpdateUsage(inputStream, repoInfo, form.getRelativePath());
    }
  }

  void verifyLayers(final ID repoId, final ManifestInfo manifestInfo) {

    final var configDigest = manifestInfo.getConfig().getDigest();

    // A manifest may name one blob more than once (RPS-1490): its config as one of its layers (the
    // empty {} descriptor of `oras push` without files) and two layers of identical bytes. The
    // stored blob is one row, so the lookup is by distinct digest.
    final var distinctDigests = new LinkedHashSet<>(manifestInfo.getLayerDigests());
    distinctDigests.add(configDigest);
    final var digests = List.copyOf(distinctDigests);

    this.layerService.isAllExistsByRepoIdAndDigests(repoId, digests);
  }

  /**
   * Refuses a manifest pushed by a digest reference that is not the manifest's own digest, of the
   * algorithm the reference names: the registry calculates both the {@code sha256} and the {@code
   * sha512} digest of the pushed bytes (RPS-1242 made {@code sha512} references routable).
   */
  void verifyReference(final ManifestForm form) {

    final var reference = form.getTagName();

    if (!BlobDigests.startsWithDigestPrefix(reference)) {
      return;
    }

    final var digest = DockerDigestCalculator.normalize(reference);

    if (!digest.equals(form.getDigest()) && !digest.equals(form.getDigestSha512())) {
      throw new BadRequestException(ProtocolErrorCodes.DIGEST_MISMATCH);
    }
  }

  /**
   * Resolves the platform a manifest is stored under. Only an <em>image config</em> media type
   * ({@code DOCKER_CONFIG_JSON}/{@code OCI_CONFIG_JSON}) is required to carry {@code os}/{@code
   * architecture}: any other config media type is an OCI artifact, legitimately without either, and
   * is stored under {@link DockerConstants#UNKNOWN_PLATFORM} (RPS-1116).
   */
  String extractPlatform(final BaseRepoInfo<ID> repoInfo, final OciImageConfig config)
      throws IOException {

    if (!DockerManifestParser.isImageConfigMediaType(config.getMediaType())) {
      return DockerConstants.UNKNOWN_PLATFORM;
    }

    final var layer =
        this.blobs.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), config.getDigest());
    final var configJson = this.blobs.getConfig(repoInfo, layer);

    return DockerManifestParser.parsePlatform(configJson);
  }
}
