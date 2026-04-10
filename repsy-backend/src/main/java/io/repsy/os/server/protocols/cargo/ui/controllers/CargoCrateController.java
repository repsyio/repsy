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
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
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
@RequestMapping("/api/cargo/crates")
@NullMarked
public class CargoCrateController {

  private final CargoAuthComponent cargoAuthComponent;
  private final RepoTxService repoTxService;
  private final CargoApiFacade cargoApiFacade;
  private final RestResponseFactory responseFactory;
  private final UsageUpdateService usageUpdateService;

  @GetMapping("/{repoName}")
  public RestResponse<PagedModel<CrateListItem>> searchCrates(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final String repoName,
      @RequestParam(defaultValue = "") final String query,
      final Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ);

    final var crates = this.cargoApiFacade.search(repoInfo, query, pageable);

    return this.responseFactory.success("cratesFetched", new PagedModel<>(crates));
  }

  @GetMapping("/{repoName}/{crateName}")
  public RestResponse<BaseCrateInfo<UUID>> getCrate(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final String repoName,
      @PathVariable final String crateName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ);

    final var crate = this.cargoApiFacade.getCrate(repoInfo, crateName);

    return this.responseFactory.success("crateFetched", crate);
  }

  @GetMapping("/{repoName}/{crateName}/{vers}")
  public RestResponse<BaseCrateVersionInfo<UUID>> getCrateVersion(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final String repoName,
      @PathVariable final String crateName,
      @PathVariable final String vers) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ);

    final var version = this.cargoApiFacade.getCrateVersion(repoInfo, crateName, vers);

    return this.responseFactory.success("crateVersionFetched", version);
  }

  @GetMapping("/{repoName}/{crateName}/versions")
  public RestResponse<PagedModel<CrateVersionListItem>> getCrateVersions(
      @RequestHeader(value = AUTHORIZATION, required = false) final @Nullable String authHeader,
      @PathVariable final String repoName,
      @PathVariable final String crateName,
      @RequestParam(defaultValue = "") final String query,
      final Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.READ);

    final var versions = this.cargoApiFacade.getCrateVersions(repoInfo, crateName, query, pageable);

    return this.responseFactory.success("crateVersionsFetched", new PagedModel<>(versions));
  }

  @DeleteMapping("/{repoName}/{crateName}")
  public RestResponse<Void> deleteCrate(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PathVariable final String repoName,
      @PathVariable final String crateName)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.cargoApiFacade.deleteCrate(repoInfo, crateName);

    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getId(), usages);

    this.usageUpdateService.updateUsage(usageChangedInfo);

    return this.responseFactory.success("crateDeleted");
  }

  @DeleteMapping("/{repoName}/{crateName}/{vers}")
  public RestResponse<Void> deleteCrateVersion(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @PathVariable final String repoName,
      @PathVariable final String crateName,
      @PathVariable final String vers)
      throws IOException {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.CARGO);

    this.cargoAuthComponent.authorizeRequest(repoInfo, authHeader, Permission.MANAGE);

    final var usages = this.cargoApiFacade.deleteCrateVersion(repoInfo, crateName, vers);

    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getId(), usages);

    this.usageUpdateService.updateUsage(usageChangedInfo);

    return this.responseFactory.success("crateVersionDeleted");
  }
}
