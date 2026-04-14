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
package io.repsy.os.server.protocols.npm.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthComponentImpl;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.shared.repo.dtos.RepoCreateForm;
import io.repsy.os.shared.repo.dtos.RepoDescriptionForm;
import io.repsy.os.shared.repo.dtos.RepoListInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import io.repsy.os.shared.repo.dtos.RepoRenameForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsForm;
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
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/npm/repos")
public class NpmRepoController {

  private final @NonNull NpmAuthComponentImpl npmAuthComponent;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull UsageService usageService;
  private final @NonNull NpmApiFacade npmFacade;
  private final @NonNull RestResponseFactory restResponseFactory;

  @PostMapping
  public @NonNull RestResponse<Void> create(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody final @NonNull RepoCreateForm form) {

    this.npmAuthComponent.authenticateUser(authHeader);

    final var repoInfo =
        this.repoTxService.createRepo(
            form.getName(), RepoType.NPM, form.isPrivateRepo(), form.getDescription());

    this.npmFacade.createRepo(repoInfo.getStorageKey());

    return this.restResponseFactory.success("repoCreated");
  }

  @DeleteMapping("/{repoName}")
  public @NonNull RestResponse<Void> delete(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.npmFacade.deleteRegistry(repoInfo);

    return this.restResponseFactory.success("repoDeleted");
  }

  @GetMapping("/{repoName}/permission")
  public @NonNull RestResponse<RepoPermissionInfo> getPermission(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    final var repoPermissionInfo =
        this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    return this.restResponseFactory.success("repoPermissionsFetched", repoPermissionInfo);
  }

  @GetMapping("/info")
  public @NonNull RestResponse<List<RepoListInfo>> getInfo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.npmAuthComponent.authenticateUser(authHeader);

    final var repos = this.repoTxService.findAllByRepoType(RepoType.NPM);

    return this.restResponseFactory.success("repoFetched", repos);
  }

  @GetMapping("/{repoName}/settings")
  public @NonNull RestResponse<RepoSettingsInfo> getSettings(
      @RequestHeader(AUTHORIZATION) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var settings = this.repoTxService.getRepoSettings(repoInfo.getStorageKey());

    return this.restResponseFactory.success("registrySettingsFetched", settings);
  }

  @GetMapping("/{repoName}/usage")
  public @NonNull RestResponse<RepoUsageInfo> getUsage(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName) {

    this.npmAuthComponent.authenticateUser(authHeader);

    final var repoUsageInfo = this.usageService.getRepoUsageInfo(repoName, RepoType.NPM);

    return this.restResponseFactory.success("repoUsageFetched", repoUsageInfo);
  }

  @GetMapping("/count")
  public @NonNull RestResponse<Long> getCount(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.npmAuthComponent.authenticateUser(authHeader);

    final var repoCount = this.repoTxService.getRepoCount(RepoType.NPM);

    return this.restResponseFactory.success("repoCountFetched", repoCount);
  }

  @PatchMapping("/{repoName}/name")
  public @NonNull RestResponse<Void> rename(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoRenameForm renameForm) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.renameRepo(repoInfo.getName(), renameForm.getName(), RepoType.NPM);

    return this.restResponseFactory.success("repoNameUpdated");
  }

  @PatchMapping("/{repoName}/description")
  public @NonNull RestResponse<Void> updateDescription(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoDescriptionForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateDescription(repoInfo.getStorageKey(), form.getDescription());

    return this.restResponseFactory.success("repoDescriptionUpdated");
  }

  @PutMapping("/{repoName}/settings")
  public @NonNull RestResponse<Void> updateSettings(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoSettingsForm settingsForm) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateSettings(repoInfo.getStorageKey(), settingsForm);

    return this.restResponseFactory.success("repoSettingsUpdated");
  }
}
