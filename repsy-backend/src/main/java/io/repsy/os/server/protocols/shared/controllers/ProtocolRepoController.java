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
package io.repsy.os.server.protocols.shared.controllers;

import static io.repsy.protocols.shared.repo.dtos.Permission.MANAGE;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.os.generated.model.RepoDescriptionForm;
import io.repsy.os.generated.model.RepoPermissionInfo;
import io.repsy.os.generated.model.RepoRenameForm;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.generated.model.RepoSettingsInfo;
import io.repsy.os.generated.model.RepoUsageInfo;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacadeMavenAdapter;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
@NullMarked
@SuppressWarnings("java:S6856")
public class ProtocolRepoController {

  private final RepoTxService repoTxService;
  private final UsageService usageService;
  private final RestResponseFactory responseFactory;
  private final JwtUtils jwtUtils;

  @DeleteMapping("/{repoName}")
  @RepoOperation(permission = MANAGE)
  public RestResponse<Void> delete(final RepoInfo repoInfo, final ProtocolApiFacade facade) {

    // No usage update for the freed bytes: the usage lives on the repo row, which goes with it.
    // An async update would also race the delete and fail with repoNotFound (RPS-908).
    facade.deleteRepo(repoInfo);

    this.repoTxService.deleteRepo(repoInfo.getId());

    return this.responseFactory.success("repoDeleted");
  }

  @GetMapping("/{repoName}/permissions")
  @RepoOperation
  public RestResponse<RepoPermissionInfo> getRepoPermissions(
      final RepoPermissionInfo repoPermissionInfo) {

    return this.responseFactory.success("repoPermissionsFetched", repoPermissionInfo);
  }

  @GetMapping("/{repoName}/contents")
  @RepoOperation(scope = RepoScope.MAVEN)
  public RestResponse<List<StorageItemInfo>> getPathContent(
      final ProtocolApiFacadeMavenAdapter facade,
      final RepoInfo repoInfo,
      @RequestParam final String path) {

    final var items = facade.getItems(repoInfo, new RelativePath(path));

    return this.responseFactory.success("itemsFetched", items);
  }

  /**
   * Issues the token the Maven browser puts in the download URL instead of the session's access
   * token, as a navigation cannot set an {@code Authorization} header. It opens the given path of
   * this repo for reading, for a minute.
   */
  @PostMapping("/{repoName}/download-token")
  @RepoOperation(scope = RepoScope.MAVEN)
  public RestResponse<String> createDownloadToken(
      final RepoInfo repoInfo, @RequestParam final String path) {

    // Rejects a path that leaves the repo before a token is issued for it.
    final var relativePath = new RelativePath(path);

    final var token =
        this.jwtUtils.createDownloadToken(
            repoInfo.getStorageKey(), relativePath.getPath(), AuthUtils.TIMEOUT_DOWNLOAD_TOKEN);

    return this.responseFactory.success("downloadTokenCreated", token);
  }

  @GetMapping("/{repoName}/settings")
  @RepoOperation(permission = MANAGE)
  public RestResponse<RepoSettingsInfo> getRepoSettings(final RepoInfo repoInfo) {

    final var settings = this.repoTxService.getRepoSettings(repoInfo.getId());

    return this.responseFactory.success("settingsFetched", settings);
  }

  @GetMapping("/{repoName}/usage")
  @RepoOperation(permission = MANAGE)
  public RestResponse<RepoUsageInfo> getRepoUsage(final RepoInfo repoInfo) {

    final var usageInfo =
        this.usageService.getRepoUsageInfo(repoInfo.getName(), repoInfo.getType());

    return this.responseFactory.success("usageFetched", usageInfo);
  }

  @PatchMapping("/{repoName}/name")
  @RepoOperation(permission = MANAGE)
  public RestResponse<Void> renameRepo(
      final RepoInfo repoInfo, @RequestBody @Valid final RepoRenameForm form) {

    this.repoTxService.renameRepo(repoInfo.getName(), form.getName(), repoInfo.getType());

    return this.responseFactory.success("repoRenamed");
  }

  @PatchMapping("/{repoName}/description")
  @RepoOperation(permission = MANAGE)
  public RestResponse<Void> updateRepoDescription(
      final RepoInfo repoInfo, @RequestBody @Valid final RepoDescriptionForm form) {

    this.repoTxService.updateDescription(repoInfo.getStorageKey(), form.getDescription());

    return this.responseFactory.success("repoDescriptionEdited");
  }

  @PutMapping("/{repoName}/settings")
  @RepoOperation(permission = MANAGE)
  public RestResponse<Void> updateRepoSettings(
      final RepoInfo repoInfo, @RequestBody @Valid final RepoSettingsForm form) {

    this.repoTxService.updateSettings(repoInfo.getId(), form);

    return this.responseFactory.success("settingsUpdated");
  }

  @GetMapping("/{repoName}/format")
  @RepoOperation
  public RestResponse<RepoType> getRepoFormat(final RepoInfo repoInfo) {

    return this.responseFactory.success("repoTypeFetched", repoInfo.getType());
  }
}
