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
package io.repsy.os.server.protocols.docker.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.TokenRealm;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RPS-1681: every protocol's pre-processor answers a rejected bearer credential with 401 ({@code
 * unAuthorized} or {@code sessionExpired}), never 403 (RPS-1209's convention: 403 is reserved for
 * "authenticated but not permitted"). Docker was the one protocol whose pre-processor was not
 * proven to follow it; this pins that a forged or expired Docker bearer token answers 401 like
 * every other protocol's does (see {@code ProtocolJwtTokenVersionIT#aRefusedTokenIsNotCounted} for
 * npm's equivalent case).
 */
@DisplayName("Docker's pre-processor answers 401, not 403, for a rejected bearer")
class DockerAuthPreProcessorIT extends AbstractIT {

  private static final String IMAGE = "some-image";
  private static final String MANIFEST_PATH = "/v2/{repo}/" + IMAGE + "/manifests/latest";

  private MockHttpServletResponse read(final String repoName, final String bearerToken)
      throws Exception {
    return this.mockMvc
        .perform(
            get(MANIFEST_PATH, repoName)
                .with(protocolPort())
                .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + bearerToken))
        .andReturn()
        .getResponse();
  }

  @Test
  @DisplayName("a forged bearer token answers 401 unAuthorized, not 403")
  void forgedBearerAnswers401() throws Exception {
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("rps1681forged"), true, null);

    final var response = this.read(repo.getName(), "not.a.valid.token");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("unAuthorized");
  }

  @Test
  @DisplayName("an expired bearer token answers 401 sessionExpired, not 403")
  void expiredBearerAnswers401() throws Exception {
    final var user = createUser(uniqueUsername("rps1681exp"), UserRole.USER);
    final var repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("rps1681expired"), true, null);
    final var secret = (String) ReflectionTestUtils.getField(this.jwtUtils, "secret");

    final var expired =
        JWT.create()
            .withJWTId(UUID.randomUUID().toString())
            .withSubject(user.getId().toString())
            .withAudience(TokenRealm.PROTOCOL.getAudience())
            .withClaim("username", user.getUsername())
            .withExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
            .sign(Algorithm.HMAC512(secret));

    final var response = this.read(repo.getName(), expired);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(401);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("sessionExpired");
  }
}
