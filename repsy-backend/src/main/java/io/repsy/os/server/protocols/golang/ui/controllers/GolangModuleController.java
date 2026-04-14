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
package io.repsy.os.server.protocols.golang.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.golang.shared.auth.services.GolangAuthComponent;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleInfo;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/go/modules")
public class GolangModuleController {

  private final @NonNull GolangAuthComponent golangAuthComponent;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull GolangApiFacade golangApiFacade;
  private final @NonNull RestResponseFactory restResponseFactory;

  /**
   * Signals to the Go toolchain that this proxy does not relay checksum database requests.
   * Returning 404 causes Go to contact sum.golang.org directly for public modules and to rely on
   * GONOSUMDB for private modules.
   */
  @GetMapping("/{repoName}/sumdb/supported")
  public @NonNull ResponseEntity<Void> checkSumdbSupported(
      @PathVariable final @NonNull String repoName) {
    return ResponseEntity.notFound().build();
  }

  @GetMapping("/{repoName}")
  public @NonNull RestResponse<PagedModel<GoModuleListItem>> list(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var modules = this.golangApiFacade.getModules(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("modulesFetched", new PagedModel<>(modules));
  }

  @GetMapping("/{repoName}/search")
  public @NonNull RestResponse<PagedModel<GoModuleListItem>> search(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam(required = false, defaultValue = "") final @NonNull String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var modules =
        this.golangApiFacade.searchModules(repoInfo.getStorageKey(), search, pageable);

    return this.restResponseFactory.success("modulesFetched", new PagedModel<>(modules));
  }

  @GetMapping("/{repoName}/versions")
  public @NonNull RestResponse<PagedModel<GoModuleVersionListItem>> listVersions(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam final @NonNull String modulePath,
      @RequestParam(required = false, defaultValue = "") final @NonNull String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var versions =
        this.golangApiFacade.getModuleVersions(
            repoInfo.getStorageKey(), modulePath, search, pageable);

    return this.restResponseFactory.success("moduleVersionsFetched", new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/info")
  public @NonNull RestResponse<GoModuleInfo> getInfo(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam final @NonNull String modulePath) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    final var moduleInfo = this.golangApiFacade.getModuleInfo(repoInfo.getStorageKey(), modulePath);

    return this.restResponseFactory.success("moduleInfoFetched", moduleInfo);
  }

  @DeleteMapping("/{repoName}")
  public @NonNull RestResponse<Void> delete(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam final @NonNull String modulePath) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.golangApiFacade.deleteModule(repoInfo, modulePath);

    return this.restResponseFactory.success("moduleDeleted");
  }

  @DeleteMapping("/{repoName}/versions")
  public @NonNull RestResponse<Void> deleteVersion(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestParam final @NonNull String modulePath,
      @RequestParam final @NonNull String version) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.GOLANG);

    this.golangAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.golangApiFacade.deleteModuleVersion(repoInfo, modulePath, version);

    return this.restResponseFactory.success("moduleVersionDeleted");
  }
}
