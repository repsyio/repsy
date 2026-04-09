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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.docker.shared.auth.services.DockerAuthComponent;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageListItem;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.ManifestListItem;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.ImageTagListItem;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.TagDetail;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.TagDeletionComponent;
import io.repsy.os.server.protocols.docker.ui.facades.DockerApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/docker/images")
public class DockerImageController {

  private final @NonNull DockerAuthComponent dockerAuthComponent;

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull ImageTxService imageService;
  private final @NonNull ManifestTxService manifestService;
  private final @NonNull DockerApiFacade dockerApiFacade;
  private final @NonNull TagDeletionComponent tagDeletionComponent;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull RestResponseFactory restResponseFactory;

  @GetMapping("/{repoName}")
  public @NonNull RestResponse<PagedModel<ImageListItem>> getImages(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var packages =
        this.imageService.findAllByRepoIdAndContainsName(repoInfo.getStorageKey(), name, pageable);

    return this.restResponseFactory.success("imagesFetched", new PagedModel<>(packages));
  }

  @GetMapping("/{repoName}/{imageName}/tags")
  public @NonNull RestResponse<PagedModel<ImageTagListItem>> getImageTags(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var imageTags =
        this.manifestService.getImageTagsContainsName(
            repoInfo.getStorageKey(), imageName, name, pageable);

    return this.restResponseFactory.success("imageTagsFetched", new PagedModel<>(imageTags));
  }

  @DeleteMapping("/{repoName}/{imageName}")
  public @NonNull RestResponse<Void> deleteImage(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.dockerApiFacade.deleteImage(repoInfo, imageName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("imageDeleted");
  }

  @GetMapping({"/{repoName}/{imageName}", "/{repoName}/{imageName}/tags/{tagName}"})
  public @NonNull RestResponse<TagDetail> getTagDetail(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName,
      @PathVariable final @NonNull String tagName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var tagDetail =
        this.dockerApiFacade.getTagDetail(repoInfo.getStorageKey(), imageName, tagName);

    return this.restResponseFactory.success("tagDetailFetched", tagDetail);
  }

  @DeleteMapping("/{repoName}/{imageName}/tags/{tagName}")
  public @NonNull RestResponse<Void> deleteTag(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName,
      @PathVariable final @NonNull String tagName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.tagDeletionComponent.deleteTag(repoInfo, imageName, tagName);

    this.updateUsage(repoInfo, usages);

    return this.restResponseFactory.success("tagDeleted");
  }

  @GetMapping("/{repoName}/{imageName}/tags/{tagName}/manifests")
  public @NonNull RestResponse<PagedModel<ManifestListItem>> getTagManifests(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName,
      @PathVariable final @NonNull String tagName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var tagLayers =
        this.dockerApiFacade.getTagManifestsLikeName(repoInfo, imageName, tagName, name, pageable);

    return this.restResponseFactory.success("tagLayersFetched", new PagedModel<>(tagLayers));
  }

  @GetMapping("/{repoName}/{imageName}/manifests/{reference}")
  public @NonNull RestResponse<String> getManifest(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String imageName,
      @PathVariable @NonNull final String reference)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ, false);

    final var tag =
        this.manifestService.findActiveTagByRepoAndDigest(
            repoInfo.getStorageKey(), imageName, reference);

    final var fileName =
        ManifestNameGenerator.generate(repoInfo.getStorageKey(), imageName, tag.getName());

    final var manifest = this.dockerApiFacade.getManifest(repoInfo, fileName);

    return this.restResponseFactory.success("manifestFetched", manifest);
  }

  @GetMapping("/{repoName}/{ignoredImageName}/configs/{digest}")
  public @NonNull RestResponse<String> getConfig(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String ignoredImageName,
      @PathVariable @NonNull final String digest)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.DOCKER);

    this.dockerAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ, false);

    final var layer = this.dockerApiFacade.findLayerByDigestAndRepoAndImageName(repoInfo, digest);

    final var config = this.dockerApiFacade.getConfig(repoInfo, layer.getDigest());

    return this.restResponseFactory.success("configFetched", config);
  }

  public void updateUsage(final @NonNull RepoInfo repoInfo, final @NonNull BaseUsages usages) {

    final var usageUpdatedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedInfo);
  }
}
