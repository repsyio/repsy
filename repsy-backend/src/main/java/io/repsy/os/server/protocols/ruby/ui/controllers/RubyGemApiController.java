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
package io.repsy.os.server.protocols.ruby.ui.controllers;

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.GemListItem;
import io.repsy.os.generated.model.GemPackageInfo;
import io.repsy.os.generated.model.GemVersionInfo;
import io.repsy.os.generated.model.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.ui.facades.RubyApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/ruby/gems")
public class RubyGemApiController {

  private static final Set<String> GEM_SORT_PROPERTIES = Set.of("id", "name", "updatedAt");

  private static final Set<String> VERSION_SORT_PROPERTIES = Set.of("id", "version", "createdAt");

  private final RubyApiFacade rubyApiFacade;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public ResponseEntity<PagedModel<GemListItem>> listGems(
      final RepoInfo repoInfo,
      @RequestParam(name = "q", defaultValue = "") final String name,
      @PageableDefault final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, GEM_SORT_PROPERTIES);

    final var gems = this.rubyApiFacade.listGems(repoInfo, name, pageable);
    return ResponseEntity.ok(new PagedModel<>(gems));
  }

  @GetMapping("/{repoName}/{packageName}")
  @RepoOperation
  public ResponseEntity<GemPackageInfo> getPackage(
      final RepoInfo repoInfo, @PathVariable final String packageName) {

    return ResponseEntity.ok(this.rubyApiFacade.getPackageInfo(repoInfo, packageName));
  }

  @GetMapping("/{repoName}/{packageName}/versions")
  @RepoOperation
  public ResponseEntity<PagedModel<GemVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @RequestParam(name = "q", defaultValue = "") final String version,
      @PageableDefault final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, VERSION_SORT_PROPERTIES);

    final var versions = this.rubyApiFacade.listVersions(repoInfo, packageName, version, pageable);
    return ResponseEntity.ok(new PagedModel<>(versions));
  }

  @DeleteMapping("/{repoName}/{packageName}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteGem(
      final RepoInfo repoInfo, @PathVariable final String packageName) {
    final var usages = this.rubyApiFacade.deleteGem(repoInfo, packageName);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));
    return ResponseEntities.noContent();
  }

  @GetMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation
  public ResponseEntity<GemVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version,
      @RequestParam(defaultValue = "ruby") final String platform) {
    final var info = this.rubyApiFacade.getVersionInfo(repoInfo, packageName, version, platform);
    return ResponseEntity.ok(info);
  }

  @DeleteMapping("/{repoName}/{packageName}/versions/{version}")
  @RepoOperation(permission = Permission.MANAGE)
  public ResponseEntity<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String packageName,
      @PathVariable final String version,
      @RequestParam(defaultValue = "ruby") final String platform) {
    final var usages =
        this.rubyApiFacade.deleteGemVersion(repoInfo, packageName, version, platform);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));
    return ResponseEntities.noContent();
  }
}
