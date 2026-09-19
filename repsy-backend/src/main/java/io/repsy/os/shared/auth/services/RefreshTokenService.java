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
package io.repsy.os.shared.auth.services;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.auth.dtos.RefreshTokenClaims;
import io.repsy.os.shared.auth.entities.RefreshToken;
import io.repsy.os.shared.auth.repositories.RefreshTokenRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final @NonNull String REFRESH_TOKEN_EXPIRED = "refreshTokenExpired";

  private final @NonNull RefreshTokenRepository repository;
  private final @NonNull EntityManager entityManager;

  @Transactional
  public void register(
      final @NonNull UUID tokenId,
      final @NonNull UUID userId,
      final @NonNull UUID familyId,
      final @NonNull Instant expiresAt) {
    final var token = new RefreshToken();
    token.setId(tokenId);
    token.setUserId(userId);
    token.setFamilyId(familyId);
    token.setExpiresAt(expiresAt);
    // The JTI is assigned before persistence so it can be embedded in the JWT. Persist explicitly;
    // repository.save would choose merge for a non-null assigned id and fail for a new row.
    this.entityManager.persist(token);
  }

  @Transactional
  public void consume(final @NonNull RefreshTokenClaims claims) {
    final var now = Instant.now();
    if (this.repository.markUsed(claims.tokenId(), now) == 1) {
      return;
    }

    final var token =
        this.repository
            .findById(claims.tokenId())
            .filter(candidate -> candidate.getFamilyId().equals(claims.familyId()))
            .orElseThrow(() -> new UnAuthorizedException(REFRESH_TOKEN_EXPIRED));
    this.repository.revokeFamily(token.getFamilyId(), now);
    throw new UnAuthorizedException(REFRESH_TOKEN_EXPIRED);
  }

  @Scheduled(fixedDelayString = "${os.auth.refresh-token-purge-delay-ms:3600000}")
  @Transactional
  public void purgeExpired() {
    this.repository.deleteExpired(Instant.now());
  }
}
