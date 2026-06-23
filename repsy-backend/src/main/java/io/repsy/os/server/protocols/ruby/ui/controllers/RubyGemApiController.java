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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionInfo;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.ui.facades.RubyApiFacade;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
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
@NullMarked
public class RubyGemApiController {

  private final RubyApiFacade rubyApiFacade;
  private final RestResponseFactory responseFactory;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  @RepoOperation
  public RestResponse<PagedModel<GemListItem>> listGems(
      final RepoInfo repoInfo,
      @RequestParam(defaultValue = "") final String name,
      final Pageable pageable) {
    final var gems = this.rubyApiFacade.listGems(repoInfo, name, pageable);
    return this.responseFactory.success("gemsFetched", new PagedModel<>(gems));
  }

  @GetMapping("/{repoName}/{gemName}/versions")
  @RepoOperation
  public RestResponse<PagedModel<GemVersionListItem>> listVersions(
      final RepoInfo repoInfo,
      @PathVariable final String gemName,
      @RequestParam(defaultValue = "") final String version,
      final Pageable pageable) {
    final var versions = this.rubyApiFacade.listVersions(repoInfo, gemName, version, pageable);
    return this.responseFactory.success("gemVersionsFetched", new PagedModel<>(versions));
  }

  @DeleteMapping("/{repoName}/{gemName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteGem(
      final RepoInfo repoInfo, @PathVariable final String gemName) {
    final var usages = this.rubyApiFacade.deleteGem(repoInfo, gemName);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));
    return this.responseFactory.success("gemDeleted");
  }

  @GetMapping("/{repoName}/{gemName}/versions/{versionName}")
  @RepoOperation
  public RestResponse<GemVersionInfo> getVersion(
      final RepoInfo repoInfo,
      @PathVariable final String gemName,
      @PathVariable final String versionName,
      @RequestParam(defaultValue = "ruby") final String platform) {
    final var info = this.rubyApiFacade.getVersionInfo(repoInfo, gemName, versionName, platform);
    return this.responseFactory.success("gemVersionFetched", info);
  }

  @DeleteMapping("/{repoName}/{gemName}/versions/{versionName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<Void> deleteVersion(
      final RepoInfo repoInfo,
      @PathVariable final String gemName,
      @PathVariable final String versionName,
      @RequestParam(defaultValue = "ruby") final String platform) {
    final var usages = this.rubyApiFacade.deleteGemVersion(repoInfo, gemName, versionName, platform);
    this.usageUpdateService.updateUsage(new UsageChangedInfo(repoInfo.getId(), usages));
    return this.responseFactory.success("gemVersionDeleted");
  }
}
