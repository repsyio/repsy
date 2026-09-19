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
package io.repsy.os.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Pins that a bearer JWT only authenticates the entry point it was issued for: panel access tokens
 * are refused by the protocol endpoints, and the tokens the package managers hold are refused by
 * the panel API, while the package-manager flows themselves keep working.
 *
 * <p>Protocol tokens are obtained through the real login flows (npm login, cargo {@code /me}) so a
 * test fails if an issuer stops stamping its tokens, not only if the verifier changes.
 */
@DisplayName("Token realms: panel vs protocol bearer tokens")
class TokenRealmIT extends AbstractIntegrationTest {

  private static final int PROTOCOL_PORT = 9090;

  /** Serves the request the way the protocol port does: main port, servlet path = request URI. */
  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private static String basic(final User user) {
    final var credentials = user.getUsername() + ":" + VALID_PASSWORD;

    return AuthUtils.AUTH_BASIC
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  /** The token {@code npm login} stores, issued by the npm wire protocol. */
  private String npmLoginToken(final String repoName, final User user) throws Exception {
    final var body =
        "{\"name\":\"%s\",\"password\":\"%s\"}".formatted(user.getUsername(), VALID_PASSWORD);
    final var result =
        this.protocol(
            put("/{repo}/-/user/org.couchdb.user:{name}", repoName, user.getUsername())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

    assertThat(result.getResponse().getStatus()).isEqualTo(201);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  /** The token {@code cargo login} exchanges credentials for, issued by the cargo wire protocol. */
  private String cargoToken(final String repoName, final User user) throws Exception {
    final var result =
        this.protocol(get("/{repo}/me", repoName).header(AUTHORIZATION, basic(user)));

    assertThat(result.getResponse().getStatus()).isEqualTo(200);

    return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
  }

  @Nested
  @DisplayName("panel endpoints")
  class PanelEndpoints {

    @Test
    @DisplayName("GET /api/profile accepts a panel access token")
    void acceptsPanelToken() throws Exception {
      final var user = createUser(uniqueUsername("panel"), UserRole.USER);

      perform(get("/api/profile").header(AUTHORIZATION, bearerTokenFor(user)))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/profile rejects a token issued for the protocol endpoints")
    void rejectsProtocolToken() throws Exception {
      final var user = createUser(uniqueUsername("proto"), UserRole.USER);

      perform(get("/api/profile").header(AUTHORIZATION, protocolBearerTokenFor(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("PUT /api/profile/password rejects a protocol token and leaves the password")
    void rejectsProtocolTokenOnPasswordChange() throws Exception {
      final var user = createUser(uniqueUsername("pwd"), UserRole.USER);
      final var hash = user.getHash();

      perform(
              put("/api/profile/password")
                  .header(AUTHORIZATION, protocolBearerTokenFor(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"password\":\"NewPassword2@\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));

      assertThat(userRepository.findById(user.getId()).orElseThrow().getHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("GET /api/profile rejects the token npm login hands out")
    void rejectsNpmLoginToken() throws Exception {
      final var user = createUser(uniqueUsername("npm"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-npm");
      seedRepo(RepoType.NPM, repoName, true, null);

      final var token = AuthUtils.AUTH_BEARER + npmLoginToken(repoName, user);

      perform(get("/api/profile").header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("GET /api/profile rejects the token cargo login hands out")
    void rejectsCargoToken() throws Exception {
      final var user = createUser(uniqueUsername("cargo"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-cargo");
      seedRepo(RepoType.CARGO, repoName, true, null);

      final var token = AuthUtils.AUTH_BEARER + cargoToken(repoName, user);

      perform(get("/api/profile").header(AUTHORIZATION, token))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("GET /api/users rejects an admin's protocol token")
    void rejectsProtocolTokenOnAdminEndpoint() throws Exception {
      final var admin = createUser(uniqueUsername("admin"), UserRole.ADMIN);

      perform(get("/api/users").header(AUTHORIZATION, protocolBearerTokenFor(admin)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("the repo API rejects a protocol token")
    void rejectsProtocolTokenOnRepoApi() throws Exception {
      final var user = createUser(uniqueUsername("repoapi"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-repo");
      seedRepo(RepoType.NPM, repoName, true, null);

      perform(
              get("/api/npm/packages/{repo}", repoName)
                  .header(AUTHORIZATION, protocolBearerTokenFor(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("a token without a realm is answered sessionExpired, so the UI refreshes it")
    void claimlessTokenAsksTheUiToRefresh() throws Exception {
      final var user = createUser(uniqueUsername("legacy"), UserRole.USER);

      perform(get("/api/profile").header(AUTHORIZATION, claimlessBearerTokenFor(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("sessionExpired"));
    }
  }

  @Nested
  @DisplayName("protocol endpoints")
  class ProtocolEndpoints {

    private static final String PACKUMENT = "/{repo}/some-package";

    @Test
    @DisplayName("npm rejects a panel access token")
    void npmRejectsPanelToken() throws Exception {
      final var user = createUser(uniqueUsername("npmpanel"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-npm");
      seedRepo(RepoType.NPM, repoName, true, null);

      final var result =
          protocol(get(PACKUMENT, repoName).header(AUTHORIZATION, bearerTokenFor(user)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).contains("accessNotAllowed");
    }

    @Test
    @DisplayName("npm accepts the token npm login hands out")
    void npmAcceptsItsOwnToken() throws Exception {
      final var user = createUser(uniqueUsername("npmown"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-npm");
      seedRepo(RepoType.NPM, repoName, true, null);

      final var token = AuthUtils.AUTH_BEARER + npmLoginToken(repoName, user);
      final var result = protocol(get(PACKUMENT, repoName).header(AUTHORIZATION, token));

      // Authenticated: the package does not exist, which is a 404, not an auth failure.
      assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("npm keeps accepting a token issued before tokens carried a realm")
    void npmAcceptsClaimlessToken() throws Exception {
      final var user = createUser(uniqueUsername("npmold"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-npm");
      seedRepo(RepoType.NPM, repoName, true, null);

      final var result =
          protocol(get(PACKUMENT, repoName).header(AUTHORIZATION, claimlessBearerTokenFor(user)));

      assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("cargo rejects a panel access token on /me and accepts its own")
    void cargoScopesItsTokens() throws Exception {
      final var user = createUser(uniqueUsername("cargo"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-cargo");
      seedRepo(RepoType.CARGO, repoName, true, null);

      final var panel =
          protocol(get("/{repo}/me", repoName).header(AUTHORIZATION, bearerTokenFor(user)));
      assertThat(panel.getResponse().getStatus()).isEqualTo(401);

      final var own =
          protocol(
              get("/{repo}/me", repoName)
                  .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + cargoToken(repoName, user)));
      assertThat(own.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("maven rejects a panel access token sent as a bearer header")
    void mavenRejectsPanelTokenInHeader() throws Exception {
      final var user = createUser(uniqueUsername("mvnhdr"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-mvn");
      seedRepo(RepoType.MAVEN, repoName, true, null);

      final var result =
          protocol(
              get("/{repo}/com/example/lib/1.0/lib-1.0.pom", repoName)
                  .header(AUTHORIZATION, bearerTokenFor(user)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("maven browser hand-off: a panel token in ?token= is accepted")
    void mavenAcceptsPanelTokenInQuery() throws Exception {
      final var user = createUser(uniqueUsername("mvnqry"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-mvn");
      seedRepo(RepoType.MAVEN, repoName, true, null);

      final var result =
          protocol(
              get("/{repo}/com/example/lib/1.0/lib-1.0.pom", repoName)
                  .param("token", panelTokenValue(user)));

      // Authenticated: the file does not exist, which is a 404, not an auth failure.
      assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("maven rejects a token without a realm and a protocol token in ?token=")
    void mavenQueryTokenMustBeAPanelToken() throws Exception {
      final var user = createUser(uniqueUsername("mvnbad"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-mvn");
      seedRepo(RepoType.MAVEN, repoName, true, null);

      final var protocolToken =
          protocolBearerTokenFor(user).substring(AuthUtils.AUTH_BEARER.length());
      final var result =
          protocol(
              get("/{repo}/com/example/lib/1.0/lib-1.0.pom", repoName)
                  .param("token", protocolToken));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a panel token in ?token= is not accepted by the other protocols")
    void queryHandOffIsMavenOnly() throws Exception {
      final var user = createUser(uniqueUsername("npmqry"), UserRole.USER);
      final var repoName = uniqueRepoName("realm-npm");
      seedRepo(RepoType.NPM, repoName, true, null);

      final var result = protocol(get(PACKUMENT, repoName).param("token", panelTokenValue(user)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private String panelTokenValue(final User user) {
      return jwtUtils.createPanelAccessToken(
          user.getId(), user.getUsername(), Duration.ofMinutes(30));
    }
  }
}
