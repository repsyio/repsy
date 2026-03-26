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
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.os.server.protocols.maven.shared.auth.services.MavenAuthComponent;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
import io.repsy.os.shared.repo.dtos.MavenRepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoCreateForm;
import io.repsy.os.shared.repo.dtos.RepoDescriptionForm;
import io.repsy.os.shared.repo.dtos.RepoListInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import io.repsy.os.shared.repo.dtos.RepoRenameForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.RepoUsageInfo;
import io.repsy.os.shared.usage.services.UsageService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn/repo")
public class MavenRepoController {

  private final @NonNull MavenAuthComponent mavenAuthComponent;
  private final @NonNull MavenApiFacade mavenApiFacade;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull UsageService usageService;
  private final @NonNull RestResponseFactory responseFactory;

  @PostMapping
  public @NonNull RestResponse<Void> createRepo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody @Valid final @NonNull RepoCreateForm form) {

    this.mavenAuthComponent.authenticateUser(authHeader);

    final var repoInfo =
        this.repoTxService.createRepo(
            form.getName(), RepoType.MAVEN, form.isPrivateRepo(), form.getDescription());

    this.mavenApiFacade.createRepo(repoInfo.getStorageKey());

    return this.responseFactory.success("repoCreated");
  }

  @DeleteMapping("/{repoName}")
  public @NonNull RestResponse<Void> deleteRepo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.mavenApiFacade.deleteRepo(repoInfo);

    return this.responseFactory.success("repoDeleted");
  }

  @GetMapping("/{repoName}/permission")
  public @NonNull RestResponse<RepoPermissionInfo> getRepoPermission(
      @PathVariable final @NonNull String repoName,
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    final var repoPermissionInfo =
        this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    return this.responseFactory.success("repoPermissionsFetched", repoPermissionInfo);
  }

  @GetMapping("/{repoName}/content")
  public @NonNull RestResponse<List<StorageItemInfo>> getPathContent(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam final @NonNull String path) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var items = this.mavenApiFacade.getItems(repoInfo, new RelativePath(path));
    return this.responseFactory.success("itemsFetched", items);
  }

  @GetMapping
  public @NonNull RestResponse<List<RepoListInfo>> getRepos(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {
    this.mavenAuthComponent.authenticateUser(authHeader);

    final var repos = this.repoTxService.findAllByRepoType(RepoType.MAVEN);

    return this.responseFactory.success("reposFetched", repos);
  }

  @GetMapping("/{repoName}/settings")
  public @NonNull RestResponse<RepoSettingsInfo> getSettings(
      @PathVariable final @NonNull String repoName,
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var settings = this.mavenApiFacade.getSettings(repoName);

    return this.responseFactory.success("settingsFetched", settings);
  }

  @GetMapping("/{repoName}/usage")
  public @NonNull RestResponse<RepoUsageInfo> getUsage(
      @PathVariable final @NonNull String repoName,
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.mavenAuthComponent.authenticateUser(authHeader);

    final var usageInfo = this.usageService.getRepoUsageInfo(repoName, RepoType.MAVEN);

    return this.responseFactory.success("usageFetched", usageInfo);
  }

  @GetMapping("info")
  public @NonNull RestResponse<List<RepoListInfo>> getRepoDetail(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.mavenAuthComponent.authenticateUser(authHeader);

    final var repositoryList = this.repoTxService.findAllByRepoType(RepoType.MAVEN);

    return this.responseFactory.success("reposFetched", repositoryList);
  }

  @GetMapping("/count")
  public @NonNull RestResponse<Long> getRepoCount(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.mavenAuthComponent.authenticateUser(authHeader);

    final var repoCount = this.repoTxService.getRepoCount(RepoType.MAVEN);

    return this.responseFactory.success("repoCountFetched", repoCount);
  }

  @PatchMapping("/{repoName}/name")
  public @NonNull RestResponse<Void> renameRepo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoRenameForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.renameRepo(repoInfo.getName(), form.getName(), RepoType.MAVEN);

    return this.responseFactory.success("repoRenamed");
  }

  @PatchMapping("/{repoName}/description")
  public @NonNull RestResponse<Void> updateRepoDescription(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoDescriptionForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateDescription(repoInfo.getStorageKey(), form.getDescription());

    return this.responseFactory.success("repoDescriptionEdited");
  }

  @PutMapping("/{repoName}/settings")
  public @NonNull RestResponse<Void> updateSettings(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull MavenRepoSettingsForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.mavenApiFacade.updateSettings(repoInfo, form);

    return this.responseFactory.success("settingsUpdated");
  }
}
