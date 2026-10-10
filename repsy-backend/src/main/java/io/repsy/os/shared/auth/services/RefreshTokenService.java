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
import io.repsy.os.shared.constants.ErrorConstants;
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

  // A replay must not undo the family-wide revocation below: without noRollbackFor, Spring's
  // default rollback-on-RuntimeException would roll back revokeFamily along with the throw that
  // reports the replay, leaving the rest of the family usable (RPS-1682). This method is called
  // from AuthUserService.refreshToken(), itself @Transactional; propagation REQUIRED makes that
  // caller's transaction, not this one, the physical commit/rollback boundary, so the same
  // noRollbackFor is needed there too, or this annotation alone only stops this method from
  // marking the shared transaction rollback-only, not from the caller rolling it back anyway.
  @Transactional(noRollbackFor = UnAuthorizedException.class)
  public void consume(final @NonNull RefreshTokenClaims claims) {
    final var now = Instant.now();
    if (this.repository.markUsed(claims.tokenId(), now) == 1) {
      return;
    }

    final var token =
        this.repository
            .findById(claims.tokenId())
            .filter(candidate -> candidate.getFamilyId().equals(claims.familyId()))
            .orElseThrow(() -> new UnAuthorizedException(ErrorConstants.REFRESH_TOKEN_EXPIRED));
    this.repository.revokeFamily(token.getFamilyId(), now);
    throw new UnAuthorizedException(ErrorConstants.REFRESH_TOKEN_EXPIRED);
  }

  /**
   * Revokes every refresh token of {@code familyId}, so none of them can be exchanged again. Used
   * for an explicit logout: unlike {@link #consume}, it neither requires nor marks a token used, so
   * it also revokes a family whose current token was never spent, and it is idempotent (revoking an
   * already-revoked or unknown family updates nothing and never fails).
   */
  @Transactional
  public void revoke(final @NonNull UUID familyId) {
    this.repository.revokeFamily(familyId, Instant.now());
  }

  @Scheduled(fixedDelayString = "${os.auth.refresh-token-purge-delay-ms:3600000}")
  @Transactional
  public void purgeExpired() {
    this.repository.deleteExpired(Instant.now());
  }
}
