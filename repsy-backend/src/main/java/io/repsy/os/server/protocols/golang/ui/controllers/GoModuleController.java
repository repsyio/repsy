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

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.generated.model.GoModuleListItem;
import io.repsy.os.generated.model.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.ui.facades.GoApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/go/modules")
@SuppressWarnings("java:S6856")
public class GoModuleController {

  private static final Set<String> MODULE_SORT_PROPERTIES = Set.of("id", "modulePath", "createdAt");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("id", "version", "createdAt");

  private final GoApiFacade golangApiFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<GoModuleListItem>> list(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", required = false, defaultValue = "") final String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, MODULE_SORT_PROPERTIES);

    final var modules =
        this.golangApiFacade.searchModules(repoInfo.getStorageKey(), search, pageable);

    return ResponseEntity.ok(new PagedModel<>(modules));
  }

  @GetMapping("/{repoName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<GoModuleVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @RequestParam final String modulePath,
      @RequestParam(name = "q", required = false, defaultValue = "") final String search,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions =
        this.golangApiFacade.getModuleVersions(
            repoInfo.getStorageKey(), modulePath, search, pageable);

    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @GetMapping("/{repoName}/info")
  @RepoOperation
  public ResponseEntity<GoModuleInfo> getInfo(
      final RepoInfo repoInfo, @RequestParam final String modulePath) {

    final var moduleInfo = this.golangApiFacade.getModuleInfo(repoInfo.getStorageKey(), modulePath);

    return ResponseEntity.ok(moduleInfo);
  }

  @DeleteMapping("/{repoName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(
      final RepoInfo repoInfo, @RequestParam final String modulePath) {

    final var usages = this.golangApiFacade.deleteModule(repoInfo, modulePath);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  @DeleteMapping("/{repoName}/versions")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @RequestParam final String modulePath,
      @RequestParam final String version) {

    final var usages = this.golangApiFacade.deleteModuleVersion(repoInfo, modulePath, version);

    this.updateUsage(repoInfo, usages);

    return ResponseEntities.noContent();
  }

  private void updateUsage(final RepoInfo repoInfo, final BaseUsages usages) {
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getStorageKey(), usages));
  }
}
