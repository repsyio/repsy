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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-986: the token {@code GET /v2/token} hands to a caller without credentials is labelled {@code
 * anonymous}. It must never be looked up as the user of that name, or an admin who happens to be
 * called {@code anonymous} would be acted for by every anonymous {@code docker pull}.
 *
 * <p>The user is created directly, which is how an instance that predates the reserved name has
 * one.
 *
 * <p>RPS-1097: the token endpoint hands the token out for a public repo only. A private repo is
 * refused at the endpoint itself, before any token exists.
 */
@DisplayName("Docker anonymous token")
class DockerAnonymousTokenIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";

  private String anonymousToken;
  private Repo publicRepo;
  private Repo privateRepo;

  @BeforeEach
  void seed() throws Exception {
    // The worst case: an ADMIN, so nothing about the user itself would refuse the request.
    this.createUser("anonymous", UserRole.ADMIN);

    this.publicRepo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("pub"), false, null);
    this.privateRepo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("priv"), true, null);

    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param(
                        "scope",
                        "repository:%s/%s:pull".formatted(this.publicRepo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    this.anonymousToken =
        AuthUtils.AUTH_BEARER + JsonPath.<String>read(response.getContentAsString(), "$.token");
  }

  /**
   * Pulls a manifest that does not exist. A request that gets past authentication answers 404, one
   * that does not answers 401, so the status tells whether the caller was let in.
   */
  private int pullManifest(final Repo repo, final String authorization) throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE)
                .header(AUTHORIZATION, authorization)
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("is typed as anonymous, so its username claim never identifies a user")
  void tokenIsTyped() {
    final var type =
        this.jwtUtils.extractAuthenticationType(this.anonymousToken, TokenRealm.PROTOCOL);

    assertThat(type).isEqualTo(AuthenticationType.ANONYMOUS);
  }

  @Test
  @DisplayName("reads a public repo")
  void readsPublicRepo() throws Exception {
    assertThat(this.pullManifest(this.publicRepo, this.anonymousToken)).isEqualTo(404);
  }

  @Test
  @DisplayName("cannot start a blob upload, even though a user named anonymous could")
  void cannotPush() throws Exception {
    final var status =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", this.publicRepo.getName(), IMAGE)
                    .header(AUTHORIZATION, this.anonymousToken)
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(401);
  }

  @Test
  @DisplayName("cannot read a private repo, even though a user named anonymous could")
  void cannotReadPrivateRepo() throws Exception {
    assertThat(this.pullManifest(this.privateRepo, this.anonymousToken)).isEqualTo(401);
  }

  @Test
  @DisplayName("does not change what a real user's token can read")
  void userTokenStillReadsPrivateRepo() throws Exception {
    final var token = this.adminProtocolBearerToken();

    assertThat(this.pullManifest(this.privateRepo, token)).isEqualTo(404);
  }

  @Test
  @DisplayName("is not handed out for a private repo, whose token request is challenged")
  void noTokenForPrivateRepo() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param(
                        "scope",
                        "repository:%s/%s:pull".formatted(this.privateRepo.getName(), IMAGE))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Basic realm=");
    assertThat(response.getContentAsString()).doesNotContain("token");
  }
}
