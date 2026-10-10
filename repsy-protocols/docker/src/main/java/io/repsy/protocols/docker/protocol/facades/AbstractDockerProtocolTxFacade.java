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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserLayer;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.SavedManifest;
import io.repsy.protocols.docker.shared.tag.dtos.TagPage;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerTagPaging;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

/**
 * The transactional persistence layer of the Docker protocol. It is the one class a product extends
 * (the backend's {@code DockerProtocolFacade}); the work is split by concern into {@link
 * DockerManifestWriter} (push), {@link DockerManifestReader} (pull) and {@link DockerBlobResolver}
 * (files and layer rows), and this class keeps the layer upload and the tag list. The constructor
 * and the protected collaborators are the contract of a subclass.
 */
@NullMarked
public abstract class AbstractDockerProtocolTxFacade<ID>
    implements DockerProtocolFacade<ID>, DockerPathParserLayer, DockerPathParserManifest {

  protected final DockerStorageService<ID> dockerStorageService;
  protected final LayerService<ID> layerService;
  protected final ImageService<ID> imageService;
  protected final ManifestService<ID> manifestService;
  protected final ObjectMapper objectMapper;

  private final DockerBlobResolver<ID> blobs;
  private final DockerManifestWriter<ID> manifestWriter;
  private final DockerManifestReader<ID> manifestReader;

  protected AbstractDockerProtocolTxFacade(
      final DockerStorageService<ID> dockerStorageService,
      final LayerService<ID> layerService,
      final ImageService<ID> imageService,
      final ManifestService<ID> manifestService,
      final ObjectMapper objectMapper) {

    this.dockerStorageService = dockerStorageService;
    this.layerService = layerService;
    this.imageService = imageService;
    this.manifestService = manifestService;
    this.objectMapper = objectMapper;

    this.blobs = new DockerBlobResolver<>(dockerStorageService, layerService);
    this.manifestWriter =
        new DockerManifestWriter<>(
            dockerStorageService,
            layerService,
            imageService,
            manifestService,
            objectMapper,
            this.blobs);
    this.manifestReader =
        new DockerManifestReader<>(imageService, manifestService, this.blobs, this);
  }

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

    return this.blobs.getResource(repoInfo, relativePath).contentLength();
  }

  @Override
  public void verifyLayerDigest(
      final ProtocolContext context, final RelativePath relativePath, final String digest)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var resource = this.blobs.getResource(repoInfo, relativePath);

    if (!BlobDigests.matches(digest, resource.getInputStream())) {
      throw new BadRequestException(ProtocolErrorCodes.DIGEST_MISMATCH);
    }
  }

  @Override
  public long getUploadSize(final ProtocolContext context, final RelativePath relativePath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.blobs.getResource(repoInfo, relativePath).contentLength();
  }

  @Override
  public void finalizeLayerUpload(
      final ProtocolContext context, final RelativePath relativePath, final LayerInfo layerInfo)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var resource = this.blobs.getResource(repoInfo, relativePath);
    layerInfo.setSize(resource.contentLength());

    this.layerService.update(layerInfo, repoInfo.getId());
  }

  @Override
  public SavedManifest<ID> saveManifest(
      final ProtocolContext context, final String imageName, final ManifestForm form)
      throws IOException {

    return this.manifestWriter.saveManifest(context, imageName, form);
  }

  @Override
  public Resource getLayer(
      final ProtocolContext context, final String digest, final String servletPath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var layer = this.blobs.findLayerInfoByRepoIdAndDigest(repoInfo.getId(), digest);

    final var parsedPath = this.parseForLayer(servletPath, layer.getDigest());

    if (!this.blobs.checkLayerExistsInStorage(repoInfo, parsedPath.getRelativePath(), layer)) {
      throw new ItemNotFoundException(ProtocolErrorCodes.LAYER_NOT_FOUND);
    }

    return this.blobs.getLayerResource(layer.getDigest(), repoInfo, parsedPath.getRelativePath());
  }

  @Override
  public ManifestDetails getManifest(
      final ProtocolContext context,
      final String manifestReference,
      final String imageName,
      final String requestPath)
      throws IOException {

    return this.manifestReader.getManifest(context, manifestReference, imageName, requestPath);
  }

  @Override
  public TagPage listTags(
      final ProtocolContext context,
      final String imageName,
      final @Nullable Integer limit,
      final @Nullable String last) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var imageInfo =
        this.imageService.getImageInfoByRepoIdAndName(repoInfo.getId(), imageName);

    return DockerTagPaging.page(
        this.manifestService.findTagNamesByImageId(imageInfo.getId()), limit, last);
  }
}
