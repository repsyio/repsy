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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1312 (decision RPS-1149): a deploy token authenticates by its secret alone. Every protocol
 * hands the Basic password (or the token-exchange password) to the deploy-token lookup before it
 * looks at the username, so the username is a label: a wrong, an empty or another user's name with
 * a valid secret is accepted, and a wrong secret with the token's own username is refused.
 *
 * <p>The tests are the documented behaviour (repsy-docs #42), so a change that also makes a
 * protocol require the username to match fails here on every protocol it touches. Each acceptance
 * is paired with a refusal on the same route, so a route that stops authenticating at all (public,
 * or refusing everyone) does not pass for "username ignored".
 *
 * <p>A protocol read is answered {@code 401} when authentication fails and by the route's own
 * status when it succeeds (the resources here do not exist, so most answer {@code 404}). Docker
 * takes no Basic credentials on {@code /v2} itself, so it is checked at the token endpoint and by
 * pulling with the token it issues.
 */
@DisplayName("A deploy token authenticates by its secret, whatever username is sent")
class DeployTokenPasswordOnlyIT extends AbstractIntegrationTest {

  private static final String MODULE = "example.com/mod";
  private static final String WRONG_USERNAME = "not-the-token-username";
  private static final String IMAGE = "some-image";
  private static final String CHECKSUM = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";

  @Autowired private RepoDeployTokenRepository deployTokenRepository;
  @Autowired private ObjectMapper objectMapper;

  /** What the client sends in the username field of the Basic credentials. */
  enum Username {
    /** The username Repsy generated for the token. */
    OWN,
    /** A name that belongs to nobody. */
    WRONG,
    /** Nothing at all, as {@code :secret}. */
    EMPTY,
    /** An existing user, who has a different password. */
    ANOTHER_USER
  }

  /** A deploy token as the panel shows it once: the generated username and the secret. */
  private record Deploy(UUID id, String username, String secret) {}

  /**
   * A read that needs authentication on a private repo, and the status it answers once the caller
   * is authenticated (the resource itself does not exist, or the route lists nothing).
   */
  private record Read(RepoType type, String path, int authenticatedStatus) {

    @Override
    public String toString() {
      return this.type + " " + this.path;
    }
  }

  /** One read per protocol that takes Basic credentials, plus Cargo's crate download. */
  static Stream<Read> reads() {
    return Stream.of(
        new Read(RepoType.MAVEN, "/{repo}/com/example/lib/1.0/lib-1.0.pom", 404),
        new Read(RepoType.NPM, "/{repo}/some-package", 404),
        new Read(RepoType.PYPI, "/{repo}/simple/some-package/", 404),
        new Read(RepoType.HELM, "/{repo}/index.yaml", 200),
        new Read(RepoType.CARGO, "/{repo}/so/me/some-crate", 404),
        // The module is never published, and an unknown module is a 404 (RPS-1428).
        new Read(RepoType.GOLANG, "/{repo}/" + MODULE + "/@v/list", 404),
        new Read(RepoType.RUBY, "/{repo}/names", 200),
        // The NuGet service index is public even on a private repo, so read a package instead.
        new Read(RepoType.NUGET, "/{repo}/v3/package/some.pkg/index.json", 404));
  }

  static Stream<Arguments> readsByUsername() {
    return reads()
        .flatMap(
            read -> Stream.of(Username.values()).map(username -> Arguments.of(read, username)));
  }

  static Stream<Arguments> readsByFoulUsername() {
    return reads()
        .flatMap(
            read ->
                Stream.of(Username.OWN, Username.WRONG, Username.EMPTY)
                    .map(username -> Arguments.of(read, username)));
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private int status(final AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return this.protocol(request).getStatus();
  }

  private Repo privateRepo(final RepoType type) {
    return this.seedRepo(type, uniqueRepoName("rps1312"), true, null);
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

    return new Deploy(entity.getId(), entity.getUsername(), secret);
  }

  private void expire(final Deploy token) {
    final var entity = this.deployTokenRepository.findById(token.id()).orElseThrow();

    entity.setExpirationDate(Instant.now().minus(1, ChronoUnit.DAYS));
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();
  }

  private Deploy seedToken(final Repo repo) {
    return this.seedToken(repo, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  private String usernameFor(final Username kind, final Deploy token) {
    return switch (kind) {
      case OWN -> token.username();
      case WRONG -> WRONG_USERNAME;
      case EMPTY -> "";
      case ANOTHER_USER -> this.createUser(uniqueUsername("other"), UserRole.USER).getUsername();
      case null -> throw new IllegalArgumentException("no username kind");
    };
  }

  private String basic(final Username kind, final Deploy token) {
    return basicAuth(this.usernameFor(kind, token), token.secret());
  }

  /** The token exchange every Docker client does before it pulls or pushes. */
  private MockHttpServletResponse dockerToken(final Repo repo, final String authorization)
      throws Exception {
    return this.protocol(
        get("/v2/token")
            .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE))
            .header(AUTHORIZATION, authorization));
  }

  /** Pulls a manifest that does not exist: 404 means the caller got past authentication. */
  private int pullManifest(final Repo repo, final String bearerToken) throws Exception {
    return this.status(
        get("/v2/{repo}/{image}/manifests/latest", repo.getName(), IMAGE)
            .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + bearerToken));
  }

  @Nested
  @DisplayName("is accepted with any username")
  class Accepted {

    @ParameterizedTest(name = "{0} with username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.DeployTokenPasswordOnlyIT#readsByUsername")
    void basicRead(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var token = seedToken(repo);

      // The same request without the token is refused, so the read really does need it.
      assertThat(status(get(read.path(), repo.getName()))).isEqualTo(401);
      assertThat(
              status(
                  get(read.path(), repo.getName()).header(AUTHORIZATION, basic(username, token))))
          .isEqualTo(read.authenticatedStatus());
    }

    @ParameterizedTest(name = "Maven write with username {0}")
    @EnumSource(Username.class)
    void mavenWrite(final Username username) throws Exception {
      // A checksum file is stored without registering an artifact, which the rolled-back test
      // transaction could not hold (the repo row is not committed): it needs WRITE all the same.
      final var repo = privateRepo(RepoType.MAVEN);
      final var token = seedToken(repo);
      final var path = "/{repo}/com/example/lib/1.0/lib-1.0.pom.sha1";

      assertThat(status(put(path, repo.getName()).content(CHECKSUM))).isEqualTo(401);
      assertThat(
              status(
                  put(path, repo.getName())
                      .header(AUTHORIZATION, basic(username, token))
                      .content(CHECKSUM)))
          .isEqualTo(200);
    }

    @ParameterizedTest(name = "npm login with username {0}")
    @EnumSource(Username.class)
    void npmLogin(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.NPM);
      final var token = seedToken(repo);
      final var name = usernameFor(username, token);
      final var response =
          protocol(
              put(LOGIN, repo.getName(), name)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsBytes(
                          Map.of("name", name, "password", token.secret()))));

      assertThat(response.getStatus()).isEqualTo(201);

      // The token it hands out is the deploy token's, whatever name the client typed.
      final var jwt = JsonPath.<String>read(response.getContentAsString(), "$.token");

      assertThat(
              status(
                  get("/{repo}/some-package", repo.getName())
                      .header(AUTHORIZATION, "Bearer " + jwt)))
          .isEqualTo(404);
    }

    @ParameterizedTest(name = "Docker token endpoint (Basic) with username {0}")
    @EnumSource(Username.class)
    void dockerTokenBasic(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo);
      final var response = dockerToken(repo, basic(username, token));

      assertThat(response.getStatus()).isEqualTo(200);

      final var jwt = JsonPath.<String>read(response.getContentAsString(), "$.token");

      assertThat(pullManifest(repo, jwt)).isEqualTo(404);
    }

    @ParameterizedTest(name = "Docker token endpoint (password grant) with username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "ANOTHER_USER"})
    void dockerTokenPasswordGrant(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo);
      final var response =
          protocol(
              post("/v2/token")
                  .param("grant_type", "password")
                  .param("username", usernameFor(username, token))
                  .param("password", token.secret())
                  .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE)));

      assertThat(response.getStatus()).isEqualTo(200);

      final var jwt = JsonPath.<String>read(response.getContentAsString(), "$.token");

      assertThat(pullManifest(repo, jwt)).isEqualTo(404);
    }
  }

  @Nested
  @DisplayName("Docker password grant")
  class DockerPasswordGrant {

    @Test
    @DisplayName("needs a non-blank username field, and treats a blank one as no credentials")
    void blankUsernameIsNoCredentials() throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo);

      // Only the form's shape is checked here: the password grant is unauthenticated without a
      // username, exactly like a request with no credentials. A Basic header takes any username.
      assertThat(
              protocol(
                      post("/v2/token")
                          .param("grant_type", "password")
                          .param("username", "")
                          .param("password", token.secret())
                          .param("scope", "repository:%s/%s:pull".formatted(repo.getName(), IMAGE)))
                  .getStatus())
          .isEqualTo(401);
      assertThat(dockerToken(repo, basicAuth("", token.secret())).getStatus()).isEqualTo(200);
    }
  }

  @Nested
  @DisplayName("Cargo is token-only")
  class CargoTokenOnly {

    private static final String CRATE = "/{repo}/so/me/some-crate";

    @Test
    @DisplayName("takes the raw secret in Authorization, with no username at all")
    void rawSecret() throws Exception {
      final var repo = privateRepo(RepoType.CARGO);
      final var token = seedToken(repo);

      assertThat(status(get(CRATE, repo.getName()))).isEqualTo(401);
      assertThat(status(get(CRATE, repo.getName()).header(AUTHORIZATION, token.secret())))
          .isEqualTo(404);
      assertThat(status(get(CRATE, repo.getName()).header(AUTHORIZATION, "nope"))).isEqualTo(401);
    }

    @ParameterizedTest(name = "/me with Basic username {0} answers a deploy-token JWT")
    @EnumSource(Username.class)
    void meIgnoresTheUsername(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.CARGO);
      final var token = seedToken(repo);
      final var response =
          protocol(get("/{repo}/me", repo.getName()).header(AUTHORIZATION, basic(username, token)));

      assertThat(response.getStatus()).isEqualTo(200);

      final var jwt = JsonPath.<String>read(response.getContentAsString(), "$.token");

      // The JWT is then sent as the raw Authorization value, as `cargo` does.
      assertThat(status(get(CRATE, repo.getName()).header(AUTHORIZATION, jwt))).isEqualTo(404);
    }
  }

  @Nested
  @DisplayName("is refused whatever the username is")
  class Refused {

    @ParameterizedTest(name = "{0} with the right username and a wrong secret; username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.DeployTokenPasswordOnlyIT#readsByFoulUsername")
    void wrongSecret(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var token = seedToken(repo);
      final var header = basicAuth(usernameFor(username, token), "not-" + token.secret());

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(401);
      // The username alone never carries a request: the valid secret is what does.
      assertThat(
              status(
                  get(read.path(), repo.getName())
                      .header(AUTHORIZATION, basicAuth(token.username(), token.secret()))))
          .isEqualTo(read.authenticatedStatus());
    }

    @ParameterizedTest(name = "{0} with an expired token; username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.DeployTokenPasswordOnlyIT#readsByFoulUsername")
    void expiredToken(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var token = seedToken(repo);
      final var header = basic(username, token);

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(read.authenticatedStatus());

      expire(token);

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(401);
    }

    @ParameterizedTest(name = "{0} with a revoked token; username {1}")
    @MethodSource("io.repsy.os.server.shared.auth.DeployTokenPasswordOnlyIT#readsByFoulUsername")
    void revokedToken(final Read read, final Username username) throws Exception {
      final var repo = privateRepo(read.type());
      final var token = seedToken(repo);
      final var header = basic(username, token);

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(read.authenticatedStatus());

      deployTokenRepository.deleteById(token.id());
      entityManager.flush();

      assertThat(status(get(read.path(), repo.getName()).header(AUTHORIZATION, header)))
          .isEqualTo(401);
    }

    @ParameterizedTest(name = "Maven write with a wrong secret; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void mavenWriteWrongSecret(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.MAVEN);
      final var token = seedToken(repo);

      assertThat(
              status(
                  put("/{repo}/com/example/lib/1.0/lib-1.0.pom.sha1", repo.getName())
                      .header(
                          AUTHORIZATION,
                          basicAuth(usernameFor(username, token), "not-" + token.secret()))
                      .content(CHECKSUM)))
          .isEqualTo(401);
    }

    @ParameterizedTest(name = "npm login with a wrong secret; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void npmLoginWrongSecret(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.NPM);
      final var token = seedToken(repo);
      final var name = usernameFor(username, token);

      assertThat(
              status(
                  put(LOGIN, repo.getName(), name)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsBytes(
                              Map.of("name", name, "password", "not-" + token.secret())))))
          .isEqualTo(401);
    }

    @ParameterizedTest(name = "Docker token endpoint with a wrong secret; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void dockerTokenWrongSecret(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo);
      final var header = basicAuth(usernameFor(username, token), "not-" + token.secret());

      assertThat(dockerToken(repo, header).getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "Docker token endpoint with an expired token; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void dockerTokenExpired(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo, Instant.now().minus(1, ChronoUnit.DAYS));

      assertThat(dockerToken(repo, basic(username, token)).getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "Docker token endpoint with a revoked token; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void dockerTokenRevoked(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.DOCKER);
      final var token = seedToken(repo);
      final var header = basic(username, token);

      assertThat(dockerToken(repo, header).getStatus()).isEqualTo(200);

      deployTokenRepository.deleteById(token.id());
      entityManager.flush();

      assertThat(dockerToken(repo, header).getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "npm login with an expired token; username {0}")
    @EnumSource(
        value = Username.class,
        names = {"OWN", "WRONG", "EMPTY"})
    void npmLoginExpired(final Username username) throws Exception {
      final var repo = privateRepo(RepoType.NPM);
      final var token = seedToken(repo, Instant.now().minus(1, ChronoUnit.DAYS));
      final var name = usernameFor(username, token);

      assertThat(
              status(
                  put(LOGIN, repo.getName(), name)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          objectMapper.writeValueAsBytes(
                              Map.of("name", name, "password", token.secret())))))
          .isEqualTo(401);
    }
  }
}
