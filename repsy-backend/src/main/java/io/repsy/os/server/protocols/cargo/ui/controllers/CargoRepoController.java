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
package io.repsy.os.server.protocols.cargo.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.cargo.shared.auth.services.CargoAuthComponent;
import io.repsy.os.server.protocols.cargo.ui.facades.CargoApiFacade;
import io.repsy.os.shared.repo.dtos.RepoCreateForm;
import io.repsy.os.shared.repo.dtos.RepoDescriptionForm;
import io.repsy.os.shared.repo.dtos.RepoListInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
import io.repsy.os.shared.repo.dtos.RepoRenameForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.RepoUsageInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
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
@RequestMapping("/api/cargo/repos")
@NullMarked
public class CargoRepoController {

  private final CargoAuthComponent cargoAuthComponent;
  private final RepoTxService repoTxService;
  private final UsageService usageService;
  private final CargoApiFacade cargoApiFacade;
  private final RestResponseFactory responseFactory;
  private final UsageUpdateService usageUpdateService;

  @PostMapping
  public RestResponse<Void> create(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestBody @Valid final RepoCreateForm form) {

    this.cargoAuthComponent.authenticateAndCreateToken(authHeader);

    final var repoInfo =
        this.repoTxService.createRepo(
            form.getName(), RepoType.CARGO, form.isPrivateRepo(), form.getDescription());

    this.cargoApiFacade.createRepo(repoInfo.getStorageKey());

    return this.responseFactory.success("repoCreated");
  }

  @DeleteMapping("/{repoName}")
  public RestResponse<Void> delete(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final String repoName)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.cargoApiFacade.deleteRepo(repoInfo);

    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getId(), usages);

    this.usageUpdateService.updateUsage(usageChangedInfo);

    this.repoTxService.deleteRepo(repoInfo.getId());

    return this.responseFactory.success("repoDeleted");
  }

  @GetMapping("/{repoName}/permissions")
  public RestResponse<RepoPermissionInfo> getPermission(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);
    final var repoPermissionInfo =
        this.cargoAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    return this.responseFactory.success("repoPermissionsFetched", repoPermissionInfo);
  }

  @GetMapping("/info")
  public RestResponse<List<RepoListInfo>> getInfo(
      @RequestHeader(AUTHORIZATION) final String authHeader) {

    this.cargoAuthComponent.authenticateAndCreateToken(authHeader);

    final var repositoryList = this.repoTxService.findAllByRepoType(RepoType.CARGO);

    return this.responseFactory.success("reposFetched", repositoryList);
  }

  @GetMapping("/{repoName}/settings")
  public RestResponse<RepoSettingsInfo> getSettings(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    final var settings = this.cargoApiFacade.getSettings(repoInfo);

    return this.responseFactory.success("settingsFetched", settings);
  }

  @GetMapping("/{repoName}/usage")
  public RestResponse<RepoUsageInfo> getUsage(
      @RequestHeader(AUTHORIZATION) final String authHeader, @PathVariable final String repoName) {

    this.cargoAuthComponent.authenticateUser(authHeader);

    final var usageInfo = this.usageService.getRepoUsageInfo(repoName, RepoType.CARGO);

    return this.responseFactory.success("usageFetched", usageInfo);
  }

  @GetMapping("/count")
  public RestResponse<Long> getCount(@RequestHeader(AUTHORIZATION) final String authHeader) {

    this.cargoAuthComponent.authenticateUser(authHeader);

    final var repoCount = this.repoTxService.getRepoCount(RepoType.CARGO);

    return this.responseFactory.success("repoCountFetched", repoCount);
  }

  @PatchMapping("/{repoName}/name")
  public RestResponse<Void> rename(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PathVariable final String repoName,
      @RequestBody @Valid final RepoRenameForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.renameRepo(repoInfo.getName(), form.getName(), RepoType.CARGO);

    return this.responseFactory.success("repoNameUpdated");
  }

  @PatchMapping("/{repoName}/description")
  public RestResponse<Void> updateDescription(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PathVariable final String repoName,
      @RequestBody @Valid final RepoDescriptionForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    this.repoTxService.updateDescription(repoInfo.getStorageKey(), form.getDescription());

    return this.responseFactory.success("repoDescriptionUpdated");
  }

  @PutMapping("/{repoName}/settings")
  public RestResponse<Void> updateSettings(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PathVariable final String repoName,
      @RequestBody final RepoSettingsForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    this.cargoApiFacade.updateSettings(repoInfo, form);

    return this.responseFactory.success("settingsUpdated");
  }
}
