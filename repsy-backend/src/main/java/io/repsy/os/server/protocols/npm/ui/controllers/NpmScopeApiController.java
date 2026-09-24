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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.NpmPackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.services.NpmPackageServiceImpl;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the packages of one npm scope, or the unscoped ones. These routes live under their own
 * {@code /api/npm/scopes} prefix rather than next to the package routes of {@link
 * NpmPackageApiController}: below {@code /api/npm/packages/{repoName}} every segment is a package
 * or scope name, so a literal segment there hides the package or scope of that name (RPS-1010).
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/npm/scopes")
@NullMarked
@SuppressWarnings("java:S6856")
public class NpmScopeApiController {

  private final NpmPackageServiceImpl npmPackageService;
  private final RestResponseFactory restResponseFactory;

  @GetMapping({
    "/{repoName}/packages",
    "/{repoName}/{scope}/packages",
  })
  @RepoOperation
  public RestResponse<PagedModel<NpmPackageListItem>> listByScope(
      final RepoInfo repoInfo,
      @PathVariable(required = false) final @Nullable String scope,
      @RequestParam(name = "q", required = false, defaultValue = "") final String name,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var packages =
        this.npmPackageService.getPackagesByScopeContainsName(
            repoInfo.getStorageKey(),
            scope,
            name,
            SortValidator.resolveSortPaths(pageable, NpmPackageApiController.PACKAGE_SORT_PATHS));

    return this.restResponseFactory.success(
        NpmPackageApiController.PACKAGES_FETCHED, new PagedModel<>(packages));
  }
}
