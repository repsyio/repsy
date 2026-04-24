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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
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
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
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

  private final @NonNull ImageTxService imageService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull DockerApiFacade dockerApiFacade;
  private final @NonNull TagDeletionComponent tagDeletionComponent;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull RestResponseFactory restResponseFactory;

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<ImageListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.imageService.findAllByRepoIdAndContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success("imagesFetched", new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{imageName}/tags")
  @RepoOperation
  public RestResponse<PagedModel<ImageTagListItem>> listTags(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var imageTags =
        this.manifestService.getImageTagsContainsName(
            repoInfo.getStorageKey(), imageName, name, pageable);

    return this.restResponseFactory.success("imageTagsFetched", new PagedModel<>(imageTags));
  }

  @DeleteMapping("/{repoName}/{imageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> delete(final RepoInfo repoInfo, @PathVariable final String imageName) {

    final var usages = this.dockerApiFacade.deleteImage(repoInfo, imageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("imageDeleted");
  }

  @GetMapping({"/{repoName}/{imageName}", "/{repoName}/{imageName}/tags/{tagName}"})
  @RepoOperation
  public RestResponse<TagDetail> getTagDetail(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @PathVariable final String tagName) {

    final var tagDetail =
        this.dockerApiFacade.getTagDetail(repoInfo.getStorageKey(), imageName, tagName);

    return this.restResponseFactory.success("tagDetailFetched", tagDetail);
  }

  @DeleteMapping("/{repoName}/{imageName}/tags/{tagName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteTag(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @PathVariable final String tagName) {

    final var usages = this.tagDeletionComponent.deleteTag(repoInfo, imageName, tagName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("tagDeleted");
  }

  @GetMapping("/{repoName}/{imageName}/tags/{tagName}/manifests")
  @RepoOperation
  public RestResponse<PagedModel<ManifestListItem>> listTagManifests(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @PathVariable final String tagName,
      @RequestParam(required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var tagLayers =
        this.dockerApiFacade.getTagManifestsLikeName(repoInfo, imageName, tagName, name, pageable);

    return this.restResponseFactory.success("tagLayersFetched", new PagedModel<>(tagLayers));
  }

  @GetMapping("/{repoName}/{imageName}/manifests/{reference}")
  @RepoOperation
  public RestResponse<String> getManifest(
      final RepoInfo repoInfo,
      @PathVariable final String imageName,
      @PathVariable final String reference)
      throws IOException {

    final var tag =
        this.manifestService.findActiveTagByRepoAndDigest(
            repoInfo.getStorageKey(), imageName, reference);

    final var fileName =
        ManifestNameGenerator.generate(repoInfo.getStorageKey(), imageName, tag.getName());

    final var manifest = this.dockerApiFacade.getManifest(repoInfo, fileName);

    return this.restResponseFactory.success("manifestFetched", manifest);
  }

  @GetMapping("/{repoName}/{ignoredImageName}/configs/{digest}")
  @RepoOperation
  public RestResponse<String> getConfig(
      final RepoInfo repoInfo,
      @PathVariable final String ignoredImageName,
      @PathVariable final String digest)
      throws IOException {

    final var layer = this.dockerApiFacade.findLayerByDigestAndRepoAndImageName(repoInfo, digest);

    final var config = this.dockerApiFacade.getConfig(repoInfo, layer.getDigest());

    return this.restResponseFactory.success("configFetched", config);
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
