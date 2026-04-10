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
package io.repsy.os.server.protocols.pypi.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.pypi.shared.auth.services.PypiAuthComponent;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
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
@RequestMapping("/api/pypi/repos")
public class PypiRepoController {

  private final @NonNull PypiAuthComponent pypiAuthComponent;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull UsageService usageService;
  private final @NonNull PypiApiFacade pypiApiFacade;
  private final @NonNull RestResponseFactory restResponseFactory;

  @PostMapping
  public @NonNull RestResponse<Void> createRepo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestBody final @NonNull RepoCreateForm form) {

    this.pypiAuthComponent.authenticateUser(authHeader);

    final var repoInfo =
        this.repoTxService.createRepo(
            form.getName(), RepoType.PYPI, form.isPrivateRepo(), form.getDescription());

    this.pypiApiFacade.createRepo(repoInfo.getStorageKey());

    return this.restResponseFactory.success("repositoryCreated");
  }

  @DeleteMapping("/{repoName}")
  public @NonNull RestResponse<Void> deleteRepo(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.pypiApiFacade.deleteRepo(repoInfo);

    return this.restResponseFactory.success("repositoryDeleted");
  }

  @GetMapping("/{repoName}/permissions")
  public @NonNull RestResponse<RepoPermissionInfo> getRepoPermission(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    final var repoPermissionInfo =
        this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    return this.restResponseFactory.success("repoPermissionsFetched", repoPermissionInfo);
  }

  @GetMapping
  public @NonNull RestResponse<List<RepoListInfo>> getRepos(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.pypiAuthComponent.authenticateUser(authHeader);

    final var repos = this.repoTxService.findAllByRepoType(RepoType.PYPI);

    return this.restResponseFactory.success("repositoriesFetched", repos);
  }

  @GetMapping("/{repoName}/settings")
  public @NonNull RestResponse<RepoSettingsInfo> getRepoSettings(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var settings = this.repoTxService.getRepoSettings(repoInfo.getStorageKey());

    return this.restResponseFactory.success("repositorySettingsFetched", settings);
  }

  @GetMapping("/{repoName}/usage")
  public @NonNull RestResponse<RepoUsageInfo> getUsage(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName) {

    this.pypiAuthComponent.authenticateUser(authHeader);

    final var info = this.usageService.getRepoUsageInfo(repoName, RepoType.PYPI);

    return this.restResponseFactory.success("usageFetched", info);
  }

  @GetMapping("/count")
  public @NonNull RestResponse<Long> getRepoCount(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    this.pypiAuthComponent.authenticateUser(authHeader);

    final var repoCount = this.repoTxService.getRepoCount(RepoType.PYPI);

    return this.restResponseFactory.success("repoCountFetched", repoCount);
  }

  @PatchMapping("/{repoName}/name")
  public @NonNull RestResponse<Void> updateRepoName(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoRenameForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.renameRepo(repoInfo.getName(), form.getName(), RepoType.PYPI);

    return this.restResponseFactory.success("repoNameUpdated");
  }

  @PatchMapping("/{repoName}/description")
  public @NonNull RestResponse<Void> updateRepoDescription(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoDescriptionForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateDescription(repoInfo.getStorageKey(), form.getDescription());

    return this.restResponseFactory.success("repoDescriptionUpdated");
  }

  @PutMapping("/{repoName}/settings")
  public @NonNull RestResponse<Void> updateRepoSettings(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull RepoSettingsForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.PYPI);

    this.pypiAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateSettings(repoInfo.getStorageKey(), form);

    return this.restResponseFactory.success("repoSettingsUpdated");
  }
}
