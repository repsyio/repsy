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
package io.repsy.os.server.shared.token;

import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.protocols.shared.token.DeployTokenHash;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Inserts a deploy token for a repo, the way the panel would, so a protocol IT can use it. */
public final class DeployTokenFixtures {

  /** A deploy token's username and its secret (shown once, stored hashed). */
  public record Seeded(String username, String secret) {}

  private DeployTokenFixtures() {
    throw new UnsupportedOperationException("Utility class");
  }

  public static Seeded seed(
      final Repo repo,
      final boolean readOnly,
      final RepoRepository repoRepository,
      final RepoDeployTokenRepository deployTokenRepository,
      final EntityManager entityManager) {

    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    deployTokenRepository.save(entity);
    entityManager.flush();

    return new Seeded(entity.getUsername(), secret);
  }
}
