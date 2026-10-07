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
package io.repsy.os.server.shared.auth;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * What the personal access token integration tests share (RPS-1903): seeding a token for a user,
 * and sending a request on the protocol port. It adds no Spring configuration, so every subclass
 * shares the context of {@link AbstractIntegrationTest}.
 */
public abstract class AbstractPatIntegrationTest extends AbstractIntegrationTest {

  /** A token as the panel shows it once: its id, its owner and its secret. */
  protected record Pat(UUID id, User owner, String secret) {}

  @Autowired protected PersonalAccessTokenRepository patRepository;

  /** A token of {@code owner} that works for 30 days, with {@code profile:read} and the scopes. */
  protected Pat seedPat(final User owner, final TokenScope... scopes) {
    return this.seedPat(owner, Instant.now().plus(Duration.ofDays(30)), scopes);
  }

  protected Pat seedPat(
      final User owner, final Instant expirationDate, final TokenScope... scopes) {
    final var secret = TokenFactory.personalAccessToken();
    final var token = new PersonalAccessToken();

    token.setUser(owner);
    token.setName("pat-" + secret.substring(4, 10));
    token.setTokenHash(TokenHash.hash(secret));
    token.setScopes(TokenScope.withImplicit(List.of(scopes)));
    token.setExpirationDate(expirationDate);
    this.patRepository.saveAndFlush(token);

    return new Pat(token.getId(), owner, secret);
  }

  /** Makes the token expire a day ago. */
  protected void expire(final Pat pat) {
    final var token = this.patRepository.findById(pat.id()).orElseThrow();

    token.setExpirationDate(Instant.now().minus(Duration.ofDays(1)));
    this.patRepository.saveAndFlush(token);
  }

  /** Revokes the token, which deletes its row. */
  protected void revoke(final Pat pat) {
    this.patRepository.deleteById(pat.id());
    this.entityManager.flush();
    this.entityManager.clear();
  }

  protected MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  protected int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getStatus();
  }

  protected Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps1903"), true, null);
  }
}
