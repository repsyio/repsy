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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1329: {@code npm whoami} ({@code GET /-/whoami}) and {@code npm ping} ({@code GET /-/ping})
 * used to end in {@code 404 unknownPath}. Every request goes through the real router, pre-processor
 * and handler, and uses the credentials an npm client would hold.
 */
@DisplayName("npm wire protocol GET /-/whoami and /-/ping")
class NpmWhoamiPingProtocolIT extends AbstractIntegrationTest {

  private static final String WHOAMI = "/{repo}/-/whoami";
  private static final String PING = "/{repo}/-/ping";
  private static final String LOGIN = "/{repo}/-/user/org.couchdb.user:{name}";
  private static final String TYPED_NAME = "whatever-the-client-typed";
  private static final String BASIC_CHALLENGE = "Basic realm=\"Repsy Managed Registry\"";
  private static final String BEARER_CHALLENGE =
      "Bearer realm=\"Repsy Managed Registry\", " + BASIC_CHALLENGE;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo privateRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("who"), true, null);
  }

  private Repo publicRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("who"), false, null);
  }

  private static String bearer(final String token) {
    return AuthUtils.AUTH_BEARER + token;
  }

  /** The token an npm client stores after {@code npm login}. */
  private String loginToken(final Repo repo, final String name, final String password)
      throws Exception {
    final var response =
        this.protocol(
            put(LOGIN, repo.getName(), name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    this.objectMapper.writeValueAsBytes(
                        Map.of("name", name, "password", password))));

    assertThat(response.getStatus()).isEqualTo(201);

    return JsonPath.read(response.getContentAsString(), "$.token");
  }

  private String seedToken(final Repo repo, final String username, final Instant expiresAt) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(username);
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(true);
    entity.setExpirationDate(expiresAt);
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  private String seedToken(final Repo repo, final String username) {
    return this.seedToken(repo, username, Instant.now().plus(30, ChronoUnit.DAYS));
  }

  private String whoami(final MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);

    return JsonPath.read(response.getContentAsString(), "$.username");
  }

  @Test
  @DisplayName("whoami answers the user of a Basic password")
  void basicUser() throws Exception {
    final var user = createUser(uniqueUsername("basic"), UserRole.USER);
    final var repo = this.privateRepo();

    final var response =
        this.protocol(
            get(WHOAMI, repo.getName())
                .header(AUTHORIZATION, basicAuth(user.getUsername(), VALID_PASSWORD)));

    assertThat(this.whoami(response)).isEqualTo(user.getUsername());
    assertThat(response.getContentAsString())
        .isEqualTo("{\"username\":\"" + user.getUsername() + "\"}");
  }

  @Test
  @DisplayName("whoami answers the user of a protocol JWT")
  void protocolJwt() throws Exception {
    final var user = createUser(uniqueUsername("jwt"), UserRole.USER);
    final var repo = this.privateRepo();

    final var response =
        this.protocol(
            get(WHOAMI, repo.getName()).header(AUTHORIZATION, this.protocolBearerTokenFor(user)));

    assertThat(this.whoami(response)).isEqualTo(user.getUsername());
  }

  @Test
  @DisplayName("whoami answers the user of the token that npm login returned")
  void loginToken() throws Exception {
    final var user = createUser(uniqueUsername("login"), UserRole.USER);
    final var repo = this.publicRepo();
    final var token = this.loginToken(repo, user.getUsername(), VALID_PASSWORD);

    final var response =
        this.protocol(get(WHOAMI, repo.getName()).header(AUTHORIZATION, bearer(token)));

    assertThat(this.whoami(response)).isEqualTo(user.getUsername());
  }

  @Test
  @DisplayName("whoami answers a deploy token's generated username, however it is presented")
  void deployTokenUsername() throws Exception {
    final var typedUser = createUser(uniqueUsername("victim"), UserRole.ADMIN);
    final var repo = this.privateRepo();
    final var generated = TokenUsernameGenerator.deployTokenUsername();
    final var secret = this.seedToken(repo, generated);
    final var loginJwt = this.loginToken(repo, typedUser.getUsername(), secret);

    final var asBasic =
        this.protocol(
            get(WHOAMI, repo.getName())
                .header(AUTHORIZATION, basicAuth(typedUser.getUsername(), secret)));
    final var asRawBearer =
        this.protocol(get(WHOAMI, repo.getName()).header(AUTHORIZATION, bearer(secret)));
    final var asLoginJwt =
        this.protocol(get(WHOAMI, repo.getName()).header(AUTHORIZATION, bearer(loginJwt)));

    assertThat(this.whoami(asBasic)).isEqualTo(generated).isNotEqualTo(typedUser.getUsername());
    assertThat(this.whoami(asRawBearer)).isEqualTo(generated);
    assertThat(this.whoami(asLoginJwt)).isEqualTo(generated).isNotEqualTo(typedUser.getUsername());
  }

  @Test
  @DisplayName("whoami refuses a deploy token of another repo and an expired one")
  void unusableDeployTokens() throws Exception {
    final var repo = this.privateRepo();
    final var other = this.privateRepo();
    final var ofOther = this.seedToken(other, TokenUsernameGenerator.deployTokenUsername());
    final var expired =
        this.seedToken(
            repo, TokenUsernameGenerator.deployTokenUsername(), Instant.now().minusSeconds(60));

    for (final var secret : new String[] {ofOther, expired}) {
      assertThat(
              this.protocol(get(WHOAMI, repo.getName()).header(AUTHORIZATION, bearer(secret)))
                  .getStatus())
          .isEqualTo(401);
      assertThat(
              this.protocol(
                      get(WHOAMI, repo.getName()).header(AUTHORIZATION, basicAuth("x", secret)))
                  .getStatus())
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("whoami needs credentials on a public repo too, and challenges with Basic")
  void publicRepoWithoutCredentials() throws Exception {
    final var repo = this.publicRepo();

    final var response = this.protocol(get(WHOAMI, repo.getName()));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo(BASIC_CHALLENGE);
  }

  @Test
  @DisplayName("whoami challenges a Bearer credential that is refused with Bearer first")
  void junkBearer() throws Exception {
    final var repo = this.privateRepo();

    final var response =
        this.protocol(
            get(WHOAMI, repo.getName())
                .header(AUTHORIZATION, bearer("not.a.token"))
                .with(remoteAddr("10.32.9.1")));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo(BEARER_CHALLENGE);
  }

  @Test
  @DisplayName("whoami refuses a wrong Basic password")
  void wrongPassword() throws Exception {
    final var user = createUser(uniqueUsername("wrong"), UserRole.USER);
    final var repo = this.publicRepo();

    final var response =
        this.protocol(
            get(WHOAMI, repo.getName())
                .header(AUTHORIZATION, basicAuth(user.getUsername(), "not-the-password"))
                .with(remoteAddr("10.32.9.2")));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("ping answers an empty JSON object on a public repo without credentials")
  void pingPublic() throws Exception {
    final var repo = this.publicRepo();

    final var response = this.protocol(get(PING, repo.getName()));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString()).isEqualTo("{}");
  }

  @Test
  @DisplayName("ping of a private repo needs read access, and ignores the ?write=true of npm 6")
  void pingPrivate() throws Exception {
    final var user = createUser(uniqueUsername("ping"), UserRole.USER);
    final var repo = this.privateRepo();

    final var anonymous = this.protocol(get(PING, repo.getName()).param("write", "true"));
    final var authenticated =
        this.protocol(
            get(PING, repo.getName())
                .param("write", "true")
                .header(AUTHORIZATION, this.protocolBearerTokenFor(user)));

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(anonymous.getHeader(WWW_AUTHENTICATE)).isEqualTo(BASIC_CHALLENGE);
    assertThat(authenticated.getStatus()).isEqualTo(200);
    assertThat(authenticated.getContentAsString()).isEqualTo("{}");
  }

  @Test
  @DisplayName("of another repo type the paths stay unknown")
  void otherRepoTypes() throws Exception {
    final var maven = this.seedRepo(RepoType.MAVEN, uniqueRepoName("who-mvn"), false, null);

    assertThat(this.protocol(get(PING, maven.getName())).getStatus()).isEqualTo(404);
    assertThat(this.protocol(get(WHOAMI, maven.getName())).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("packages named whoami and ping keep their packuments, and the endpoints still work")
  void packagesWithTheSameNames() throws Exception {
    final var user = createUser(uniqueUsername("names"), UserRole.ADMIN);
    final var token = this.protocolBearerTokenFor(user);
    final var repo = this.publicRepo();

    for (final var name : new String[] {"whoami", "ping"}) {
      assertThat(
              this.protocol(
                      put("/{repo}/{name}", repo.getName(), name)
                          .header(AUTHORIZATION, token)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              NpmPublishBodies.body(
                                  this.objectMapper, repo.getName(), name, "1.0.0", Map.of())))
                  .getStatus())
          .isEqualTo(200);
    }

    final var packument = this.protocol(get("/{repo}/whoami", repo.getName()));
    final var pingPackument = this.protocol(get("/{repo}/ping", repo.getName()));
    final var whoami = this.protocol(get(WHOAMI, repo.getName()).header(AUTHORIZATION, token));
    final var ping = this.protocol(get(PING, repo.getName()));

    assertThat(packument.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(packument.getContentAsString(), "$.name")).isEqualTo("whoami");
    assertThat(JsonPath.<String>read(pingPackument.getContentAsString(), "$.name"))
        .isEqualTo("ping");
    assertThat(this.whoami(whoami)).isEqualTo(user.getUsername());
    assertThat(ping.getContentAsString()).isEqualTo("{}");
  }
}
