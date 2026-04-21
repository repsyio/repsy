package io.repsy.os.server.protocols.shared.protocols.ui.controllers;

import static io.repsy.protocols.shared.repo.dtos.Permission.MANAGE;
import static org.springframework.data.domain.Sort.Direction.DESC;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.server.shared.token.dtos.DeployTokenForm;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfoListItem;
import io.repsy.os.server.shared.token.dtos.TokenInfo;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoName}/deploy-tokens")
@NullMarked
public class ProtocolDeployTokenController {

  private final DeployTokenService deployTokenService;
  private final RestResponseFactory restResponseFactory;

  @PostMapping
  @RepoOperation(permission = MANAGE)
  public RestResponse<TokenInfo> create(
      final RepoInfo repoInfo, @RequestBody @Valid final DeployTokenForm form) {

    final var deployToken =
        this.deployTokenService.createDeployToken(repoInfo.getStorageKey(), form);

    return this.restResponseFactory.success("tokenCreated", deployToken);
  }

  @DeleteMapping("/{tokenId}")
  @RepoOperation(permission = MANAGE)
  public RestResponse<Void> revoke(final RepoInfo repoInfo, @PathVariable final UUID tokenId) {

    this.deployTokenService.revokeDeployToken(repoInfo.getStorageKey(), tokenId);

    return this.restResponseFactory.success("tokenRevoked");
  }

  @PutMapping("/{tokenId}")
  @RepoOperation(permission = MANAGE)
  public RestResponse<String> rotate(final RepoInfo repoInfo, @PathVariable final UUID tokenId) {

    final var repoDeployToken =
        this.deployTokenService.rotateDeployToken(repoInfo.getStorageKey(), tokenId);

    return this.restResponseFactory.success("tokenRotated", repoDeployToken);
  }

  @GetMapping
  @RepoOperation(permission = MANAGE)
  public RestResponse<PagedModel<DeployTokenInfoListItem>> list(
      @PageableDefault(sort = "id", direction = DESC) final Pageable pageable,
      final RepoInfo repoInfo) {

    final var deployTokenInfoList =
        this.deployTokenService.getDeployTokensByRepoInfo(repoInfo.getStorageKey(), pageable);

    return this.restResponseFactory.success("TokenFetched", new PagedModel<>(deployTokenInfoList));
  }
}
