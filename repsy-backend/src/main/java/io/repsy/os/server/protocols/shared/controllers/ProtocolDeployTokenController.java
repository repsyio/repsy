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
import static org.springframework.data.domain.Sort.Direction.DESC;

import io.repsy.core.web.http.NoStore;
import io.repsy.core.web.http.ResponseEntities;
import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.DeployTokenForm;
import io.repsy.os.generated.model.TokenInfo;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfoListItem;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoName}/deploy-tokens")
@SuppressWarnings("java:S6856")
public class ProtocolDeployTokenController {

  /**
   * The properties of a listed token the list can be sorted by. The token hash and the internal
   * bookkeeping columns are left out.
   */
  private static final Set<String> SORT_PROPERTIES =
      Set.of("id", "name", "username", "description", "readOnly", "expirationDate", "createdAt");

  private final DeployTokenService deployTokenService;

  @PostMapping
  @RepoOperation(permission = MANAGE)
  public ResponseEntity<TokenInfo> create(
      final RepoInfo repoInfo,
      @RequestBody @Valid final DeployTokenForm form,
      final HttpServletResponse response) {

    final var result = this.deployTokenService.createDeployToken(repoInfo.getStorageKey(), form);

    NoStore.apply(response);

    final var location =
        URI.create("/api/repos/" + repoInfo.getName() + "/deploy-tokens/" + result.tokenId());
    return ResponseEntities.created(location, result.tokenInfo());
  }

  @DeleteMapping("/{tokenId}")
  @RepoOperation(permission = MANAGE)
  public ResponseEntity<Void> revokeDeployToken(
      final RepoInfo repoInfo, @PathVariable final UUID tokenId) {

    this.deployTokenService.revokeDeployToken(repoInfo.getStorageKey(), tokenId);

    return ResponseEntities.noContent();
  }

  @PostMapping("/{tokenId}/actions/rotate")
  @RepoOperation(permission = MANAGE)
  public ResponseEntity<String> rotateDeployToken(
      final RepoInfo repoInfo,
      @PathVariable final UUID tokenId,
      final HttpServletResponse response) {

    final var repoDeployToken =
        this.deployTokenService.rotateDeployToken(repoInfo.getStorageKey(), tokenId);

    NoStore.apply(response);

    return ResponseEntities.jsonString(repoDeployToken);
  }

  @GetMapping
  @RepoOperation(permission = MANAGE)
  public PagedModel<DeployTokenInfoListItem> list(
      @PageableDefault(sort = "id", direction = DESC) final Pageable pageable,
      final RepoInfo repoInfo) {

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES);

    final var deployTokenInfoList =
        this.deployTokenService.getDeployTokensByRepoInfo(repoInfo.getStorageKey(), pageable);

    return new PagedModel<>(deployTokenInfoList);
  }
}
