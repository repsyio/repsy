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

import io.repsy.os.shared.auth.entities.RevokedProtocolToken;
import io.repsy.os.shared.auth.repositories.RevokedProtocolTokenRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The registry of protocol tokens that were revoked before they expired (RPS-1361). A protocol
 * token is a stateless JWT, so a logout can only make it stop working by remembering it here; the
 * protocol authentication asks {@link #isRevoked} for every bearer JWT it has already verified.
 */
@Service
@RequiredArgsConstructor
public class RevokedProtocolTokenService {

  private final @NonNull RevokedProtocolTokenRepository repository;
  private final @NonNull EntityManager entityManager;

  /** Whether {@code token} (a verified JWT, without its {@code Bearer} prefix) was revoked. */
  @Transactional(readOnly = true)
  public boolean isRevoked(final @NonNull String token) {
    return this.repository.existsById(hash(token));
  }

  /**
   * Revokes {@code token} until {@code expiresAt}, when it stops working on its own. Revoking a
   * token twice is not an error.
   */
  @Transactional
  public void revoke(final @NonNull String token, final @NonNull Instant expiresAt) {
    final var tokenHash = hash(token);

    if (this.repository.existsById(tokenHash)) {
      return;
    }

    final var revoked = new RevokedProtocolToken();
    revoked.setTokenHash(tokenHash);
    revoked.setExpiresAt(expiresAt);
    revoked.setRevokedAt(Instant.now());
    // The id is assigned, so persist explicitly: repository.save would merge (select, then insert).
    this.entityManager.persist(revoked);
  }

  @Scheduled(fixedDelayString = "${os.auth.revoked-token-purge-delay-ms:3600000}")
  @Transactional
  public void purgeExpired() {
    this.repository.deleteExpired(Instant.now());
  }

  private static @NonNull String hash(final @NonNull String token) {
    return DigestUtils.sha256Hex(token);
  }
}
