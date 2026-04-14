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
import io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthComponentImpl;
import io.repsy.os.server.shared.token.dtos.DeployTokenForm;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfoListItem;
import io.repsy.os.server.shared.token.dtos.TokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/npm/deploy-tokens")
public class NpmDeployTokenController {

  private final @NonNull NpmAuthComponentImpl npmAuthComponent;

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull DeployTokenService deployTokenService;
  private final @NonNull RestResponseFactory restResponseFactory;

  @PostMapping("/{repoName}")
  public @NonNull RestResponse<TokenInfo> create(
      @RequestHeader(HttpHeaders.AUTHORIZATION) final String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody @Valid final @NonNull DeployTokenForm deployTokenForm) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var deployToken =
        this.deployTokenService.createDeployToken(repoInfo.getStorageKey(), deployTokenForm);

    return this.restResponseFactory.success("tokenCreated", deployToken);
  }

  @DeleteMapping("/{repoName}/{tokenId}")
  public @NonNull RestResponse<Void> revoke(
      @RequestHeader(HttpHeaders.AUTHORIZATION) final String authHeader,
      @PathVariable final @NonNull UUID tokenId,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.deployTokenService.revokeDeployToken(repoInfo.getStorageKey(), tokenId);

    return this.restResponseFactory.success("tokenRevoked");
  }

  @PutMapping("/{repoName}/{tokenId}")
  public @NonNull RestResponse<String> rotate(
      @RequestHeader(HttpHeaders.AUTHORIZATION) final String authHeader,
      @PathVariable final @NonNull UUID tokenId,
      @PathVariable final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var repoDeployToken =
        this.deployTokenService.rotateDeployToken(repoInfo.getStorageKey(), tokenId);

    return this.restResponseFactory.success("tokenRotated", repoDeployToken);
  }

  @GetMapping("/{repoName}")
  public @NonNull RestResponse<PagedModel<DeployTokenInfoListItem>> list(
      @RequestHeader(HttpHeaders.AUTHORIZATION) final String authHeader,
      @PathVariable final @NonNull String repoName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.NPM);

    this.npmAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var deployTokenInfoList =
        this.deployTokenService.getDeployTokensByRepoInfo(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("TokenFetched", new PagedModel<>(deployTokenInfoList));
  }
}
