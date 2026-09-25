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
package io.repsy.os.server.protocols.cargo.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1239: {@code GET .../owners} used to require WRITE and never returned a {@code users} array,
 * so {@code cargo owner --list} failed client-side even though the raw GET answered 200. The route
 * is split by method: GET is now a READ that answers the crates.io {@code {"users": [...]}} shape
 * with a repo-level synthetic owner (Repsy has no ownership model finer than the repository), and
 * PUT/DELETE stay WRITE no-ops that report {@code {"ok":true,...}}.
 *
 * <p>This also fixes a latent auth gap: {@code getProperties()} used to omit {@code
 * writeOperation}, so PUT/DELETE on a <em>public</em> repo skipped authentication entirely. That is
 * a permission-narrowing behavior change on a currently-"working" (if degenerate) path — pinned
 * below by {@code publicRepoWriteNowRequiresAuth}.
 */
@DisplayName("Cargo owners route (RPS-1239)")
class CargoOwnersProtocolIT extends AbstractIntegrationTest {

  private static final String OWNERS = "/{repo}/api/v1/crates/some-crate/owners";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private Repo repo(final boolean privateRepo) {
    return this.seedRepo(RepoType.CARGO, uniqueRepoName("rps1239"), privateRepo, null);
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedToken(final Repo repo, final boolean readOnly) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  @Nested
  @DisplayName("GET .../owners")
  class Get {

    @Test
    @DisplayName("a read-only deploy token on a private repo gets 200 with a users array")
    void readOnlyTokenOnPrivateRepoCanList() throws Exception {
      final var repo = repo(true);
      final var secret = seedToken(repo, true);
      final var auth = basicAuth(repo.getName(), secret);

      final var result = protocol(get(OWNERS, repo.getName()).header(AUTHORIZATION, auth));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      final var body = result.getResponse().getContentAsString();
      final List<?> users = JsonPath.read(body, "$.users");
      assertThat(users).isNotEmpty();
      assertThat((String) JsonPath.read(body, "$.users[0].login")).isEqualTo(repo.getName());
    }

    @Test
    @DisplayName("no credential on a private repo gets 401 with the Basic challenge")
    void noCredentialOnPrivateRepoRefused() throws Exception {
      final var repo = repo(true);

      final var result = protocol(get(OWNERS, repo.getName()));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getHeader(WWW_AUTHENTICATE))
          .isEqualTo("Basic realm=\"Repsy\"");
    }

    @Test
    @DisplayName("no credential on a public repo still gets 200 with a users array")
    void noCredentialOnPublicRepoStillReads() throws Exception {
      final var repo = repo(false);

      final var result = protocol(get(OWNERS, repo.getName()));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      final List<?> users = JsonPath.read(result.getResponse().getContentAsString(), "$.users");
      assertThat(users).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("PUT/DELETE .../owners")
  class Modify {

    @Test
    @DisplayName("a write deploy token on a private repo can PUT and DELETE, body unchanged")
    void writeTokenOnPrivateRepoCanModify() throws Exception {
      final var repo = repo(true);
      final var secret = seedToken(repo, false);
      final var auth = basicAuth(repo.getName(), secret);

      final var putResult = protocol(put(OWNERS, repo.getName()).header(AUTHORIZATION, auth));
      assertThat(putResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(putResult.getResponse().getContentAsString())
          .contains("\"ok\":true")
          .contains("Ownership is managed at the repository level in this registry");

      final var deleteResult = protocol(delete(OWNERS, repo.getName()).header(AUTHORIZATION, auth));
      assertThat(deleteResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(deleteResult.getResponse().getContentAsString()).contains("\"ok\":true");
    }

    @Test
    @DisplayName("a read-only deploy token cannot PUT on a private repo")
    void readOnlyTokenCannotModify() throws Exception {
      final var repo = repo(true);
      final var secret = seedToken(repo, true);
      final var auth = basicAuth(repo.getName(), secret);

      final var result = protocol(put(OWNERS, repo.getName()).header(AUTHORIZATION, auth));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("no credential on a public repo now gets 401 (was an unauthenticated 200)")
    void publicRepoWriteNowRequiresAuth() throws Exception {
      final var repo = repo(false);

      final var result = protocol(put(OWNERS, repo.getName()));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
  }
}
