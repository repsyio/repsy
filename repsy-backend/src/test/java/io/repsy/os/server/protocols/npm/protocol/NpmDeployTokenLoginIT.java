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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1045: {@code npm login} with a deploy token as the password has to answer with something the
 * npm endpoints accept for that repo, and never with the stored SHA-256 hash of the secret.
 *
 * <p>Every test logs in through the real {@code PUT /-/user/org.couchdb.user:{name}} flow and then
 * uses the returned token as the {@code _authToken} an npm client would send, so a regression shows
 * up whichever side (issuer or verifier) moves.
 */
@DisplayName("npm login with a deploy token")
class NpmDeployTokenLoginIT extends AbstractIntegrationTest {

  private static final String PACKAGE = "login-package";
  private static final String PACKUMENT = "/{repo}/" + PACKAGE;
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String TYPED_NAME = "whatever-the-client-typed";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private ObjectMapper objectMapper;

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getResponse().getStatus();
  }

  private static String bearer(final String token) {
    return AuthUtils.AUTH_BEARER + token;
  }

  private MvcResult login(final Repo repo, final String name, final String password)
      throws Exception {
    return this.protocol(
        put(LOGIN, repo.getName(), name)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                this.objectMapper.writeValueAsBytes(Map.of("name", name, "password", password))));
  }

  /** The token an npm client stores after {@code npm login} with a deploy-token secret. */
  private String loginToken(final Repo repo, final String secret) throws Exception {
    final var result = this.login(repo, TYPED_NAME, secret);

    assertThat(result.getResponse().getStatus()).isEqualTo(201);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  /** A minimal {@code npm publish} body: one version and its tarball, base64 encoded. */
  private byte[] publishBody(final String version) throws Exception {
    final var tarball =
        Base64.getEncoder().encodeToString("tarball".getBytes(StandardCharsets.UTF_8));
    final var versionMetadata =
        Map.of(
            "name",
            PACKAGE,
            "version",
            version,
            "dist",
            Map.of(
                "tarball",
                "http://localhost:9090/npm/tenant/"
                    + PACKAGE
                    + "/-/"
                    + PACKAGE
                    + "-"
                    + version
                    + ".tgz"));
    final var body =
        Map.of(
            "_id",
            PACKAGE,
            "name",
            PACKAGE,
            "dist-tags",
            Map.of("latest", version),
            "versions",
            Map.of(version, versionMetadata),
            "_attachments",
            Map.of(
                PACKAGE + "-" + version + ".tgz",
                Map.of("content_type", "application/octet-stream", "data", tarball, "length", 7)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedToken(final Repo repo, final boolean readOnly, final Instant expiresAt) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(expiresAt);
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  private String seedToken(final Repo repo, final boolean readOnly) {
    return this.seedToken(repo, readOnly, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  private Repo privateRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("rps1045"), true, null);
  }

  @Test
  @DisplayName("answers 201 with a token that is neither the stored hash nor the secret")
  void neverReturnsTheStoredHash() throws Exception {
    final var repo = privateRepo();
    final var secret = seedToken(repo, false);
    final var result = this.login(repo, TYPED_NAME, secret);
    final var body = result.getResponse().getContentAsString();
    final var token = JsonPath.<String>read(body, "$.token");

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    assertThat(token).isNotEqualTo(DeployTokenHash.hash(secret)).isNotEqualTo(secret);
    assertThat(body).doesNotContain(DeployTokenHash.hash(secret)).doesNotContain(secret);
  }

  @Test
  @DisplayName("answers a deploy-token JWT that carries the username the client typed")
  void answersADeployTokenJwt() throws Exception {
    final var repo = privateRepo();
    final var admin = createUser(uniqueUsername("victim"), UserRole.ADMIN);
    final var secret = seedToken(repo, false);
    final var result = this.login(repo, admin.getUsername(), secret);
    final var decoded =
        JWT.decode(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.token"));

    // The username is the client's choice, so the token must not read as that user's (RPS-979).
    assertThat(decoded.getClaim("username").asString()).isEqualTo(admin.getUsername());
    assertThat(decoded.getClaim("authentication_type").asString()).isEqualTo("deploy_token");
    assertThat(decoded.getSubject()).isNotEqualTo(admin.getId().toString());
  }

  @Test
  @DisplayName("of a read-write token publishes and reads with the returned token")
  void readWriteTokenPublishesAndReads() throws Exception {
    final var repo = privateRepo();
    final var token = loginToken(repo, seedToken(repo, false));

    assertThat(
            status(
                put(PACKUMENT, repo.getName())
                    .header(AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody("1.0.0"))))
        .isEqualTo(200);
    assertThat(status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(200);
  }

  @Test
  @DisplayName("of a read-only token reads but cannot publish with the returned token")
  void readOnlyTokenCannotPublish() throws Exception {
    final var repo = privateRepo();
    final var writer = loginToken(repo, seedToken(repo, false));
    final var reader = loginToken(repo, seedToken(repo, true));

    assertThat(
            status(
                put(PACKUMENT, repo.getName())
                    .header(AUTHORIZATION, bearer(writer))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody("1.0.0"))))
        .isEqualTo(200);
    assertThat(
            status(
                put(PACKUMENT, repo.getName())
                    .header(AUTHORIZATION, bearer(reader))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody("1.0.1"))))
        .isEqualTo(401);
    assertThat(status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(reader))))
        .isEqualTo(200);
  }

  @Test
  @DisplayName("of another repo is refused at login, and its token is refused here")
  void tokenOfAnotherRepoIsRefused() throws Exception {
    final var repoA = privateRepo();
    final var repoB = privateRepo();
    final var secretA = seedToken(repoA, false);
    final var tokenA = loginToken(repoA, secretA);

    assertThat(this.login(repoB, TYPED_NAME, secretA).getResponse().getStatus()).isEqualTo(401);
    assertThat(status(get(PACKUMENT, repoB.getName()).header(AUTHORIZATION, bearer(tokenA))))
        .isEqualTo(401);
    assertThat(
            status(
                put(PACKUMENT, repoB.getName())
                    .header(AUTHORIZATION, bearer(tokenA))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody("1.0.0"))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("refuses an expired token at login")
  void expiredTokenIsRefusedAtLogin() throws Exception {
    final var repo = privateRepo();
    final var secret = seedToken(repo, false, Instant.now().minusSeconds(60));
    final var result = this.login(repo, TYPED_NAME, secret);

    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.token"))
        .isNull();
  }

  @Test
  @DisplayName("stops working once its deploy token is revoked")
  void revokedTokenIsRefused() throws Exception {
    final var repo = privateRepo();
    final var secret = seedToken(repo, false);
    final var token = loginToken(repo, secret);

    this.deployTokenRepository.delete(
        this.deployTokenRepository
            .findByRepoIdAndToken(repo.getId(), DeployTokenHash.hash(secret))
            .orElseThrow());
    this.entityManager.flush();

    assertThat(status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(401);
  }

  @Test
  @DisplayName("without a name in the body still answers a token that works")
  void loginWithoutANameStillWorks() throws Exception {
    final var repo = privateRepo();
    final var secret = seedToken(repo, false);
    final var result =
        this.protocol(
            put(LOGIN, repo.getName(), "undefined")
                .contentType(MediaType.APPLICATION_JSON)
                .content(this.objectMapper.writeValueAsBytes(Map.of("password", secret))));

    assertThat(result.getResponse().getStatus()).isEqualTo(201);

    final var token = JsonPath.<String>read(result.getResponse().getContentAsString(), "$.token");

    assertThat(status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(404); // authenticated; nothing is published
  }

  @Test
  @DisplayName("with user credentials still answers a token that works")
  void userLoginStillWorks() throws Exception {
    final var user = createUser(uniqueUsername("npmuser"), UserRole.USER);
    final var repo = privateRepo();
    final var result = this.login(repo, user.getUsername(), VALID_PASSWORD);

    assertThat(result.getResponse().getStatus()).isEqualTo(201);

    final var token = JsonPath.<String>read(result.getResponse().getContentAsString(), "$.token");

    assertThat(
            status(
                put(PACKUMENT, repo.getName())
                    .header(AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody("1.0.0"))))
        .isEqualTo(200);
    assertThat(status(get(PACKUMENT, repo.getName()).header(AUTHORIZATION, bearer(token))))
        .isEqualTo(200);
  }
}
