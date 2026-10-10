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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * RPS-1903: a {@code rut-} secret that is unknown or expired is a failed credential. It counts
 * against {@link AuthFailureThrottle} like a wrong password, on the protocol routes and on the
 * {@code @RepoOperation} panel routes (one count), and a client over the limit is answered {@code
 * 429} with {@code Retry-After} before it learns anything. A secret that Repsy recognizes (valid,
 * but not allowed to do the thing) is not counted, and a valid one is never refused for the
 * failures of the client behind the same address.
 *
 * <p>Every test starts with an empty throttle ({@link io.repsy.os.AbstractIT}). The failures cost
 * no hash check, so the limit is reached quickly.
 */
@DisplayName("Failed personal access token checks are throttled per client")
class PatThrottleIT extends AbstractPatIT {

  private static final String READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";
  private static final String WRITE = "/{repo}/com/example/lib/1.0/lib-1.0.pom.sha1";
  private static final String PASSWORD_LIKE_A_TOKEN = "rut-this-is-a-password";
  private static final String PASSWORD_HASH = PasswordHasher.hash(PASSWORD_LIKE_A_TOKEN);

  @Autowired private AuthThrottleProperties properties;

  private User owner;
  private Repo repo;

  @BeforeEach
  void setUp() {
    this.owner = this.createUser(uniqueUsername("throttle"), UserRole.USER);
    this.repo = this.privateRepo(RepoType.MAVEN);
  }

  private int basic(final String secret) throws Exception {
    return this.status(
        get(READ, this.repo.getName()).header(AUTHORIZATION, basicAuth("anyone", secret)));
  }

  private MockHttpServletResponse bearer(final String secret) throws Exception {
    return this.protocol(get(READ, this.repo.getName()).header(AUTHORIZATION, "Bearer " + secret));
  }

  /** Spends the failures a client has, with unknown secrets, each answered 401. */
  private void useUpTheFailures() throws Exception {
    for (int i = 0; i < this.properties.maxFailures(); i++) {
      assertThat(this.basic(TokenFactory.personalAccessToken())).isEqualTo(401);
    }
  }

  @Test
  @DisplayName(
      "unknown secrets are refused with 401 until the limit, then with 429 and Retry-After")
  void unknownSecretsAreCounted() throws Exception {
    this.useUpTheFailures();

    final var response = this.bearer(TokenFactory.personalAccessToken());

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(response.getHeader(RETRY_AFTER))).isPositive();
    assertThat(this.basic(TokenFactory.personalAccessToken())).isEqualTo(429);
  }

  @Test
  @DisplayName("an expired token counts as a failure, like an unknown one")
  void anExpiredTokenIsCounted() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    this.expire(pat);

    for (int i = 0; i < this.properties.maxFailures(); i++) {
      assertThat(this.basic(pat.secret())).isEqualTo(401);
    }

    assertThat(this.basic(pat.secret())).isEqualTo(429);
  }

  @Test
  @DisplayName("a valid token is not refused for the failures of its client, and costs none")
  void aValidTokenIsNeverThrottled() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    for (int i = 0; i < this.properties.maxFailures() + 5; i++) {
      assertThat(this.basic(pat.secret())).isEqualTo(404);
    }

    this.useUpTheFailures();

    assertThat(this.basic(TokenFactory.personalAccessToken())).isEqualTo(429);
    assertThat(this.basic(pat.secret())).isEqualTo(404);
  }

  @Test
  @DisplayName("a token that is recognized but not allowed to do it is 401 and is not counted")
  void aRefusedTokenIsNotCounted() throws Exception {
    final var pat = this.seedPat(this.owner, TokenScope.REPO_READ);

    for (int i = 0; i < this.properties.maxFailures() + 5; i++) {
      assertThat(
              this.status(
                  put(WRITE, this.repo.getName())
                      .header(AUTHORIZATION, basicAuth("anyone", pat.secret()))
                      .content("x")))
          .isEqualTo(401);
    }

    assertThat(this.basic(pat.secret())).isEqualTo(404);
  }

  @Test
  @DisplayName("the panel routes of a repo count the same failures")
  void thePanelRoutesShareTheCount() throws Exception {
    for (int i = 0; i < this.properties.maxFailures(); i++) {
      this.mockMvc
          .perform(
              get("/api/repos/{repo}", this.repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, "Bearer " + TokenFactory.personalAccessToken()))
          .andReturn()
          .getResponse();
    }

    assertThat(this.basic(TokenFactory.personalAccessToken())).isEqualTo(429);
  }

  @Test
  @DisplayName("a password that looks like a token is never hashed: the protocols refuse it")
  void aSecretWithTheTokenPrefixNeverReachesThePasswordCheck() throws Exception {
    final var username = uniqueUsername("rutpass");

    this.userTxService.create(username, UserRole.USER, PASSWORD_HASH);
    this.entityManager.flush();

    assertThat(
            this.status(
                get(READ, this.repo.getName())
                    .header(AUTHORIZATION, basicAuth(username, PASSWORD_LIKE_A_TOKEN))))
        .isEqualTo(401);

    // It is the protocols' rule only: the panel login still takes the password.
    this.mockMvc
        .perform(
            post("/api/auth/login")
                .with(apiPort())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"password\":\"%s\"}"
                        .formatted(username, PASSWORD_LIKE_A_TOKEN)))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
