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

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.repositories.RevokedProtocolTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** RPS-1361: the registry of the protocol tokens that a logout revoked. */
@DisplayName("RevokedProtocolTokenService")
class RevokedProtocolTokenServiceIT extends AbstractIntegrationTest {

  @Autowired private RevokedProtocolTokenService service;
  @Autowired private RevokedProtocolTokenRepository repository;

  private static String token() {
    return "header." + UUID.randomUUID() + ".signature";
  }

  private static Instant in(final long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS);
  }

  @Test
  @DisplayName("a token is revoked once revoke() has run, and no other token is")
  void revokes() {
    final var revoked = token();
    final var other = token();

    assertThat(this.service.isRevoked(revoked)).isFalse();

    this.service.revoke(revoked, in(30));

    assertThat(this.service.isRevoked(revoked)).isTrue();
    assertThat(this.service.isRevoked(other)).isFalse();
  }

  @Test
  @DisplayName("the token itself is not stored, only its SHA-256")
  void storesOnlyTheHash() {
    final var revoked = token();

    this.service.revoke(revoked, in(30));

    final var hash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(revoked);
    assertThat(this.repository.findById(hash)).isPresent();
    assertThat(this.repository.findById(revoked)).isEmpty();
  }

  @Test
  @DisplayName("revoking a token twice keeps one row and is not an error")
  void revokingTwiceIsFine() {
    final var revoked = token();
    final var before = this.repository.count();

    this.service.revoke(revoked, in(30));
    this.service.revoke(revoked, in(30));

    assertThat(this.repository.count()).isEqualTo(before + 1);
    assertThat(this.service.isRevoked(revoked)).isTrue();
  }

  @Test
  @DisplayName("purgeExpired() forgets a token that has expired anyway and keeps a live one")
  void purgesExpiredTokens() {
    final var expired = token();
    final var live = token();
    this.service.revoke(expired, Instant.now().minus(1, ChronoUnit.HOURS));
    this.service.revoke(live, in(30));

    this.service.purgeExpired();

    assertThat(this.service.isRevoked(expired)).isFalse();
    assertThat(this.service.isRevoked(live)).isTrue();
  }
}
