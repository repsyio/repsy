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
package io.repsy.os.server.protocols.maven.ui.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.ArtifactListItem;
import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.generated.model.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.DeletedItem;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.artifact.services.components.ArtifactDeletionComponent;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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
@RequestMapping("/api/mvn/artifacts")
@NullMarked
@SuppressWarnings("java:S6856")
public class MavenArtifactController {

  private static final Set<String> ARTIFACT_SORT_PROPERTIES =
      Set.of("id", "groupName", "artifactName", "lastUpdatedAt");

  private static final Set<String> VERSION_SORT_PROPERTIES =
      Set.of("id", "versionName", "lastUpdatedAt");

  private final UsageUpdateService usageUpdateService;
  private final ArtifactServiceImpl artifactService;
  private final MavenApiFacade mavenApiFacade;
  private final ArtifactDeletionComponent artifactDeletionComponent;
  private final RestResponseFactory restResponseFactory;

  @DeleteMapping("/{repoName}/{groupName}/{artifactName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<DeletedItem> delete(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @PathVariable final String artifactName) {

    final var deletedItemPair =
        this.artifactDeletionComponent.deleteArtifact(repoInfo, groupName, artifactName);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("artifactDeleted", deletedItemPair.getFirst());
  }

  @DeleteMapping("/{repoName}/{groupName}/{artifactName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<DeletedItem> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @PathVariable final String artifactName,
      @PathVariable final String version)
      throws IOException, XmlPullParserException {

    final var deletedItemPair =
        this.artifactDeletionComponent.deleteArtifactVersion(
            repoInfo, groupName, artifactName, version);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("artifactVersionDeleted", deletedItemPair.getFirst());
  }

  @DeleteMapping("/{repoName}/{groupName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<DeletedItem> deleteGroup(
      final RepoInfo repoInfo, @PathVariable final String groupName) {

    final var deletedItemPair = this.artifactDeletionComponent.deleteGroup(repoInfo, groupName);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("groupDeleted", deletedItemPair.getFirst());
  }

  @GetMapping({
    "/{repoName}/{groupName}/{artifactName}",
    "/{repoName}/{groupName}/{artifactName}/versions/{version}"
  })
  @RepoOperation
  public RestResponse<ArtifactVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @PathVariable final String artifactName,
      @PathVariable final @Nullable String version)
      throws IOException, XmlPullParserException {

    final var artifactVersion =
        this.mavenApiFacade.findArtifactVersion(repoInfo, groupName, artifactName, version);

    return this.restResponseFactory.success("artifactVersionFetched", artifactVersion);
  }

  @GetMapping("/{repoName}/{groupName}/{artifactName}/versions")
  @RepoOperation
  public RestResponse<PagedModel<ArtifactVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @PathVariable final String artifactName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var artifactVersions =
        this.artifactService.getArtifactVersions(
            repoInfo.getStorageKey(), groupName, artifactName, pageable);

    return this.restResponseFactory.success(
        "artifactVersionsFetched", new PagedModel<>(artifactVersions));
  }

  @GetMapping(value = "/{repoName}/{groupName}/{artifactName}/versions", params = "q")
  @RepoOperation
  public RestResponse<PagedModel<ArtifactVersionListItem>> listVersionsLikeVersion(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @PathVariable final String artifactName,
      @RequestParam(name = "q", required = false, defaultValue = "") final String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var artifactVersions =
        this.artifactService.getArtifactVersionsContainsVersion(
            repoInfo.getStorageKey(), groupName, artifactName, version, pageable);

    return this.restResponseFactory.success(
        "artifactVersionsFetched", new PagedModel<>(artifactVersions));
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<ArtifactListItem>> listMavenGroups(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", required = false, defaultValue = "") final String groupName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, ARTIFACT_SORT_PROPERTIES);

    final var artifacts =
        this.artifactService.getArtifactsContainsGroupName(
            repoInfo.getStorageKey(), groupName, pageable);

    return this.restResponseFactory.success("artifactsFetched", new PagedModel<>(artifacts));
  }

  @GetMapping("/{repoName}/{groupName}")
  @RepoOperation
  public RestResponse<PagedModel<ArtifactListItem>> listMavenArtifacts(
      final RepoInfo repoInfo,
      @PathVariable final String groupName,
      @RequestParam(name = "q", required = false, defaultValue = "") final String artifactName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, ARTIFACT_SORT_PROPERTIES);

    final var artifacts =
        this.artifactService.getArtifactsContainsArtifactName(
            repoInfo.getStorageKey(), groupName, artifactName, pageable);

    return this.restResponseFactory.success("artifactsFetched", new PagedModel<>(artifacts));
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {

    final var usageUpdatedEvent = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedEvent);
  }
}
