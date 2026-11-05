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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.services.DeployTokenService;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.token.DeployTokenHash;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2431: rotating a deploy token keeps its row and id and replaces the stored hash, so a JWT
 * minted from the old secret (npm login, cargo {@code /me}, Docker {@code /v2/token}) must stop
 * working at the rotation, not at its own expiry (up to 90 days for npm).
 */
@DisplayName("A JWT minted from a deploy token ends when the token is rotated")
class DeployTokenRotationJwtIT extends AbstractIT {

  private static final String IMAGE = "some-image";
  private static final String CRATE = "/{repo}/so/me/some-crate";
  private static final String PACKUMENT = "/{repo}/some-package";
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private DeployTokenService deployTokenService;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private ObjectMapper objectMapper;

  private record Deploy(UUID id, String secret) {}

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getStatus();
  }

  private Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps2431"), true, null);
  }

  private Deploy seedToken(final Repo repo, final Instant expiresAt) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(false);
    entity.setExpirationDate(expiresAt);
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return new Deploy(entity.getId(), secret);
  }

  private Deploy seedToken(final Repo repo) {
    return this.seedToken(repo, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  /** Rotates through the service the controller uses; the row and its id stay. */
  private String rotate(final Repo repo, final Deploy token) {
    final var newSecret = this.deployTokenService.rotateDeployToken(repo.getId(), token.id());

    this.entityManager.flush();
    this.entityManager.clear();

    return newSecret;
  }

  private String npmLogin(final Repo repo, final String secret) throws Exception {
    final var response =
        this.protocol(
            put(LOGIN, repo.getName(), "typed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", "typed", "password", secret))));

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private int npmRead(final Repo repo, final String jwt) throws Exception {
    return this.status(
        get(PACKUMENT, repo.getName()).header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));
  }

  private String cargoJwt(final Repo repo, final String secret) throws Exception {
    final var response =
        this.protocol(
            get("/{repo}/me", repo.getName()).header(AUTHORIZATION, basicAuth("whatever", secret)));

    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private int cargoRead(final Repo repo, final String jwt) throws Exception {
    return this.status(get(CRATE, repo.getName()).header(AUTHORIZATION, jwt));
  }

  private String dockerJwt(final Repo repo, final String secret) throws Exception {
    final var response =
        this.protocol(
            get("/v2/token")
                .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
                .header(AUTHORIZATION, basicAuth("whatever", secret)));

    assertThat(response.getStatus()).isEqualTo(200);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private int dockerPull(final Repo repo, final String jwt) throws Exception {
    return this.status(
        get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE)
            .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt));
  }

  @Test
  @DisplayName("npm: the login JWT is refused after rotation and a new login works")
  void npm() throws Exception {
    final var repo = privateRepo(RepoType.NPM);
    final var token = seedToken(repo);
    final var oldJwt = npmLogin(repo, token.secret());

    assertThat(npmRead(repo, oldJwt)).isEqualTo(404);

    final var newSecret = rotate(repo, token);

    assertThat(npmRead(repo, oldJwt)).isEqualTo(401);

    final var newJwt = npmLogin(repo, newSecret);

    assertThat(npmRead(repo, newJwt)).isEqualTo(404);
  }

  @Test
  @DisplayName("cargo: the /me JWT is refused after rotation and a new one works")
  void cargo() throws Exception {
    final var repo = privateRepo(RepoType.CARGO);
    final var token = seedToken(repo);
    final var oldJwt = cargoJwt(repo, token.secret());

    assertThat(cargoRead(repo, oldJwt)).isEqualTo(404);

    final var newSecret = rotate(repo, token);

    assertThat(cargoRead(repo, oldJwt)).isEqualTo(401);
    assertThat(cargoRead(repo, cargoJwt(repo, newSecret))).isEqualTo(404);
  }

  @Test
  @DisplayName("Docker: the /v2/token JWT is refused after rotation and a new one works")
  void docker() throws Exception {
    final var repo = privateRepo(RepoType.DOCKER);
    final var token = seedToken(repo);
    final var oldJwt = dockerJwt(repo, token.secret());

    assertThat(dockerPull(repo, oldJwt)).isEqualTo(404);

    final var newSecret = rotate(repo, token);

    assertThat(dockerPull(repo, oldJwt)).isEqualTo(401);
    assertThat(dockerPull(repo, dockerJwt(repo, newSecret))).isEqualTo(404);
  }

  @Test
  @DisplayName("a JWT without the hash fingerprint is refused (issued before RPS-2431)")
  void jwtWithoutFingerprint() throws Exception {
    final var repo = privateRepo(RepoType.NPM);
    final var token = seedToken(repo);
    final var legacy =
        this.jwtUtils.createProtocolToken(
            token.id(),
            "typed",
            java.time.Period.ofDays(1),
            io.repsy.os.shared.auth.dtos.AuthenticationType.DEPLOY_TOKEN);

    assertThat(npmRead(repo, legacy)).isEqualTo(401);
  }

  @Test
  @DisplayName("the npm login JWT never outlives the token")
  void npmJwtIsCappedAtTheTokenExpiry() throws Exception {
    final var repo = privateRepo(RepoType.NPM);
    final var expiresAt = Instant.now().plus(2, ChronoUnit.DAYS);
    final var token = seedToken(repo, expiresAt);
    final Date jwtExpiry = JWT.decode(npmLogin(repo, token.secret())).getExpiresAt();

    assertThat(jwtExpiry.toInstant()).isBeforeOrEqualTo(expiresAt.plusSeconds(1));
  }

  @Test
  @DisplayName("the JWT carries a fingerprint, not the stored hash")
  void fingerprintIsNotTheHash() throws Exception {
    final var repo = privateRepo(RepoType.NPM);
    final var token = seedToken(repo);
    final var jwt = JWT.decode(npmLogin(repo, token.secret()));
    final var fingerprint = jwt.getClaim("tf").asString();

    assertThat(fingerprint).isNotBlank();
    assertThat(DeployTokenHash.hash(token.secret())).doesNotContain(fingerprint);
    assertThat(List.of(jwt.getToken())).noneMatch(t -> t.contains(token.secret()));
  }
}
