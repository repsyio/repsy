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
package io.repsy.os.server.protocols.docker.ui.controllers;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.ImageListItem;
import io.repsy.os.generated.model.ImageTagListItem;
import io.repsy.os.generated.model.ManifestListItem;
import io.repsy.os.generated.model.TagDetail;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.TagDeletionComponent;
import io.repsy.os.server.protocols.docker.ui.facades.DockerApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/docker/images")
@SuppressWarnings("java:S6856")
public class DockerImageController {

  private static final Set<String> IMAGE_SORT_PROPERTIES =
      Set.of("id", "name", "updatedAt", "lastUpdatedAt");

  private static final Set<String> TAG_SORT_PROPERTIES = Set.of("id", "name", "createdAt");

  private static final Set<String> MANIFEST_SORT_PROPERTIES = Set.of("id", "name", "createdAt");

  private final @NonNull ImageTxService imageService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull DockerApiFacade dockerApiFacade;
  private final @NonNull TagDeletionComponent tagDeletionComponent;
  private final @NonNull UsageUpdateService usageUpdateService;

  /**
   * The image a nested route addresses: the {@code image} query parameter when given (a name with
   * several segments such as {@code team/app}, with {@code -} as {@code {imageName}}; a lone {@code
   * -} is not a valid OCI name, so it never collides with a real image), else the {@code
   * {imageName}} path segment.
   */
  private static String imageNameOf(final String imageName, final String image) {

    return image == null || image.isBlank() ? imageName : image;
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<ImageListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, IMAGE_SORT_PROPERTIES);

    final var packages =
        this.imageService.findAllByRepoIdAndContainsName(repoInfo.getStorageKey(), name, pageable);

    return ResponseEntity.ok(new PagedModel<>(packages));
  }

  /**
   * The image as the list shows it (tags, untagged manifests, their size): what the image's page
   * says when the image has no tag left. A tag's detail is {@code .../tags/{tagName}}.
   */
  @GetMapping("/{repoName}/{imageName}")
  @RepoOperation
  public ResponseEntity<ImageListItem> getImage(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image) {

    final var item =
        this.imageService.findListItemByRepoIdAndName(
            repoInfo.getStorageKey(), imageNameOf(imageName, image));

    return ResponseEntity.ok(item);
  }

  @DeleteMapping("/{repoName}/{imageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image) {

    final var usages = this.dockerApiFacade.deleteImage(repoInfo, imageNameOf(imageName, image));

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{imageName}/tags")
  @RepoOperation
  public ResponseEntity<PagedModel<ImageTagListItem>> listTags(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @RequestParam(name = "q", required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, TAG_SORT_PROPERTIES);

    final var resolvedName = imageNameOf(imageName, image);

    // An image that does not exist answers 404 imageNotFound like every other image route, not an
    // empty page (RPS-1579).
    this.imageService.findImageInfoByRepoIdAndName(repoInfo.getStorageKey(), resolvedName);

    final var imageTags =
        this.manifestService.getImageTagsContainsName(
            repoInfo.getStorageKey(), resolvedName, name, pageable);

    return ResponseEntity.ok(new PagedModel<>(imageTags));
  }

  @GetMapping("/{repoName}/{imageName}/tags/{tagName}")
  @RepoOperation
  public ResponseEntity<TagDetail> getTagDetail(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @PathVariable final String tagName) {

    final var tagDetail =
        this.dockerApiFacade.getTagDetail(
            repoInfo.getStorageKey(), imageNameOf(imageName, image), tagName);

    return ResponseEntity.ok(tagDetail);
  }

  @DeleteMapping("/{repoName}/{imageName}/tags/{tagName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteTag(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @PathVariable final String tagName) {

    this.tagDeletionComponent.deleteTag(repoInfo, imageNameOf(imageName, image), tagName);

    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{imageName}/tags/{tagName}/manifests")
  @RepoOperation
  public ResponseEntity<PagedModel<ManifestListItem>> listTagManifests(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @PathVariable final String tagName,
      @RequestParam(name = "q", required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, MANIFEST_SORT_PROPERTIES);

    final var tagLayers =
        this.dockerApiFacade.getTagManifestsLikeName(
            repoInfo, imageNameOf(imageName, image), tagName, name, pageable);

    return ResponseEntity.ok(new PagedModel<>(tagLayers));
  }

  /** The manifest as stored, as a JSON string literal (the stored bytes stay exact). */
  @GetMapping(
      value = "/{repoName}/{imageName}/manifests/{reference}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RepoOperation
  public ResponseEntity<String> getManifest(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @PathVariable final String reference)
      throws IOException {

    final var fileNames =
        this.manifestService.findManifestFileNamesByReference(
            repoInfo.getStorageKey(), imageNameOf(imageName, image), reference);

    return ResponseEntities.jsonString(this.dockerApiFacade.getManifest(repoInfo, fileNames));
  }

  /** The config blob as stored, as a JSON string literal. */
  @GetMapping(
      value = "/{repoName}/{imageName}/configs/{digest}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RepoOperation
  public ResponseEntity<String> getConfig(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(name = "image", required = false) final String image,
      @PathVariable final String digest)
      throws IOException {

    final var layer =
        this.dockerApiFacade.findConfigLayerByImageAndDigest(
            repoInfo, imageNameOf(imageName, image), digest);

    return ResponseEntities.jsonString(this.dockerApiFacade.getConfig(repoInfo, layer.getDigest()));
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
