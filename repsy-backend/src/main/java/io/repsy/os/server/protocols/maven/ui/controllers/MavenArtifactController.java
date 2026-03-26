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

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.DeletedItem;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.artifact.services.components.ArtifactDeletionComponent;
import io.repsy.os.server.protocols.maven.shared.auth.services.MavenAuthComponent;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
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
@RequestMapping("/api/mvn/artifacts")
public class MavenArtifactController {

  private final @NonNull MavenAuthComponent mavenAuthComponent;
  private final @NonNull UsageUpdateService usageUpdateService;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull ArtifactServiceImpl artifactService;
  private final @NonNull MavenApiFacade mavenApiFacade;
  private final @NonNull ArtifactDeletionComponent artifactDeletionComponent;
  private final @NonNull RestResponseFactory restResponseFactory;

  @DeleteMapping("/{repoName}/{groupName}/{artifactName}")
  public @NonNull RestResponse<DeletedItem> deleteArtifact(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @PathVariable final @NonNull String artifactName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);
    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var deletedItemPair =
        this.artifactDeletionComponent.deleteArtifact(repoInfo, groupName, artifactName);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("artifactDeleted", deletedItemPair.getFirst());
  }

  @DeleteMapping("/{repoName}/{groupName}/{artifactName}/versions/{versionName}")
  public @NonNull RestResponse<DeletedItem> deleteArtifactVersion(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @PathVariable final @NonNull String artifactName,
      @PathVariable final @NonNull String versionName)
      throws IOException, XmlPullParserException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var deletedItemPair =
        this.artifactDeletionComponent.deleteArtifactVersion(
            repoInfo, groupName, artifactName, versionName);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("artifactVersionDeleted", deletedItemPair.getFirst());
  }

  @DeleteMapping("/{repoName}/{groupName}")
  public @NonNull RestResponse<DeletedItem> deleteGroup(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var deletedItemPair = this.artifactDeletionComponent.deleteGroup(repoInfo, groupName);

    this.updateUsage(repoInfo, deletedItemPair.getSecond());

    return this.restResponseFactory.success("groupDeleted", deletedItemPair.getFirst());
  }

  @GetMapping({
    "/{repoName}/{groupName}/{artifactName}",
    "/{repoName}/{groupName}/{artifactName}/version/{versionName}"
  })
  public @NonNull RestResponse<ArtifactVersionInfo> getArtifactVersion(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @PathVariable final @NonNull String artifactName,
      @PathVariable final @Nullable String versionName)
      throws IOException, XmlPullParserException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var version =
        this.mavenApiFacade.findArtifactVersion(repoInfo, groupName, artifactName, versionName);

    return this.restResponseFactory.success("artifactVersionFetched", version);
  }

  @GetMapping("/{repoName}/{groupName}/{artifactName}/versions")
  public @NonNull RestResponse<PagedModel<ArtifactVersionListItem>> getArtifactVersions(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @PathVariable final @NonNull String artifactName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var artifactVersions =
        this.artifactService.getArtifactVersions(
            repoInfo.getStorageKey(), groupName, artifactName, pageable);

    return this.restResponseFactory.success(
        "artifactVersionsFetched", new PagedModel<>(artifactVersions));
  }

  @GetMapping("/{repoName}/{groupName}/{artifactName}/versions/search")
  public @NonNull RestResponse<PagedModel<ArtifactVersionListItem>> getArtifactVersionsLikeVersion(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @PathVariable final @NonNull String artifactName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String version,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var artifactVersions =
        this.artifactService.getArtifactVersionsContainsVersion(
            repoInfo.getStorageKey(), groupName, artifactName, version, pageable);

    return this.restResponseFactory.success(
        "artifactVersionsFetched", new PagedModel<>(artifactVersions));
  }

  @GetMapping("/{repoName}/search")
  public @NonNull RestResponse<PagedModel<ArtifactListItem>> getArtifactsContainsGroupName(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String groupName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var artifacts =
        this.artifactService.getArtifactsContainsGroupName(
            repoInfo.getStorageKey(), groupName, pageable);

    return this.restResponseFactory.success("artifactsFetched", new PagedModel<>(artifacts));
  }

  @GetMapping("/{repoName}/{groupName}/search")
  public @NonNull RestResponse<PagedModel<ArtifactListItem>> getArtifactsContainsArtifactName(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull String groupName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String artifactName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var artifacts =
        this.artifactService.getArtifactsContainsArtifactName(
            repoInfo.getStorageKey(), groupName, artifactName, pageable);

    return this.restResponseFactory.success("artifactsFetched", new PagedModel<>(artifacts));
  }

  public void updateUsage(final @NonNull RepoInfo repoInfo, final @NonNull BaseUsages usages) {

    final var usageUpdatedEvent = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    this.usageUpdateService.updateUsage(usageUpdatedEvent);
  }
}
