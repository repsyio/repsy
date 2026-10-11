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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.parser.DockerPathParserManifest;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.tag.dtos.BaseManifestDetail;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;

/** The manifest read of the Docker facade: a reference to a digest to the manifest's file. */
@RequiredArgsConstructor
final class DockerManifestReader<ID> {

  private final ImageService<ID> imageService;
  private final ManifestService<ID> manifestService;
  private final DockerBlobResolver<ID> blobs;
  private final DockerPathParserManifest manifestPaths;

  ManifestDetails getManifest(
      final ProtocolContext context,
      final String manifestReference,
      final String imageName,
      final String requestPath)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var imageInfo =
        this.imageService.getImageInfoByRepoIdAndName(repoInfo.getId(), imageName);

    final var digest = this.resolveManifestDigest(repoInfo, imageName, manifestReference);

    final var manifest =
        this.manifestService.getManifestByRepoIdAndImageNameAndDigest(
            repoInfo.getId(), imageInfo, digest);

    final var manifestResource =
        this.getManifestResource(repoInfo, imageName, manifest, requestPath);
    final var manifestStr = manifestResource.getContentAsString(StandardCharsets.UTF_8);

    // The digest reported is in the algorithm the client asked for: a reference by sha512 gets
    // the sha512 digest back, a tag or a sha256 reference the canonical sha256 one (RPS-1244).
    return new ManifestDetails(
        manifest.getMediaType(),
        DockerDigestCalculator.reportedDigest(manifestReference, manifest.getDigest()),
        manifestStr);
  }

  /**
   * Reads the manifest's file: at its digest, or, for a manifest an earlier version stored, under
   * the legacy name generated from its {@code storage_name} until the repair service has renamed
   * it. Both are tried, so a file that was renamed just before its row was updated is still found.
   */
  Resource getManifestResource(
      final BaseRepoInfo<ID> repoInfo,
      final String imageName,
      final BaseManifestDetail<ID> manifest,
      final String requestPath) {

    if (manifest.getStorageName() != null) {
      final var legacyName =
          generate(repoInfo.getStorageKey(), imageName, manifest.getStorageName());
      final var legacyPath =
          this.manifestPaths.parseForManifest(requestPath, legacyName).getRelativePath();

      if (this.blobs.existsResource(repoInfo, legacyPath)) {
        return this.blobs.getResource(repoInfo, legacyPath);
      }
    }

    final var path =
        this.manifestPaths.parseForManifest(requestPath, manifest.getDigest()).getRelativePath();

    return this.blobs.getResource(repoInfo, path);
  }

  String resolveManifestDigest(
      final BaseRepoInfo<ID> repoInfo, final String imageName, final String reference) {

    if (BlobDigests.startsWithDigestPrefix(reference)) {
      return DockerDigestCalculator.normalize(reference);
    }

    return this.manifestService
        .findActiveTagByNameAndRepoAndImage(repoInfo.getId(), imageName, reference)
        .map(BaseTagDetail::getDigest)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.TAG_NOT_FOUND));
  }
}
