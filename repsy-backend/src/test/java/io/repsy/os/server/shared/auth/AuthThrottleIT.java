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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.auth.dtos.AuthenticationType;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-1092: a client that keeps sending wrong credentials is refused with 429 before it costs
 * another BCrypt verification, on the protocol routes, on the {@code /api} routes that take Basic
 * credentials, and on the panel login, which share one count.
 *
 * <p>Every test starts with an empty throttle ({@link AbstractIntegrationTest}), and MockMvc's
 * default address is the one client that fails; a second client is sent from another address with
 * {@code remoteAddr}. The throttle, the password cache and the user service are spies: the first
 * gives the tests a clock, the other two show whether a request reached the hash check or the user
 * lookup. That gives this class a Spring context of its own.
 */
@DisplayName("Failed password checks are throttled per client")
class AuthThrottleIT extends AbstractIntegrationTest {

  private static final int MAX_FAILURES = 20;
  private static final String COUNT_URL = "/api/repos/MAVEN/count";
  private static final String PRIVATE_READ = "/{repo}/com/example/lib/1.0/lib-1.0.pom";
  private static final String NPM_BEARER_CHALLENGE =
      "Bearer realm=\"Repsy Managed Registry\", Basic realm=\"Repsy Managed Registry\"";
  private static final String OTHER_CLIENT = "198.51.100.9";
  private static final String TOO_MANY_TEXT =
      "Too many failed authentication attempts. Please try again later.";

  @MockitoSpyBean private AuthFailureThrottle throttle;
  @MockitoSpyBean private VerifiedPasswordCache cache;
  @MockitoSpyBean private UserTxService spiedUsers;

  @Autowired private AuthThrottleProperties properties;
  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private final AtomicLong skewNanos = new AtomicLong();

  private String username;

  @BeforeEach
  void createAdminAndStartTheClock() {
    this.username = uniqueUsername("throttle");
    this.createUser(this.username, UserRole.ADMIN);

    this.skewNanos.set(0);
    // A fixed base plus a controlled skew, not System.nanoTime() plus the skew: the 20 failed
    // checks a test drives each cost a real BCrypt verification, and on a loaded machine that can
    // itself take more than a second, which would eat into the window this class advances by hand
    // (RPS-1175). Capturing the base once and never reading the wall clock again makes every test
    // here independent of how long those checks actually take.
    final var base = System.nanoTime();
    doAnswer(invocation -> base + this.skewNanos.get()).when(this.throttle).now();
  }

  private void advance(final Duration duration) {
    this.skewNanos.addAndGet(duration.toNanos());
  }

  // ---------------------------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------------------------

  private MockHttpServletResponse send(final MockHttpServletRequestBuilder request)
      throws Exception {
    return this.mockMvc.perform(request).andReturn().getResponse();
  }

  /** An {@code /api} route that takes Basic credentials and needs an admin. */
  private MockHttpServletResponse apiBasic(final String user, final String password)
      throws Exception {
    return this.send(
        get(COUNT_URL).header(AUTHORIZATION, basicAuth(user, password)).with(apiPort()));
  }

  private MockHttpServletResponse apiBasic(
      final String user, final String password, final String clientAddress) throws Exception {
    return this.send(
        get(COUNT_URL)
            .header(AUTHORIZATION, basicAuth(user, password))
            .with(apiPort())
            .with(remoteAddr(clientAddress)));
  }

  private MockHttpServletResponse protocolBasic(
      final Repo repo, final String user, final String password) throws Exception {
    return this.send(
        get(PRIVATE_READ, repo.getName())
            .header(AUTHORIZATION, basicAuth(user, password))
            .with(protocolPort()));
  }

  private MockHttpServletResponse panelLogin(final String user, final String password)
      throws Exception {
    return this.send(
        post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password))
            .with(apiPort()));
  }

  /** Fails {@code times} password checks with a username that exists. */
  private void failApi(final int times) throws Exception {
    for (var i = 0; i < times; i++) {
      assertThat(this.apiBasic(this.username, "wrong-" + i).getStatus()).isEqualTo(401);
    }
  }

  private static String body(final MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  /** The body without its {@code errorCode}, which is a fresh id for every error. */
  private static String bodyWithoutErrorCode(final MockHttpServletResponse response)
      throws Exception {
    return body(response).replaceAll("\"errorCode\":\"[^\"]*\"", "");
  }

  /** The panel answer to a client that is over the limit. */
  private static void expectThrottled(final MockHttpServletResponse response) throws Exception {
    final var body = body(response);

    assertThat(response.getStatus()).as(body).isEqualTo(429);
    assertThat(Long.parseLong(response.getHeader(RETRY_AFTER))).isBetween(1L, 60L);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isNull();
    assertThat(JsonPath.<String>read(body, "$.msgId")).isEqualTo("tooManyRequests");
    assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("ERROR");
    assertThat(JsonPath.<String>read(body, "$.text")).isEqualTo(TOO_MANY_TEXT);
  }

  private Repo seedDeployTokenRepo() {
    return this.seedRepo(RepoType.MAVEN, uniqueRepoName("thr"), true, null);
  }

  private Repo seedNpmRepo() {
    return this.seedRepo(RepoType.NPM, uniqueRepoName("thr"), true, null);
  }

  private MockHttpServletResponse npmBearer(final Repo repo, final String authorization)
      throws Exception {
    return this.send(
        get("/{repo}/some-package", repo.getName())
            .header(AUTHORIZATION, authorization)
            .with(protocolPort()));
  }

  private record DeployCredential(UUID id, String username, String secret) {}

  private DeployCredential seedDeployToken(final Repo repo) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(false);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return new DeployCredential(entity.getId(), entity.getUsername(), secret);
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("is on by default, with the documented limits")
  void defaults() {
    assertThat(this.properties)
        .isEqualTo(new AuthThrottleProperties(true, MAX_FAILURES, 60, 10_000));
  }

  @Test
  @DisplayName("refuses the request after the limit with 429, Retry-After and no challenge")
  void blocksAfterTheLimit() throws Exception {
    this.failApi(MAX_FAILURES);

    expectThrottled(this.apiBasic(this.username, "wrong-again"));
  }

  @Test
  @DisplayName("does not run the hash check for a refused request")
  void refusedRequestSkipsTheHashCheck() throws Exception {
    this.failApi(MAX_FAILURES);
    verify(this.cache, times(MAX_FAILURES)).matches(any(), anyString());
    clearInvocations(this.cache);

    expectThrottled(this.apiBasic(this.username, "wrong-again"));
    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));

    verify(this.cache, never()).matches(any(), anyString());
  }

  @Test
  @DisplayName("counts unknown usernames like wrong passwords")
  void unknownUsernamesCount() throws Exception {
    for (var i = 0; i < MAX_FAILURES; i++) {
      assertThat(this.apiBasic(uniqueUsername("nobody"), "wrong").getStatus()).isEqualTo(401);
    }

    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));
  }

  @Test
  @DisplayName("answers a blocked client the same whatever username and password it sends")
  void indistinguishableWhileBlocked() throws Exception {
    this.failApi(MAX_FAILURES);

    final var unknownUser = this.apiBasic(uniqueUsername("nobody"), "wrong");
    final var wrongPassword = this.apiBasic(this.username, "wrong");
    final var rightPassword = this.apiBasic(this.username, VALID_PASSWORD);
    final var emptyPassword = this.apiBasic(this.username, "");

    final var responses = List.of(unknownUser, wrongPassword, rightPassword, emptyPassword);

    for (final var response : responses) {
      expectThrottled(response);
      assertThat(bodyWithoutErrorCode(response)).isEqualTo(bodyWithoutErrorCode(unknownUser));
      assertThat(response.getHeader(RETRY_AFTER)).isEqualTo(unknownUser.getHeader(RETRY_AFTER));
      assertThat(response.getHeaderNames()).isEqualTo(unknownUser.getHeaderNames());
    }
  }

  @Test
  @DisplayName("does not count a request without a username, which costs no hash check")
  void requestWithoutUsernameIsNotCounted() throws Exception {
    for (var i = 0; i < MAX_FAILURES * 2; i++) {
      assertThat(this.apiBasic("", "wrong").getStatus()).isEqualTo(401);
    }
    verify(this.cache, never()).matches(any(), anyString());

    assertThat(this.apiBasic(this.username, "wrong").getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("does not start counting or refusing until the limit is used up")
  void countsOnlyFailures() throws Exception {
    this.failApi(MAX_FAILURES - 1);

    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
    assertThat(this.apiBasic(this.username, "wrong").getStatus()).isEqualTo(401);
    expectThrottled(this.apiBasic(this.username, "wrong"));
  }

  @Test
  @DisplayName("a success does not reset the count")
  void successDoesNotReset() throws Exception {
    this.failApi(MAX_FAILURES - 1);

    // The attacker's own valid account cannot buy more guesses.
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(this.apiBasic(this.username, "wrong").getStatus()).isEqualTo(401);
    expectThrottled(this.apiBasic(this.username, "wrong"));
  }

  @Test
  @DisplayName("keys the count on the client address, not on a header the client sends")
  void forwardedForHeaderIsNotTrusted() throws Exception {
    for (var i = 0; i < MAX_FAILURES; i++) {
      final var response =
          this.send(
              get(COUNT_URL)
                  .header(AUTHORIZATION, basicAuth(this.username, "wrong"))
                  .header("X-Forwarded-For", "203.0.113." + i)
                  .header("X-Real-IP", "203.0.113." + i)
                  .with(apiPort()));
      assertThat(response.getStatus()).isEqualTo(401);
    }

    expectThrottled(this.apiBasic(this.username, "wrong"));
  }

  @Test
  @DisplayName("does not affect another client")
  void otherClientIsNotAffected() throws Exception {
    this.failApi(MAX_FAILURES);
    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));

    assertThat(this.apiBasic(this.username, VALID_PASSWORD, OTHER_CLIENT).getStatus())
        .isEqualTo(200);
    assertThat(this.apiBasic(this.username, "wrong", OTHER_CLIENT).getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("lets the client start again when its window ends")
  void windowExpires() throws Exception {
    this.failApi(MAX_FAILURES);
    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));

    this.advance(Duration.ofSeconds(59));
    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));

    this.advance(Duration.ofSeconds(1));
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
    this.failApi(MAX_FAILURES - 1);
    assertThat(this.apiBasic(this.username, "wrong").getStatus()).isEqualTo(401);
    expectThrottled(this.apiBasic(this.username, "wrong"));
  }

  @Test
  @DisplayName("lets a remembered password through while the client is blocked, without a hash")
  void rememberedPasswordPassesWhileBlocked() throws Exception {
    // The first successful request remembers the password.
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
    this.failApi(MAX_FAILURES);
    expectThrottled(this.apiBasic(this.username, "wrong"));
    clearInvocations(this.cache);

    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);

    verify(this.cache, never()).matches(any(), anyString());
  }

  @Test
  @DisplayName("stops letting even a remembered password through once the client keeps guessing")
  void guessingSaturatesTheThrottle() throws Exception {
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
    this.failApi(MAX_FAILURES);

    // A guess at a remembered password costs a lookup, so refused guesses count as well.
    for (var refused = MAX_FAILURES; refused < MAX_FAILURES * 10; refused++) {
      assertThat(this.apiBasic(this.username, "guess-" + refused).getStatus()).isEqualTo(429);
    }

    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));

    this.advance(Duration.ofSeconds(60));
    assertThat(this.apiBasic(this.username, VALID_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("throttles the protocol routes and leaves deploy tokens alone")
  void protocolRoutes() throws Exception {
    final var repo = this.seedDeployTokenRepo();
    final var deployToken = this.seedDeployToken(repo);

    for (var i = 0; i < MAX_FAILURES; i++) {
      assertThat(this.protocolBasic(repo, this.username, "wrong-" + i).getStatus()).isEqualTo(401);
    }

    final var refused = this.protocolBasic(repo, this.username, "wrong");
    expectThrottled(refused);

    // A deploy token is looked up before the user's password, costs no hash and never fails here.
    assertThat(this.protocolBasic(repo, deployToken.username(), deployToken.secret()).getStatus())
        .isEqualTo(404);
    // A client whose password is right is refused all the same, so it learns nothing.
    expectThrottled(this.protocolBasic(repo, this.username, VALID_PASSWORD));
  }

  @Test
  @DisplayName("does not count deploy-token requests")
  void deployTokensAreNotCounted() throws Exception {
    final var repo = this.seedDeployTokenRepo();
    final var deployToken = this.seedDeployToken(repo);

    for (var i = 0; i < MAX_FAILURES * 2; i++) {
      assertThat(this.protocolBasic(repo, deployToken.username(), deployToken.secret()).getStatus())
          .isEqualTo(404);
    }

    assertThat(this.protocolBasic(repo, this.username, "wrong").getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("answers Docker and Helm clients with the OCI TOOMANYREQUESTS error")
  void ociBody() throws Exception {
    for (var i = 0; i < MAX_FAILURES; i++) {
      final var response =
          this.send(
              get("/v2/token")
                  .header(AUTHORIZATION, basicAuth(this.username, "wrong-" + i))
                  .with(protocolPort()));
      assertThat(response.getStatus()).isEqualTo(401);
    }

    final var response =
        this.send(
            get("/v2/token")
                .header(AUTHORIZATION, basicAuth(this.username, VALID_PASSWORD))
                .with(protocolPort()));
    final var body = body(response);

    assertThat(response.getStatus()).as(body).isEqualTo(429);
    assertThat(Long.parseLong(response.getHeader(RETRY_AFTER))).isBetween(1L, 60L);
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isNull();
    assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys("errors");
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("TOOMANYREQUESTS");
  }

  @Test
  @DisplayName("throttles the panel login before it looks the user up")
  void panelLogin() throws Exception {
    for (var i = 0; i < MAX_FAILURES; i++) {
      final var user = i % 2 == 0 ? this.username : uniqueUsername("nobody");
      final var response = this.panelLogin(user, "Wrong1" + i);
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(JsonPath.<String>read(body(response), "$.msgId")).isEqualTo("invalidCredentials");
    }
    clearInvocations(this.spiedUsers);

    final var unknownUser = this.panelLogin(uniqueUsername("nobody"), "Wrong12");
    final var wrongPassword = this.panelLogin(this.username, "Wrong12");
    final var rightPassword = this.panelLogin(this.username, VALID_PASSWORD);

    for (final var response : List.of(unknownUser, wrongPassword, rightPassword)) {
      expectThrottled(response);
      assertThat(bodyWithoutErrorCode(response)).isEqualTo(bodyWithoutErrorCode(unknownUser));
    }
    verify(this.spiedUsers, never()).getUserByUsername(anyString());
  }

  @Test
  @DisplayName("shares one count between the panel login and the Basic checks")
  void panelAndBasicShareTheCount() throws Exception {
    for (var i = 0; i < MAX_FAILURES / 2; i++) {
      assertThat(this.panelLogin(this.username, "Wrong12").getStatus()).isEqualTo(401);
      assertThat(this.apiBasic(this.username, "wrong").getStatus()).isEqualTo(401);
    }

    expectThrottled(this.panelLogin(this.username, VALID_PASSWORD));
    expectThrottled(this.apiBasic(this.username, VALID_PASSWORD));
  }

  @Test
  @DisplayName("answers 401 as before to a client under the limit")
  void underTheLimitNothingChanges() throws Exception {
    final var response = this.panelLogin(this.username, "Wrong12");

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(RETRY_AFTER)).isNull();
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo("Bearer");
  }

  /**
   * RPS-1209: npm sends every deploy token as a Bearer {@code _authToken}, so a revoked or rotated
   * one is a wrong credential like a wrong Basic password: {@code unAuthorized}, counted, and 429
   * once the client is over the limit.
   */
  @Test
  @DisplayName("counts an npm Bearer token that is no deploy token like a wrong password")
  void npmBearerTokenThatIsNoDeployTokenIsCounted() throws Exception {
    final var repo = this.seedNpmRepo();
    final var revoked = this.seedDeployToken(repo);
    this.deployTokenRepository.deleteById(revoked.id());
    this.entityManager.flush();

    for (var i = 0; i < MAX_FAILURES; i++) {
      final var response = this.npmBearer(repo, "Bearer " + revoked.secret());

      assertThat(response.getStatus()).as(body(response)).isEqualTo(401);
      assertThat(JsonPath.<String>read(body(response), "$.msgId")).isEqualTo("unAuthorized");
      assertThat(response.getHeader(WWW_AUTHENTICATE)).isEqualTo(NPM_BEARER_CHALLENGE);
    }

    final var refused = this.npmBearer(repo, "Bearer " + revoked.secret());
    expectThrottled(refused);
    // The count is the client's, so a wrong Basic password on any route is refused as well.
    expectThrottled(this.protocolBasic(this.seedDeployTokenRepo(), this.username, "wrong"));
  }

  @Test
  @DisplayName("counts a Bearer value that is neither a deploy token nor a JWT on any route")
  void garbageBearerIsCounted() throws Exception {
    final var repo = this.seedNpmRepo();

    for (var i = 0; i < MAX_FAILURES; i++) {
      final var response = this.npmBearer(repo, "Bearer not.a.token." + i);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(JsonPath.<String>read(body(response), "$.msgId")).isEqualTo("unAuthorized");
    }

    expectThrottled(this.npmBearer(repo, "Bearer not.a.token"));
  }

  @Test
  @DisplayName("lets a valid Bearer deploy token and a valid protocol JWT through while blocked")
  void validBearerCredentialsPassWhileBlocked() throws Exception {
    final var repo = this.seedNpmRepo();
    final var deployToken = this.seedDeployToken(repo);
    final var jwt = this.adminProtocolBearerToken();

    for (var i = 0; i < MAX_FAILURES; i++) {
      assertThat(this.npmBearer(repo, "Bearer not.a.token." + i).getStatus()).isEqualTo(401);
    }
    expectThrottled(this.npmBearer(repo, "Bearer not.a.token"));

    // Neither is a 401 or a 429: the package does not exist, which is what an authorized read of
    // an empty repo answers.
    assertThat(this.npmBearer(repo, "Bearer " + deployToken.secret()).getStatus()).isEqualTo(404);
    assertThat(this.npmBearer(repo, jwt).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("does not count a deploy-token JWT whose token was revoked")
  void revokedDeployTokenJwtIsNotCounted() throws Exception {
    final var repo = this.seedNpmRepo();
    final var deployToken = this.seedDeployToken(repo);
    final var jwt =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createProtocolToken(
                deployToken.id(),
                deployToken.username(),
                Duration.ofMinutes(30),
                AuthenticationType.DEPLOY_TOKEN);
    this.deployTokenRepository.deleteById(deployToken.id());
    this.entityManager.flush();

    for (var i = 0; i < MAX_FAILURES * 2; i++) {
      final var response = this.npmBearer(repo, jwt);

      assertThat(response.getStatus()).as(body(response)).isEqualTo(401);
      assertThat(JsonPath.<String>read(body(response), "$.msgId")).isEqualTo("unAuthorized");
    }
  }

  @Test
  @DisplayName(
      "does not count an expired but validly signed protocol JWT, which keeps sessionExpired")
  void expiredProtocolJwtIsNotCounted() throws Exception {
    final var repo = this.seedNpmRepo();
    final var user = this.createUser(uniqueUsername("expired"), UserRole.USER);
    final var expired =
        AuthUtils.AUTH_BEARER
            + this.jwtUtils.createProtocolToken(
                user.getId(), user.getUsername(), Duration.ofSeconds(-60));

    for (var i = 0; i < MAX_FAILURES * 2; i++) {
      final var response = this.npmBearer(repo, expired);

      assertThat(response.getStatus()).as(body(response)).isEqualTo(401);
      assertThat(JsonPath.<String>read(body(response), "$.msgId")).isEqualTo("sessionExpired");
    }

    // The count is untouched: the first wrong credential is a 401, not a 429.
    final var wrong = this.npmBearer(repo, "Bearer not.a.token");
    assertThat(wrong.getStatus()).isEqualTo(401);
    assertThat(JsonPath.<String>read(body(wrong), "$.msgId")).isEqualTo("unAuthorized");
  }
}
