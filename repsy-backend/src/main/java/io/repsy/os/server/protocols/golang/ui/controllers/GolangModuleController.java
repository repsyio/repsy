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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.generated.model.GoModuleListItem;
import io.repsy.os.generated.model.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/go/modules")
@NullMarked
@SuppressWarnings("java:S6856")
public class GolangModuleController {

  private final GolangApiFacade golangApiFacade;
  private final RestResponseFactory restResponseFactory;

  /**
   * Signals to the Go toolchain that this proxy does not relay checksum database requests.
   * Returning 404 causes Go to contact sum.golang.org directly for public modules and to rely on
   * GONOSUMDB for private modules.
   */
  @GetMapping("/{repoName}/sumdb/supported")
  public ResponseEntity<Void> checkSumdbSupported(@PathVariable final String repoName) {
    return ResponseEntity.notFound().build();
  }

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<GoModuleListItem>> list(
      final RepoInfo repoInfo,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var modules = this.golangApiFacade.getModules(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("modulesFetched", new PagedModel<>(modules));
  }

  @GetMapping("/{repoName}/search")
  @RepoOperation
  public RestResponse<PagedModel<GoModuleListItem>> search(
      final RepoInfo repoInfo,
      @RequestParam(required = false, defaultValue = "") final String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var modules =
        this.golangApiFacade.searchModules(repoInfo.getStorageKey(), search, pageable);

    return this.restResponseFactory.success("modulesFetched", new PagedModel<>(modules));
  }

  @GetMapping("/{repoName}/versions")
  @RepoOperation
  public RestResponse<PagedModel<GoModuleVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @RequestParam final String modulePath,
      @RequestParam(required = false, defaultValue = "") final String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var versions =
        this.golangApiFacade.getModuleVersions(
            repoInfo.getStorageKey(), modulePath, search, pageable);

    return this.restResponseFactory.success("moduleVersionsFetched", new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/info")
  @RepoOperation
  public RestResponse<GoModuleInfo> getInfo(
      final RepoInfo repoInfo, @RequestParam final String modulePath) {

    final var moduleInfo = this.golangApiFacade.getModuleInfo(repoInfo.getStorageKey(), modulePath);

    return this.restResponseFactory.success("moduleInfoFetched", moduleInfo);
  }

  @DeleteMapping("/{repoName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> delete(final RepoInfo repoInfo, @RequestParam final String modulePath) {

    this.golangApiFacade.deleteModule(repoInfo, modulePath);

    return this.restResponseFactory.success("moduleDeleted");
  }

  @DeleteMapping("/{repoName}/versions")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteVersion(
      final RepoInfo repoInfo,
      @RequestParam final String modulePath,
      @RequestParam final String version) {

    this.golangApiFacade.deleteModuleVersion(repoInfo, modulePath, version);

    return this.restResponseFactory.success("moduleVersionDeleted");
  }
}
