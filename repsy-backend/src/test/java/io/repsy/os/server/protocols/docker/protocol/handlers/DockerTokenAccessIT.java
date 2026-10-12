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

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.token.DeployTokenHash;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-2216: {@code /v2/token} grants the intersection of what a client asked for and what its
 * credential may do in the named image, and the registry routes enforce what the token carries.
 * Each test names what the old behaviour did: a token asked for {@code pull} pushed, a token for
 * one image was used on another, and a deploy token carried no grants at all.
 *
 * <p>A scope the credential may not exercise is not a 401 at the token endpoint: the token has an
 * empty access list (the Docker token specification), so {@code docker login} keeps working and the
 * registry refuses the operation with {@code insufficient_scope}.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data.
 */
@DisplayName("Docker token access (RPS-2216)")
class DockerTokenAccessIT extends AbstractIT {

  private static final String IMAGE = "app";
  private static final String LAYER = "layer-one";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private DockerWire adminWire;

  @BeforeEach
  void setUp() {
    this.adminWire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.adminProtocolBearerToken());
  }

  private Repo dockerRepo(final boolean privateRepo) {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), privateRepo, null);
  }

  /** A repo with one pushed image {@code app:latest}. */
  private Repo repoWithImage(final boolean privateRepo) throws Exception {
    final var repo = this.dockerRepo(privateRepo);
    this.adminWire.pushBlobsOf(repo, IMAGE, LAYER);
    final var put = this.adminWire.putImage(repo, IMAGE, "latest", imageManifest(LAYER));
    assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(201);

    return repo;
  }

  private String seedDeployToken(final Repo repo, final boolean readOnly) {
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

  private static String scope(final Repo repo, final String image, final String actions) {
    return "repository:%s/%s:%s".formatted(repo.getName(), image, actions);
  }

  private MockHttpServletResponse exchange(
      final String username, final String password, final String... scopes) throws Exception {
    final var request =
        get("/v2/token").header(AUTHORIZATION, basicAuth(username, password)).with(protocolPort());

    for (final var scope : scopes) {
      request.param("scope", scope);
    }

    final var response = this.mockMvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

    return response;
  }

  private static String jwtOf(final MockHttpServletResponse response) throws Exception {
    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private static List<String> accessOf(final MockHttpServletResponse response) throws Exception {
    return JWT.decode(jwtOf(response)).getClaim("access").asList(String.class);
  }

  private MockHttpServletResponse pull(final Repo repo, final String image, final String jwt)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/latest", repo.getName(), image)
                .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse startPush(final Repo repo, final String image, final String jwt)
      throws Exception {
    return this.mockMvc
        .perform(
            post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), image)
                .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + jwt)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private static void assertInsufficientScope(
      final MockHttpServletResponse response, final String scope) {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE))
        .contains("scope=\"" + scope + "\"")
        .endsWith("error=\"insufficient_scope\"");
  }

  @Test
  @DisplayName("a deploy token asked for pull only gets pull, and cannot push (was: 202)")
  void deployTokenAskedForPullCannotPush() throws Exception {
    final var repo = this.repoWithImage(true);
    final var secret = this.seedDeployToken(repo, false);

    final var response = this.exchange("ignored", secret, scope(repo, IMAGE, "pull"));

    assertThat(accessOf(response)).containsExactly(repo.getName() + "/" + IMAGE + ":pull");
    assertThat(this.pull(repo, IMAGE, jwtOf(response)).getStatus()).isEqualTo(200);
    assertInsufficientScope(
        this.startPush(repo, IMAGE, jwtOf(response)), scope(repo, IMAGE, "pull,push"));
  }

  @Test
  @DisplayName("a read-write deploy token asked for push,pull gets both, a read-only one pull")
  void deployTokenGetsTheIntersectionWithItsReadOnlyFlag() throws Exception {
    final var repo = this.repoWithImage(true);

    final var readWrite =
        this.exchange("x", this.seedDeployToken(repo, false), scope(repo, IMAGE, "push,pull"));
    final var readOnly =
        this.exchange("x", this.seedDeployToken(repo, true), scope(repo, IMAGE, "push,pull"));

    assertThat(accessOf(readWrite)).containsExactly(repo.getName() + "/" + IMAGE + ":pull,push");
    assertThat(this.startPush(repo, IMAGE, jwtOf(readWrite)).getStatus()).isEqualTo(202);
    assertThat(accessOf(readOnly)).containsExactly(repo.getName() + "/" + IMAGE + ":pull");
    assertThat(this.pull(repo, IMAGE, jwtOf(readOnly)).getStatus()).isEqualTo(200);
    assertThat(this.startPush(repo, IMAGE, jwtOf(readOnly)).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a deploy token is granted nothing in another repo, and the token is still issued")
  void deployTokenOfAnotherRepoGetsAnEmptyAccessList() throws Exception {
    final var repo = this.repoWithImage(true);
    final var other = this.dockerRepo(true);
    final var secret = this.seedDeployToken(other, false);

    final var response = this.exchange("x", secret, scope(repo, IMAGE, "pull,push"));

    assertThat(accessOf(response)).isEmpty();
    assertThat(this.pull(repo, IMAGE, jwtOf(response)).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a token for one image is no token for another image of the same repo")
  void tokenForOneImageDoesNotReachAnother() throws Exception {
    final var repo = this.repoWithImage(true);
    final var secret = this.seedDeployToken(repo, false);

    final var response = this.exchange("x", secret, scope(repo, IMAGE, "pull,push"));

    assertThat(this.pull(repo, IMAGE, jwtOf(response)).getStatus()).isEqualTo(200);
    assertInsufficientScope(
        this.pull(repo, "other", jwtOf(response)), scope(repo, "other", "pull"));
    assertInsufficientScope(
        this.startPush(repo, "other", jwtOf(response)), scope(repo, "other", "pull,push"));
  }

  @Test
  @DisplayName("a user's token asked for pull cannot push, and delete is not granted to a user")
  void userTokenIsNarrowed() throws Exception {
    final var repo = this.repoWithImage(true);
    final var username = this.createUser(uniqueUsername("plain"), UserRole.USER).getUsername();

    final var pullOnly = this.exchange(username, VALID_PASSWORD, scope(repo, IMAGE, "pull"));
    final var everything =
        this.exchange(username, VALID_PASSWORD, scope(repo, IMAGE, "pull,push,delete"));

    assertThat(this.pull(repo, IMAGE, jwtOf(pullOnly)).getStatus()).isEqualTo(200);
    assertInsufficientScope(
        this.startPush(repo, IMAGE, jwtOf(pullOnly)), scope(repo, IMAGE, "pull,push"));
    assertThat(accessOf(everything)).containsExactly(repo.getName() + "/" + IMAGE + ":pull,push");
    assertThat(this.startPush(repo, IMAGE, jwtOf(everything)).getStatus()).isEqualTo(202);
  }

  @Test
  @DisplayName("a token asked for no scope, as docker login does, is issued and grants nothing")
  void dockerLoginTokenHasAnEmptyAccessList() throws Exception {
    final var repo = this.repoWithImage(true);
    final var username = this.createUser(uniqueUsername("login"), UserRole.USER).getUsername();

    final var response = this.exchange(username, VALID_PASSWORD);

    assertThat(accessOf(response)).isEmpty();
    assertInsufficientScope(this.pull(repo, IMAGE, jwtOf(response)), scope(repo, IMAGE, "pull"));
  }

  @Test
  @DisplayName(
      "anonymous pulls a public repo with the anonymous pull token, which carries only pull")
  void anonymousPullsAPublicRepo() throws Exception {
    final var repo = this.repoWithImage(false);

    final var response =
        this.mockMvc
            .perform(
                get("/v2/token").param("scope", scope(repo, IMAGE, "pull")).with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    assertThat(accessOf(response)).containsExactly(repo.getName() + "/" + IMAGE + ":pull");
    assertThat(this.pull(repo, IMAGE, jwtOf(response)).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("anonymous is never granted push: asking for it is refused at the token endpoint")
  void anonymousIsNotGrantedPush() throws Exception {
    final var repo = this.repoWithImage(false);

    final var response =
        this.mockMvc
            .perform(
                get("/v2/token")
                    .param("scope", scope(repo, IMAGE, "pull,push"))
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
  }
}
