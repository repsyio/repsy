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
package io.repsy.os.server.shared.token.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.DeployTokenForm;
import io.repsy.os.generated.model.TokenInfo;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfo;
import io.repsy.os.server.shared.token.dtos.DeployTokenInfoListItem;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.mappers.DeployTokenConverter;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenUtils;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DeployTokenService {

  private static final Duration DEFAULT_EXPIRATION_DURATION = Duration.of(365, ChronoUnit.DAYS);

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull RepoDeployTokenRepository deployTokenRepository;
  private final @NonNull DeployTokenConverter deployTokenConverter;

  public @NonNull Page<DeployTokenInfoListItem> getDeployTokensByRepoInfo(
      final @NonNull UUID repoId, final @NonNull Pageable pageable) {

    final var repoDeployTokens = this.deployTokenRepository.findAllByRepoId(repoId, pageable);

    if (repoDeployTokens.isEmpty()) {
      return Page.empty();
    }

    return repoDeployTokens;
  }

  public @NonNull Optional<DeployTokenInfo> findByTokenAndRepoType(
      final @NonNull String token, final @NonNull RepoType repoType) {

    return this.deployTokenRepository
        .findByTokenAndRepoType(token, repoType)
        .map(this.deployTokenConverter::toDeployTokenInfo);
  }

  public @NonNull Optional<DeployTokenInfo> findByRepoIdAndToken(
      final @NonNull UUID repoId, final @NonNull String token) {

    return this.deployTokenRepository
        .findByRepoIdAndToken(repoId, token)
        .map(this.deployTokenConverter::toDeployTokenInfo);
  }

  public @NonNull Optional<DeployTokenInfo> findByRepoIdAndTokenId(
      final @NonNull UUID repoId, final @NonNull UUID tokenId) {

    return this.deployTokenRepository
        .findByRepoIdAndId(repoId, tokenId)
        .map(this.deployTokenConverter::toDeployTokenInfo);
  }

  @Transactional
  public @NonNull TokenInfo createDeployToken(
      final @NonNull UUID repoId, final @NonNull DeployTokenForm deployTokenForm) {

    final var repo = this.repoTxService.getRepoEntity(repoId);
    final var now = Instant.now();
    final var generatedToken = TokenFactory.deployToken();
    final var repoDeployToken = this.deployTokenConverter.toDeployToken(deployTokenForm);

    repoDeployToken.setRepo(repo);
    repoDeployToken.setToken(generatedToken);

    if (repoDeployToken.getUsername() == null || repoDeployToken.getUsername().isEmpty()) {
      repoDeployToken.setUsername(TokenUsernameGenerator.deployTokenUsername());
    }

    if (repoDeployToken.getExpirationDate() == null) {
      repoDeployToken.setExpirationDate(now.plus(DEFAULT_EXPIRATION_DURATION));
    }

    final var dayDuration =
        (int) Math.max(Duration.between(now, repoDeployToken.getExpirationDate()).toDays(), 1);

    repoDeployToken.setTokenDurationDay(dayDuration);

    this.deployTokenRepository.save(repoDeployToken);

    return TokenInfo.builder()
        .token(generatedToken)
        .username(repoDeployToken.getUsername())
        .build();
  }

  @Transactional
  public void revokeDeployToken(final @NonNull UUID repoId, final @NonNull UUID tokenId) {

    final var repoDeployToken = this.getRepoDeployTokenByRepoAndTokenId(repoId, tokenId);

    this.deployTokenRepository.delete(repoDeployToken);
  }

  @Transactional
  public @NonNull String rotateDeployToken(
      final @NonNull UUID repoId, final @NonNull UUID tokenId) {

    final var repoDeployToken = this.getRepoDeployTokenByRepoAndTokenId(repoId, tokenId);
    final var newToken = TokenFactory.deployToken();

    if (DeployTokenUtils.isExpired(repoDeployToken.getExpirationDate())) {
      repoDeployToken.setUsername(TokenUsernameGenerator.deployTokenUsername());
      repoDeployToken.setExpirationDate(
          Instant.now().plus(repoDeployToken.getTokenDurationDay(), ChronoUnit.DAYS));
    }

    repoDeployToken.setToken(newToken);

    this.deployTokenRepository.save(repoDeployToken);

    return newToken;
  }

  @Transactional
  public void updateLastUsedTime(final @NonNull UUID tokenId) {

    this.deployTokenRepository.updateLastUsedTime(tokenId, Instant.now());
  }

  private @NonNull RepoDeployToken getRepoDeployTokenByRepoAndTokenId(
      final @NonNull UUID repoId, final @NonNull UUID tokenId) {

    return this.deployTokenRepository
        .findByRepoIdAndId(repoId, tokenId)
        .orElseThrow(() -> new ItemNotFoundException("tokenNotFound"));
  }
}
