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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full-stack integration tests for {@code /api/repos/*} ({@code ProtocolRepoController}), running
 * against the real Spring context, MVC dispatch, the {@code ProtocolAuthInterceptor}, a
 * containerized PostgreSQL database and a temporary storage directory. See {@link
 * AbstractIntegrationTest} for the shared setup.
 *
 * <p>Repo types appear in URLs in <strong>upper case</strong> ({@code /api/repos/MAVEN/info}),
 * exactly as the OpenAPI {@code RepoType} enum and the frontend spell them; other spellings are
 * pinned in {@link RepoTypePathVariable}.
 *
 * <p>Notes on what is deliberately pinned rather than "fixed", because these are characterization
 * tests of today's behavior:
 *
 * <ul>
 *   <li>authorization is only {@code READ}/{@code WRITE} for any authenticated user and {@code
 *       MANAGE} for {@code ADMIN}; there is no repo owner concept;
 *   <li>the two {@code {repoType}}-only routes ({@code /info}, {@code /count}) and {@code POST}
 *       create carry no repo name, so the interceptor only authenticates the caller for them and
 *       {@code count}'s {@code MANAGE} annotation is not enforced;
 *   <li>a request without an {@code Authorization} header on those routes ends in a 500;
 * </ul>
 *
 * <p>{@code UsageUpdateService.updateUsage} is {@code @Async}, so it runs on another thread and can
 * never see rows that only exist inside a test-managed, rolled-back transaction. It is therefore
 * replaced by a mock, and the tests assert that no endpoint here calls it: a deleted repo's usage
 * goes with its row. {@code ProtocolRepoDeleteUsageIT} covers the delete with the real service.
 */
@DisplayName("ProtocolRepoController /api/repos/*")
class ProtocolRepoControllerIT extends AbstractIntegrationTest {

  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String UNSUPPORTED_MEDIA_TYPE_TEXT = "Unsupported media type.";
  private static final String REPO_NOT_FOUND_TEXT = "Repository not found";
  private static final String REPO_EXISTS_TEXT = "The repository exists. Please try another name.";
  private static final String USER_NOT_FOUND_TEXT = "User not found.";
  private static final String UNAUTHORIZED_TEXT = "The user has logged in but has no permissions.";
  private static final String ACCESS_NOT_ALLOWED_TEXT = "Access isn't allowed.";
  private static final String SESSION_EXPIRED_TEXT = "Session expired.";
  private static final String ITEM_NOT_FOUND_TEXT = "The requested item is not found.";
  private static final String ERROR_OCCURRED_TEXT = "An error occurred.";

  private static final String[] REPO_LIST_KEYS = {
    "name", "type", "privateRepo", "diskUsage", "createdAt"
  };
  private static final String[] SETTINGS_KEYS = {
    "privateRepo", "releases", "snapshots", "allowOverride", "searchable", "securityScanEnabled"
  };
  private static final String[] STORAGE_ITEM_KEYS = {
    "name", "createdAt", "size", "directory", "path"
  };

  @MockitoBean private UsageUpdateService usageUpdateService;

  // ---------------------------------------------------------------------------------------------
  // Request / fixture helpers
  // ---------------------------------------------------------------------------------------------

  private static MockHttpServletRequestBuilder json(
      final MockHttpServletRequestBuilder request, final String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String createBody(final String name) {
    return "{\"name\":\"%s\"}".formatted(name);
  }

  private static String nameBody(final String name) {
    return "{\"name\":\"%s\"}".formatted(name);
  }

  private static String descriptionBody(final String description) {
    return "{\"description\":\"%s\"}".formatted(description);
  }

  private static String settingsBody(
      final boolean privateRepo,
      final boolean allowOverride,
      final boolean releases,
      final boolean snapshots,
      final boolean securityScanEnabled) {
    return ("{\"privateRepo\":%s,\"allowOverride\":%s,\"releases\":%s,\"snapshots\":%s,"
            + "\"securityScanEnabled\":%s}")
        .formatted(privateRepo, allowOverride, releases, snapshots, securityScanEnabled);
  }

  private static String repoUrl(final Repo repo, final String suffix) {
    return "/api/repos/" + repo.getName() + suffix;
  }

  private Repo seedMaven() {
    return this.seedRepo(RepoType.MAVEN, uniqueRepoName("mvn"));
  }

  private static void writeFile(final Repo repo, final String relativePath, final String content)
      throws IOException {
    final var file = storageDirOf(repo).resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static long directoryCount(final RepoType type) throws IOException {
    try (var children = Files.list(STORAGE_ROOT.resolve(protocolDir(type)))) {
      return children.count();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Response helpers
  // ---------------------------------------------------------------------------------------------

  private static Map<String, Object> dataObject(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static List<Map<String, Object>> dataList(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static Instant instantOrNull(final Object json) {
    return json == null ? null : Instant.parse((String) json);
  }

  private static void expectValidationError(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.BAD_REQUEST, "validationError", null, VALIDATION_TEXT);
  }

  private static void expectRepoNotFound(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.NOT_FOUND, "repoNotFound", "repoNotFound", REPO_NOT_FOUND_TEXT);
  }

  private static void expectRepoExists(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.CONFLICT, "repoExists", "repoExists", REPO_EXISTS_TEXT);
  }

  private static void expectUnauthorized(final ResultActions result) throws Exception {
    expectError(result, HttpStatus.UNAUTHORIZED, "unAuthorized", "unAuthorized", UNAUTHORIZED_TEXT);
  }

  /** Asserts the exact {@code RepoPermissionInfo} shape; {@code description} is omitted if null. */
  private static void assertPermissions(
      final Map<String, Object> node,
      final String repoName,
      final String description,
      final boolean canRead,
      final boolean canWrite,
      final boolean canManage,
      final boolean privateRepo) {

    final var expectedKeys =
        description == null
            ? new String[] {"repoName", "canRead", "canWrite", "canManage", "private"}
            : new String[] {
              "repoName", "description", "canRead", "canWrite", "canManage", "private"
            };

    assertThat(node)
        .containsOnlyKeys(expectedKeys)
        .containsEntry("repoName", repoName)
        .containsEntry("canRead", canRead)
        .containsEntry("canWrite", canWrite)
        .containsEntry("canManage", canManage)
        .containsEntry("private", privateRepo);

    if (description != null) {
      assertThat(node).containsEntry("description", description);
    }
  }

  /** Asserts a freshly created repo row: everything at its creation default. */
  private static void assertCreatedRow(
      final Repo repo,
      final String name,
      final RepoType type,
      final boolean privateRepo,
      final String description) {

    assertThat(repo.getId()).isNotNull();
    assertThat(repo.getName()).isEqualTo(name);
    assertThat(repo.getType()).isEqualTo(type);
    assertThat(repo.isPrivateRepo()).isEqualTo(privateRepo);
    assertThat(repo.getDescription()).isEqualTo(description);
    assertThat(repo.isAllowOverride()).isTrue();
    assertThat(repo.getSnapshots()).isTrue();
    assertThat(repo.getReleases()).isTrue();
    assertThat(repo.isSearchable()).isFalse();
    assertThat(repo.isSecurityScanEnabled()).isTrue();
    assertThat(repo.getDiskUsage()).isZero();
    assertThat(repo.getCreatedAt()).isNotNull();
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization -- identical rules, so parameterized over every endpoint
  // ---------------------------------------------------------------------------------------------

  /** How the interceptor treats a route, which decides what an anonymous caller gets. */
  private enum Kind {
    /** Route with a repo name and {@code READ}: public repos are readable without credentials. */
    REPO_READ,
    /** Route with a repo name and {@code MANAGE}: only an ADMIN gets in. */
    REPO_MANAGE,
    /** Route with only a repo type: the interceptor merely authenticates the caller. */
    TYPE_ONLY
  }

  /** What a request targets: an existing repo and the {@code {repoType}} spelling to use. */
  private record Target(String repoName, String repoType) {}

  /** One row per {@code ProtocolRepoController} handler. */
  private record Endpoint(
      String name, Kind kind, Function<Target, MockHttpServletRequestBuilder> request) {
    @Override
    public String toString() {
      return this.name;
    }
  }

  private static List<Endpoint> allEndpoints() {
    return List.of(
        new Endpoint(
            "POST /api/repos/{repoType}",
            Kind.TYPE_ONLY,
            t -> json(post("/api/repos/" + t.repoType()), createBody("probe-" + randomTag()))),
        new Endpoint(
            "DELETE /api/repos/{repoName}",
            Kind.REPO_MANAGE,
            t -> delete("/api/repos/" + t.repoName())),
        new Endpoint(
            "GET /api/repos/{repoName}/permissions",
            Kind.REPO_READ,
            t -> get("/api/repos/" + t.repoName() + "/permissions")),
        new Endpoint(
            "GET /api/repos/{repoName}/contents",
            Kind.REPO_READ,
            t -> get("/api/repos/" + t.repoName() + "/contents").param("path", "/")),
        new Endpoint(
            "GET /api/repos/{repoName}/settings",
            Kind.REPO_MANAGE,
            t -> get("/api/repos/" + t.repoName() + "/settings")),
        new Endpoint(
            "GET /api/repos/{repoName}/usage",
            Kind.REPO_MANAGE,
            t -> get("/api/repos/" + t.repoName() + "/usage")),
        new Endpoint(
            "GET /api/repos/{repoType}/info",
            Kind.TYPE_ONLY,
            t -> get("/api/repos/" + t.repoType() + "/info")),
        new Endpoint(
            "GET /api/repos/{repoType}/count",
            Kind.TYPE_ONLY,
            t -> get("/api/repos/" + t.repoType() + "/count")),
        new Endpoint(
            "PATCH /api/repos/{repoName}/name",
            Kind.REPO_MANAGE,
            t ->
                json(
                    patch("/api/repos/" + t.repoName() + "/name"),
                    nameBody("probe-" + randomTag()))),
        new Endpoint(
            "PATCH /api/repos/{repoName}/description",
            Kind.REPO_MANAGE,
            t -> json(patch("/api/repos/" + t.repoName() + "/description"), descriptionBody("x"))),
        new Endpoint(
            "PUT /api/repos/{repoName}/settings",
            Kind.REPO_MANAGE,
            t -> json(put("/api/repos/" + t.repoName() + "/settings"), "{}")),
        new Endpoint(
            "GET /api/repos/{repoName}/format",
            Kind.REPO_READ,
            t -> get("/api/repos/" + t.repoName() + "/format")));
  }

  private static Stream<Endpoint> endpoints() {
    return allEndpoints().stream();
  }

  private static Stream<Endpoint> endpointsOfKind(final Kind... kinds) {
    final var wanted = List.of(kinds);
    return allEndpoints().stream().filter(e -> wanted.contains(e.kind()));
  }

  private static Stream<Endpoint> repoScopedEndpoints() {
    return endpointsOfKind(Kind.REPO_READ, Kind.REPO_MANAGE);
  }

  private static Stream<Endpoint> repoReadEndpoints() {
    return endpointsOfKind(Kind.REPO_READ);
  }

  private static Stream<Endpoint> repoManageEndpoints() {
    return endpointsOfKind(Kind.REPO_MANAGE);
  }

  private static Stream<Endpoint> typeOnlyEndpoints() {
    return endpointsOfKind(Kind.TYPE_ONLY);
  }

  @Nested
  @DisplayName("authentication & authorization")
  class Security {

    static Stream<Endpoint> endpoints() {
      return ProtocolRepoControllerIT.endpoints();
    }

    static Stream<Endpoint> repoScopedEndpoints() {
      return ProtocolRepoControllerIT.repoScopedEndpoints();
    }

    static Stream<Endpoint> repoReadEndpoints() {
      return ProtocolRepoControllerIT.repoReadEndpoints();
    }

    static Stream<Endpoint> repoManageEndpoints() {
      return ProtocolRepoControllerIT.repoManageEndpoints();
    }

    static Stream<Endpoint> typeOnlyEndpoints() {
      return ProtocolRepoControllerIT.typeOnlyEndpoints();
    }

    private Target target() {
      return new Target(ProtocolRepoControllerIT.this.seedMaven().getName(), "MAVEN");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 unAuthorized for HTTP Basic credentials naming an unknown user")
    void nonBearerAuthorizationHeader(final Endpoint endpoint) throws Exception {
      final var target = this.target();

      // RPS-906: must match basicCredentialsWrongPassword, so usernames cannot be enumerated.
      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              endpoint.request().apply(target).header(AUTHORIZATION, "Basic dXNlcjpw")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 for a malformed/garbage bearer token")
    void malformedBearerToken(final Endpoint endpoint) throws Exception {
      final var target = this.target();

      expectError(
          ProtocolRepoControllerIT.this.perform(
              endpoint.request().apply(target).header(AUTHORIZATION, "Bearer not-a-jwt")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          ACCESS_NOT_ALLOWED_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 401 sessionExpired for an expired token")
    void expiredToken(final Endpoint endpoint) throws Exception {
      final var target = this.target();
      final var admin =
          ProtocolRepoControllerIT.this.createUser(uniqueUsername("expired"), UserRole.ADMIN);

      expectError(
          ProtocolRepoControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(target)
                  .header(
                      AUTHORIZATION, ProtocolRepoControllerIT.this.expiredBearerTokenFor(admin))),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          SESSION_EXPIRED_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 404 userNotFound when the token's user no longer exists")
    void tokenUserNoLongerExists(final Endpoint endpoint) throws Exception {
      final var target = this.target();
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          ProtocolRepoControllerIT.this.bearerTokenFor(UUID.randomUUID(), uniqueUsername("ghost"));

      expectError(
          ProtocolRepoControllerIT.this.perform(
              endpoint.request().apply(target).header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "userNotFound",
          USER_NOT_FOUND_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoScopedEndpoints")
    @DisplayName("returns 404 repoNotFound for an unknown repo once the caller is authorized")
    void unknownRepo(final Endpoint endpoint) throws Exception {
      final var target = new Target("nope-" + randomTag(), "MAVEN");

      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(target)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoScopedEndpoints")
    @DisplayName("returns 401 unAuthorized, not 404, for an unknown repo without credentials")
    void unknownRepoWithoutHeader(final Endpoint endpoint) throws Exception {
      // Checked after authentication, so a missing repo looks like a private one (RPS-887).
      final var target = new Target("nope-" + randomTag(), "MAVEN");

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(endpoint.request().apply(target)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoManageEndpoints")
    @DisplayName("returns 401 unAuthorized without credentials on a MANAGE route, even if public")
    void manageRouteWithoutHeader(final Endpoint endpoint) throws Exception {
      final var target = this.target();

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(endpoint.request().apply(target)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoReadEndpoints")
    @DisplayName("serves a READ route anonymously when the repo is public")
    void readRouteWithoutHeaderOnPublicRepo(final Endpoint endpoint) throws Exception {
      final var target = this.target();

      ProtocolRepoControllerIT.this
          .perform(endpoint.request().apply(target))
          .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoReadEndpoints")
    @DisplayName("returns 401 unAuthorized for a READ route without credentials on a private repo")
    void readRouteWithoutHeaderOnPrivateRepo(final Endpoint endpoint) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("priv"), true, null);
      final var target = new Target(repo.getName(), "MAVEN");

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(endpoint.request().apply(target)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeOnlyEndpoints")
    @DisplayName("returns 401 unAuthorized for a missing Authorization header on a repoType route")
    void typeOnlyRouteWithoutHeader(final Endpoint endpoint) throws Exception {
      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              endpoint.request().apply(new Target("unused", "MAVEN"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoManageEndpoints")
    @DisplayName(
        "returns 401 unAuthorized for a non-admin caller on a MANAGE route, changing nothing")
    void manageRouteAsPlainUser(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      final var target = new Target(repo.getName(), "MAVEN");

      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              endpoint
                  .request()
                  .apply(target)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
      assertThat(storageDirOf(repo)).isDirectory();
      verifyNoInteractions(ProtocolRepoControllerIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("accepts HTTP Basic credentials of an existing user")
    void basicCredentials() throws Exception {
      final var username = uniqueUsername("basic");
      ProtocolRepoControllerIT.this.createUser(username, UserRole.USER);

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get("/api/repos/MAVEN/count")
                  .header(AUTHORIZATION, basicAuth(username, VALID_PASSWORD))),
          "repoCountFetched",
          "Repo count fetched.");
    }

    @Test
    @DisplayName("returns 401 unAuthorized for HTTP Basic credentials with a wrong password")
    void basicCredentialsWrongPassword() throws Exception {
      final var username = uniqueUsername("basic");
      ProtocolRepoControllerIT.this.createUser(username, UserRole.USER);

      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              get("/api/repos/MAVEN/count").header(AUTHORIZATION, basicAuth(username, "wrong"))));
    }

    @Test
    @DisplayName("answers an unknown username exactly like a wrong password, including for admin")
    void basicCredentialsDoNotRevealUsernames() throws Exception {
      final var username = uniqueUsername("basic");
      ProtocolRepoControllerIT.this.createUser(username, UserRole.USER);

      final var wrongPassword = this.basicError(basicAuth(username, "wrong"));
      final var seededAdmin = this.basicError(basicAuth("admin", "wrong"));
      final var unknownUser = this.basicError(basicAuth(uniqueUsername("ghost"), "wrong"));
      final var emptyUsername = this.basicError(basicAuth("", "wrong"));

      assertThat(List.of(seededAdmin, unknownUser, emptyUsername))
          .allSatisfy(response -> assertThat(response).isEqualTo(wrongPassword));
    }

    /** The status and error envelope of a Basic-authenticated call, minus the random errorCode. */
    private Map<String, Object> basicError(final String authHeader) throws Exception {
      final var response =
          ProtocolRepoControllerIT.this
              .perform(get("/api/repos/MAVEN/count").header(AUTHORIZATION, authHeader))
              .andExpect(status().isUnauthorized())
              .andReturn()
              .getResponse();
      final Map<String, Object> envelope =
          new HashMap<>(JsonPath.read(response.getContentAsString(), "$"));
      envelope.remove("errorCode");
      envelope.put("status", response.getStatus());
      return envelope;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // {repoType} path variable
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("{repoType} path variable")
  class RepoTypePathVariable {

    private MockHttpServletRequestBuilder request(final String route, final String type) {
      return switch (route) {
        case "post" -> json(post("/api/repos/" + type), createBody("probe-" + randomTag()));
        case "info" -> get("/api/repos/" + type + "/info");
        default -> get("/api/repos/" + type + "/count");
      };
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("typeCases")
    @DisplayName("rejects a value that is not exactly a RepoType constant")
    void invalidRepoType(
        final String label,
        final String route,
        final String type,
        final HttpStatus status,
        final String msgId,
        final String data,
        final String text)
        throws Exception {

      expectError(
          ProtocolRepoControllerIT.this.perform(
              this.request(route, type)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          status,
          msgId,
          data,
          text);
    }

    static Stream<Arguments> typeCases() {
      final var routes = List.of("post", "info", "count");
      final var result = new ArrayList<Arguments>();

      for (final var route : routes) {
        // The interceptor matches case-insensitively but does not know "go"; it only knows GOLANG.
        result.add(
            Arguments.of(
                "unknown type",
                route,
                "bogus",
                HttpStatus.NOT_FOUND,
                "repoTypeNotFound",
                "repoTypeNotFound",
                "Repository type not found."));
        result.add(
            Arguments.of(
                "short go alias",
                route,
                "GO",
                HttpStatus.NOT_FOUND,
                "repoTypeNotFound",
                "repoTypeNotFound",
                "Repository type not found."));
        // The interceptor accepts these, but the enum conversion of @PathVariable is case
        // sensitive, so they fail there instead.
        result.add(
            Arguments.of(
                "lower case",
                route,
                "maven",
                HttpStatus.BAD_REQUEST,
                "validationError",
                "repoType",
                VALIDATION_TEXT));
        result.add(
            Arguments.of(
                "mixed case",
                route,
                "Maven",
                HttpStatus.BAD_REQUEST,
                "validationError",
                "repoType",
                VALIDATION_TEXT));
        result.add(
            Arguments.of(
                "lower case golang",
                route,
                "golang",
                HttpStatus.BAD_REQUEST,
                "validationError",
                "repoType",
                VALIDATION_TEXT));
      }

      return result.stream();
    }

    @Test
    @DisplayName("creates nothing for a rejected type")
    void rejectedTypeCreatesNothing() throws Exception {
      final var name = uniqueRepoName("badtype");

      expectError(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/maven"), createBody(name))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "repoType",
          VALIDATION_TEXT);

      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(name)).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // POST /api/repos/{repoType}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("POST /api/repos/{repoType}")
  class Create {

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("creates the repo row and its storage directory for every RepoType")
    void createsRepoForEveryType(final RepoType type) throws Exception {
      final var name = uniqueRepoName(type.name().toLowerCase(Locale.ROOT));

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  json(post("/api/repos/" + type.name()), createBody(name))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "repoCreated",
              "Repo created.");

      assertThat(JsonPath.<Object>read(body, "$.data")).isNull();
      final var repo = ProtocolRepoControllerIT.this.reloadRepo(name);
      assertCreatedRow(repo, name, type, false, null);
      assertThat(storageDirOf(repo)).isDirectory();
    }

    @Test
    @DisplayName("defaults private to false and stores the description")
    void defaultsAndDescription() throws Exception {
      final var name = uniqueRepoName("desc");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(
                      post("/api/repos/NPM"),
                      "{\"name\":\"%s\",\"description\":\"my npm repo\"}".formatted(name))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");

      assertCreatedRow(
          ProtocolRepoControllerIT.this.reloadRepo(name), name, RepoType.NPM, false, "my npm repo");
    }

    @ParameterizedTest(name = "privateRepo={0}")
    @ValueSource(strings = {"true", "false", "null"})
    @DisplayName("honors an explicit private flag, and treats null as false")
    void explicitPrivateFlag(final String flag) throws Exception {
      final var name = uniqueRepoName("flag");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(
                      post("/api/repos/DOCKER"),
                      "{\"name\":\"%s\",\"privateRepo\":%s}".formatted(name, flag))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");

      assertCreatedRow(
          ProtocolRepoControllerIT.this.reloadRepo(name),
          name,
          RepoType.DOCKER,
          Boolean.parseBoolean(flag),
          null);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"a", "Repo_Name-1", "UPPER", "under_score", "hy-phen", "1234567890"})
    @DisplayName("accepts every character the name pattern allows")
    void acceptsAllowedNames(final String name) throws Exception {
      final var unique = name + "-" + randomTag();

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), createBody(unique))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(unique).getName()).isEqualTo(unique);
    }

    @Test
    @DisplayName("accepts a name of exactly 25 characters")
    void acceptsMaxLengthName() throws Exception {
      final var name = "a".repeat(24) + randomTag().charAt(0);

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), createBody(name))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(name).getName()).hasSize(25);
    }

    @Test
    @DisplayName(
        "lets a plain USER create a repo: create only authenticates, it never checks MANAGE")
    void plainUserCanCreate() throws Exception {
      final var name = uniqueRepoName("byuser");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), createBody(name))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
          "repoCreated",
          "Repo created.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(name).getName()).isEqualTo(name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError for an invalid body and creates nothing")
    void invalidBody(final String label, final String body) throws Exception {
      final var rowsBefore = ProtocolRepoControllerIT.this.repoRepository.count();
      final var dirsBefore = directoryCount(RepoType.MAVEN);

      expectValidationError(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.repoRepository.count()).isEqualTo(rowsBefore);
      assertThat(directoryCount(RepoType.MAVEN)).isEqualTo(dirsBefore);
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("empty object", "{}"),
          Arguments.of("null name", "{\"name\":null}"),
          Arguments.of("blank name", "{\"name\":\"\"}"),
          Arguments.of("26 characters", "{\"name\":\"" + "a".repeat(26) + "\"}"),
          Arguments.of("space", "{\"name\":\"has space\"}"),
          Arguments.of("slash", "{\"name\":\"a/b\"}"),
          Arguments.of("dot", "{\"name\":\"dot.name\"}"),
          Arguments.of("non-ascii letter", "{\"name\":\"café\"}"),
          Arguments.of("path traversal", "{\"name\":\"..\"}"),
          Arguments.of(
              "privateRepo of the wrong type", "{\"name\":\"ok\",\"privateRepo\":\"maybe\"}"),
          Arguments.of("malformed JSON", "{not json"),
          Arguments.of("empty body", ""),
          Arguments.of("array instead of object", "[]"));
    }

    @Test
    @DisplayName("returns 415 unsupportedMediaType when the body has no JSON content type")
    void unsupportedMediaType() throws Exception {
      expectError(
          ProtocolRepoControllerIT.this.perform(
              post("/api/repos/MAVEN")
                  .content("{\"name\":\"" + uniqueRepoName("nomedia") + "\"}")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "unsupportedMediaType",
          null,
          UNSUPPORTED_MEDIA_TYPE_TEXT);
    }

    @Test
    @DisplayName("returns 409 repoExists for a duplicate name of the same type, creating nothing")
    void duplicateSameType() throws Exception {
      final var existing = ProtocolRepoControllerIT.this.seedMaven();
      final var dirsBefore = directoryCount(RepoType.MAVEN);

      expectRepoExists(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), createBody(existing.getName()))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(directoryCount(RepoType.MAVEN)).isEqualTo(dirsBefore);
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(existing.getName()).getId())
          .isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("returns 409 repoExists for a name used by a repo of another type")
    void duplicateAcrossTypes() throws Exception {
      final var existing = ProtocolRepoControllerIT.this.seedMaven();
      final var dirsBefore = directoryCount(RepoType.NPM);

      expectRepoExists(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/NPM"), createBody(existing.getName()))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(directoryCount(RepoType.NPM)).isEqualTo(dirsBefore);
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(existing.getName()).getType())
          .isEqualTo(RepoType.MAVEN);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("returns 409 repoExists for the default repo names seeded at startup")
    void startupDefaultNamesAreTaken(final RepoType type) throws Exception {
      final var defaultName = type == RepoType.GOLANG ? "go" : type.name().toLowerCase(Locale.ROOT);
      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(defaultName))
          .as("startup-seeded default repo '%s'", defaultName)
          .isPresent();

      // Use a different type than the default's, so only the name can clash.
      final var otherType = type == RepoType.MAVEN ? "NPM" : "MAVEN";

      expectRepoExists(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/" + otherType), createBody(defaultName))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));
    }

    @Test
    @DisplayName("KNOWN DEFECT: a 500-character description is fine but 501 is not validated")
    void descriptionOverColumnLength() throws Exception {
      final var ok = uniqueRepoName("d500");
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(
                      post("/api/repos/NPM"),
                      "{\"name\":\"%s\",\"description\":\"%s\"}".formatted(ok, "d".repeat(500)))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(ok).getDescription()).hasSize(500);

      // RepoCreateForm.description has no maxLength (RepoDescriptionForm does), so the form passes
      // validation and the INSERT is what fails. Hibernate defers it until flush; in production
      // RepoTxService.createRepo commits on return, so the caller gets a 500 errorOccurred. Here
      // the test-managed transaction hides that, so pin the failing flush instead.
      final var tooLong = uniqueRepoName("d501");
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(
                      post("/api/repos/NPM"),
                      "{\"name\":\"%s\",\"description\":\"%s\"}"
                          .formatted(tooLong, "d".repeat(501)))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoCreated",
          "Repo created.");

      assertThatThrownBy(() -> ProtocolRepoControllerIT.this.entityManager.flush())
          .isInstanceOf(PersistenceException.class)
          .hasStackTraceContaining("value too long for type character varying(500)");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // DELETE /api/repos/{repoName}
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("DELETE /api/repos/{repoName}")
  class Delete {

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("removes the row and the storage directory of an empty repo, for every RepoType")
    void deletesEmptyRepo(final RepoType type) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              type, uniqueRepoName(type.name().toLowerCase(Locale.ROOT)));
      assertThat(storageDirOf(repo)).isDirectory();

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  delete(repoUrl(repo, ""))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "repoDeleted",
              "Repo deleted.");

      assertThat(JsonPath.<Object>read(body, "$.data")).isNull();
      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(repo.getName())).isEmpty();
      assertThat(ProtocolRepoControllerIT.this.repoRepository.findById(repo.getId())).isEmpty();
      assertThat(storageDirOf(repo)).doesNotExist();

      verifyNoInteractions(ProtocolRepoControllerIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("removes a repo with content without submitting a usage update for its row")
    void deletesRepoWithContent() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      writeFile(repo, "com/acme/lib/1.0/lib-1.0.jar", "jar-bytes");
      writeFile(repo, "com/acme/lib/maven-metadata.xml", "<metadata/>");
      writeFile(repo, "root.txt", "root");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(repo, ""))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoDeleted",
          "Repo deleted.");

      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(repo.getName())).isEmpty();
      assertThat(storageDirOf(repo)).doesNotExist();

      verifyNoInteractions(ProtocolRepoControllerIT.this.usageUpdateService);
    }

    @Test
    @DisplayName("removes a private repo")
    void deletesPrivateRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("priv"), true, "d");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(repo, ""))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoDeleted",
          "Repo deleted.");

      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(repo.getName())).isEmpty();
    }

    @Test
    @DisplayName("leaves other repos untouched")
    void leavesOtherReposAlone() throws Exception {
      final var victim = ProtocolRepoControllerIT.this.seedMaven();
      final var bystander = ProtocolRepoControllerIT.this.seedMaven();

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(victim, ""))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoDeleted",
          "Repo deleted.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(bystander.getName()))
          .isEqualTo(bystander);
      assertThat(storageDirOf(bystander)).isDirectory();
    }

    @Test
    @DisplayName("returns 404 repoNotFound on a second delete")
    void secondDeleteIsNotFound() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(repo, "")).header(AUTHORIZATION, token)),
          "repoDeleted",
          "Repo deleted.");

      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(repo, "")).header(AUTHORIZATION, token)));
    }

    @Test
    @DisplayName("returns 404 repoNotFound for a repo that never existed")
    void unknownRepo() throws Exception {
      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              delete("/api/repos/nope-" + randomTag())
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));
    }

    @Test
    @DisplayName("the name can be reused after a delete")
    void nameIsReusable() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(repo, ""))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoDeleted",
          "Repo deleted.");

      final var recreated = ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, repo.getName());
      assertThat(recreated.getId()).isNotEqualTo(repo.getId());
      assertThat(recreated.getType()).isEqualTo(RepoType.NPM);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoName}/permissions
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoName}/permissions")
  class Permissions {

    @Test
    @DisplayName("gives an ADMIN read, write and manage")
    void adminOnPublicRepo() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), repo.getName(), null, true, true, true, false);
    }

    @Test
    @DisplayName("gives a plain USER read and write but not manage")
    void plainUser() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), repo.getName(), null, true, true, false, false);
    }

    @Test
    @DisplayName(
        "reports the private flag, and a plain USER can still read and write a private repo")
    void privateRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("priv"), true, "secret stuff");

      final var admin =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");
      final var user =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(admin), repo.getName(), "secret stuff", true, true, true, true);
      assertPermissions(dataObject(user), repo.getName(), "secret stuff", true, true, false, true);
    }

    @Test
    @DisplayName("gives an anonymous caller read-only access to a public repo")
    void anonymousOnPublicRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("pub"), false, "open source");

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(get(repoUrl(repo, "/permissions"))),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), repo.getName(), "open source", true, false, false, false);
    }

    @Test
    @DisplayName("returns 401 unAuthorized to an anonymous caller on a private repo")
    void anonymousOnPrivateRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("priv"), true, null);

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(get(repoUrl(repo, "/permissions"))));
    }

    @Test
    @DisplayName("gives the creator no owner privileges: there is no repo owner")
    void creatorIsNotAnOwner() throws Exception {
      final var creator =
          ProtocolRepoControllerIT.this.createUser(uniqueUsername("creator"), UserRole.USER);
      final var token = ProtocolRepoControllerIT.this.bearerTokenFor(creator);
      final var name = uniqueRepoName("mine");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos/MAVEN"), createBody(name)).header(AUTHORIZATION, token)),
          "repoCreated",
          "Repo created.");

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get("/api/repos/" + name + "/permissions").header(AUTHORIZATION, token)),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), name, null, true, true, false, false);
    }

    @Test
    @DisplayName("includes an empty-string description, as opposed to omitting a null one")
    void emptyDescriptionIsKept() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/description")), descriptionBody(""))
                  .header(AUTHORIZATION, token)),
          "repoDescriptionEdited",
          "Repo description updated.");

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions")).header(AUTHORIZATION, token)),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), repo.getName(), "", true, true, true, false);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoName}/contents
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoName}/contents")
  class Contents {

    private Repo repoWithContent() throws IOException {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      writeFile(repo, "com/acme/lib/1.0/lib-1.0.jar", "jar-bytes");
      writeFile(repo, "com/acme/lib/maven-metadata.xml", "<metadata/>");
      writeFile(repo, "root.txt", "root");
      return repo;
    }

    private String contents(final Repo repo, final String path) throws Exception {
      return expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .param("path", path)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
          "itemsFetched",
          "Items fetched.");
    }

    private static void assertItem(
        final Map<String, Object> item,
        final String name,
        final boolean directory,
        final Long size,
        final boolean hasCreatedAt) {

      assertThat(item)
          .containsOnlyKeys(STORAGE_ITEM_KEYS)
          .containsEntry("name", name)
          .containsEntry("directory", directory)
          .containsEntry("path", null);
      assertThat(item.get("size") == null ? null : ((Number) item.get("size")).longValue())
          .isEqualTo(size);
      assertThat(instantOrNull(item.get("createdAt")) != null).isEqualTo(hasCreatedAt);
    }

    @ParameterizedTest(name = "path=\"{0}\"")
    @ValueSource(strings = {"", "/"})
    @DisplayName("lists the root: folders with a trailing slash, then files, with sizes")
    void listsRoot(final String path) throws Exception {
      final var repo = this.repoWithContent();

      final var items = dataList(this.contents(repo, path));

      assertThat(items).hasSize(2);
      assertItem(items.get(0), "com/", true, null, true);
      assertItem(items.get(1), "root.txt", false, 4L, true);
    }

    @Test
    @DisplayName("lists nested folders with a '../' entry pointing to the parent")
    void listsNestedFolders() throws Exception {
      final var repo = this.repoWithContent();

      final var com = dataList(this.contents(repo, "com"));
      assertThat(com).hasSize(2);
      assertItem(com.get(0), "../", true, null, false);
      assertItem(com.get(1), "acme/", true, null, true);

      final var lib = dataList(this.contents(repo, "/com/acme/lib"));
      assertThat(lib).hasSize(3);
      assertItem(lib.get(0), "../", true, null, false);
      assertItem(lib.get(1), "1.0/", true, null, true);
      assertItem(lib.get(2), "maven-metadata.xml", false, 11L, true);

      final var version = dataList(this.contents(repo, "com/acme/lib/1.0"));
      assertThat(version).hasSize(2);
      assertItem(version.get(0), "../", true, null, false);
      assertItem(version.get(1), "lib-1.0.jar", false, 9L, true);
    }

    @Test
    @DisplayName("lists an empty repo as an empty array")
    void emptyRepo() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      assertThat(dataList(this.contents(repo, "/"))).isEmpty();
    }

    @Test
    @DisplayName("returns 400 validationError naming the parameter when path is missing")
    void missingPath() throws Exception {
      final var repo = this.repoWithContent();

      expectError(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
          HttpStatus.BAD_REQUEST,
          "validationError",
          "path",
          VALIDATION_TEXT);
    }

    @ParameterizedTest(name = "path=\"{0}\"")
    @ValueSource(strings = {"..", "../..", "com/../..", "com/acme/../../.."})
    @DisplayName("returns 400 invalidStoragePath for a path traversal attempt")
    void pathTraversal(final String path) throws Exception {
      final var repo = this.repoWithContent();

      expectError(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .param("path", path)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
          HttpStatus.BAD_REQUEST,
          "invalidStoragePath",
          "invalidStoragePath",
          "Invalid storage path.");
    }

    @ParameterizedTest(name = "path=\"{0}\"")
    @ValueSource(strings = {"nope", "com/nope", "com/acme/lib/1.0/lib-1.0.jar"})
    @DisplayName("returns 404 resourceNotFound for a missing path and for a path to a file")
    void unknownPath(final String path) throws Exception {
      final var repo = this.repoWithContent();

      expectError(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .param("path", path)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
          HttpStatus.NOT_FOUND,
          "resourceNotFound",
          "resourceNotFound",
          "Resource not found.");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = {"MAVEN"})
    @DisplayName("KNOWN DEFECT: a non-Maven repo is a 500, not a clean repoScopeNotMatched error")
    void nonMavenRepo(final RepoType type) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              type, uniqueRepoName(type.name().toLowerCase(Locale.ROOT)));

      // The interceptor throws IllegalArgumentException("repoScopeNotMatched"), which no handler
      // maps, so the generic handler answers errorOccurred and hides the message.
      expectError(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .param("path", "/")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.INTERNAL_SERVER_ERROR,
          "errorOccurred",
          null,
          ERROR_OCCURRED_TEXT);
    }

    @Test
    @DisplayName("lets an anonymous caller browse a public repo")
    void anonymousOnPublicRepo() throws Exception {
      final var repo = this.repoWithContent();

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/contents")).param("path", "/")),
              "itemsFetched",
              "Items fetched.");

      assertThat(dataList(body)).hasSize(2);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET / PUT /api/repos/{repoName}/settings
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("/api/repos/{repoName}/settings")
  class Settings {

    private Map<String, Object> settingsOf(final Repo repo) throws Exception {
      return dataObject(
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/settings"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "settingsFetched",
              "Settings fetched."));
    }

    private void updateSettings(final Repo repo, final String body) throws Exception {
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(put(repoUrl(repo, "/settings")), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "settingsUpdated",
          "Settings updated.");
    }

    @Test
    @DisplayName("returns the creation defaults, including the security scan flag")
    void defaults() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(SETTINGS_KEYS)
          .containsEntry("privateRepo", false)
          .containsEntry("releases", true)
          .containsEntry("snapshots", true)
          .containsEntry("allowOverride", true)
          .containsEntry("searchable", false)
          .containsEntry("securityScanEnabled", true);
    }

    @Test
    @DisplayName("returns the settings of a private repo of any type")
    void privateNonMavenRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.DOCKER, uniqueRepoName("dkr"), true, null);

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(SETTINGS_KEYS)
          .containsEntry("privateRepo", true)
          .containsEntry("securityScanEnabled", true);
    }

    @Test
    @DisplayName("PUT replaces every setting, and GET and the row show the new values")
    void updateAllSettings() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.updateSettings(repo, settingsBody(true, false, false, false, false));

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(SETTINGS_KEYS)
          .containsEntry("privateRepo", true)
          .containsEntry("releases", false)
          .containsEntry("snapshots", false)
          .containsEntry("allowOverride", false)
          .containsEntry("searchable", false)
          .containsEntry("securityScanEnabled", false);
      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.isPrivateRepo()).isTrue();
      assertThat(row.getReleases()).isFalse();
      assertThat(row.getSnapshots()).isFalse();
      assertThat(row.isAllowOverride()).isFalse();
      assertThat(row.isSecurityScanEnabled()).isFalse();
    }

    @ParameterizedTest(name = "toggle {0}")
    @ValueSource(
        strings = {"privateRepo", "allowOverride", "releases", "snapshots", "securityScanEnabled"})
    @DisplayName("persists each setting on its own, leaving the others as they were")
    void eachSettingIndependently(final String field) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      // creation defaults: private=false, override=true, releases=true, snapshots=true, scan=true
      final var body =
          settingsBody(
              field.equals("privateRepo"),
              !field.equals("allowOverride"),
              !field.equals("releases"),
              !field.equals("snapshots"),
              !field.equals("securityScanEnabled"));

      this.updateSettings(repo, body);

      final var settings = this.settingsOf(repo);
      final var flipped = field.equals("privateRepo");
      assertThat(settings).containsEntry(field, flipped);
      for (final var other :
          List.of("privateRepo", "allowOverride", "releases", "snapshots", "securityScanEnabled")) {
        if (!other.equals(field)) {
          assertThat(settings).containsEntry(other, other.equals("privateRepo") ? false : true);
        }
      }
    }

    @Test
    @DisplayName("PUT {} resets booleans to false and leaves releases/snapshots unset")
    void emptyBodyResetsSettings() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.updateSettings(repo, "{}");

      // releases and snapshots are nullable tri-state columns, and null fields are omitted from
      // the JSON.
      assertThat(this.settingsOf(repo))
          .containsOnlyKeys("privateRepo", "allowOverride", "searchable", "securityScanEnabled")
          .containsEntry("privateRepo", false)
          .containsEntry("allowOverride", false)
          .containsEntry("searchable", false)
          .containsEntry("securityScanEnabled", false);
      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.getReleases()).isNull();
      assertThat(row.getSnapshots()).isNull();
    }

    @Test
    @DisplayName("ignores fields that are not part of the form, so searchable cannot be set")
    void unknownFieldIsIgnored() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.updateSettings(repo, "{\"searchable\":true,\"privateRepo\":true}");

      assertThat(this.settingsOf(repo))
          .containsEntry("searchable", false)
          .containsEntry("privateRepo", true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettingsBodies")
    @DisplayName("returns 400 validationError for an unreadable body and changes nothing")
    void invalidBody(final String label, final String body) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectValidationError(
          ProtocolRepoControllerIT.this.perform(
              json(put(repoUrl(repo, "/settings")), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    static Stream<Arguments> invalidSettingsBodies() {
      return Stream.of(
          Arguments.of("string instead of boolean", "{\"privateRepo\":\"yes\"}"),
          Arguments.of("object instead of boolean", "{\"allowOverride\":{}}"),
          Arguments.of("malformed JSON", "{not json"),
          Arguments.of("empty body", ""),
          Arguments.of("array instead of object", "[]"));
    }

    @Test
    @DisplayName("GET and PUT return 401 unAuthorized to an anonymous caller and change nothing")
    void anonymous() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(get(repoUrl(repo, "/settings"))));
      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              json(
                  put(repoUrl(repo, "/settings")),
                  settingsBody(true, false, false, false, false))));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoName}/usage
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoName}/usage")
  class Usage {

    private String usageOf(final Repo repo) throws Exception {
      return expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/usage"))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "usageFetched",
          "Usage fetched");
    }

    @Test
    @DisplayName("returns zero usage for an empty repo")
    void emptyRepo() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      final var body = this.usageOf(repo);

      assertThat(dataObject(body)).containsOnlyKeys("diskUsed");
      assertThat(JsonPath.<Map<String, Object>>read(body, "$.data.diskUsed"))
          .containsOnlyKeys("value", "text")
          .containsEntry("text", "0 B")
          .containsEntry("value", 0);
    }

    static Stream<Arguments> usages() {
      return Stream.of(
          Arguments.of(1L, "1 B"),
          Arguments.of(1023L, "1023 B"),
          Arguments.of(1024L, "1.00 KB"),
          Arguments.of(1536L, "1.50 KB"),
          Arguments.of(1048576L, "1.00 MB"),
          Arguments.of(5L * 1048576L, "5.00 MB"),
          Arguments.of(1073741824L, "1.00 GB"),
          Arguments.of(1099511627776L, "1.00 TB"));
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @MethodSource("usages")
    @DisplayName("returns the stored disk usage as a value and a human-readable text")
    void nonEmptyRepo(final long bytes, final String text) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      ProtocolRepoControllerIT.this.repoRepository.updateDiskUsage(repo.getId(), bytes);
      ProtocolRepoControllerIT.this.entityManager.clear();

      final var body = this.usageOf(repo);

      final Map<String, Object> diskUsed = JsonPath.read(body, "$.data.diskUsed");
      assertThat(diskUsed).containsOnlyKeys("value", "text").containsEntry("text", text);
      assertThat(((Number) diskUsed.get("value")).longValue()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("reports the usage of the requested repo only")
    void perRepoIsolation() throws Exception {
      final var busy = ProtocolRepoControllerIT.this.seedMaven();
      final var idle = ProtocolRepoControllerIT.this.seedMaven();
      ProtocolRepoControllerIT.this.repoRepository.updateDiskUsage(busy.getId(), 2048);
      ProtocolRepoControllerIT.this.entityManager.clear();

      assertThat(JsonPath.<String>read(this.usageOf(busy), "$.data.diskUsed.text"))
          .isEqualTo("2.00 KB");
      assertThat(JsonPath.<String>read(this.usageOf(idle), "$.data.diskUsed.text"))
          .isEqualTo("0 B");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoType}/info
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoType}/info")
  class Info {

    private String info(final String type, final String token) throws Exception {
      return expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get("/api/repos/" + type + "/info").header(AUTHORIZATION, token)),
          "reposFetched",
          "Repos fetched.");
    }

    private static List<String> names(final String body) {
      return dataList(body).stream().map(node -> (String) node.get("name")).toList();
    }

    @Test
    @DisplayName("lists only the startup default repo of a type before anything is created")
    void startupDefault() throws Exception {
      final var items =
          dataList(this.info("MAVEN", ProtocolRepoControllerIT.this.adminBearerToken()));

      assertThat(items).hasSize(1);
      assertThat(items.get(0)).containsEntry("name", "maven").containsEntry("privateRepo", true);
    }

    @Test
    @DisplayName("returns an empty array when the type has no repos")
    void emptyList() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();

      final var body = this.info("NPM", ProtocolRepoControllerIT.this.adminBearerToken());

      assertThat(JsonPath.<List<Object>>read(body, "$.data")).isEmpty();
    }

    @Test
    @DisplayName("returns every repo of the type, private ones included, with the full item shape")
    void severalRepos() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      final var open =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("open"), false, null);
      final var secret =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("secret"), true, "s");
      final var used = ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("used"));
      ProtocolRepoControllerIT.this.repoRepository.updateDiskUsage(used.getId(), 4096);
      ProtocolRepoControllerIT.this.entityManager.clear();

      final var items = dataList(this.info("NPM", ProtocolRepoControllerIT.this.userBearerToken()));

      // The query has no ORDER BY, so the order is unspecified and deliberately not asserted.
      assertThat(items).hasSize(3);
      final var byName =
          items.stream().collect(Collectors.toMap(i -> (String) i.get("name"), i -> i));
      assertThat(byName).containsOnlyKeys(open.getName(), secret.getName(), used.getName());
      for (final var repo : List.of(open, secret, used)) {
        final var item = byName.get(repo.getName());
        final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
        assertThat(item)
            .containsOnlyKeys(REPO_LIST_KEYS)
            .containsEntry("name", row.getName())
            .containsEntry("type", "NPM")
            .containsEntry("privateRepo", row.isPrivateRepo());
        assertThat(((Number) item.get("diskUsage")).longValue()).isEqualTo(row.getDiskUsage());
        assertThat(instantOrNull(item.get("createdAt"))).isEqualTo(row.getCreatedAt());
      }
      assertThat(byName.get(secret.getName())).containsEntry("privateRepo", true);
      assertThat(((Number) byName.get(used.getName()).get("diskUsage")).longValue())
          .isEqualTo(4096L);
    }

    @Test
    @DisplayName("returns only repos of the requested type")
    void onlyRequestedType() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      final var npm = ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("n"));
      final var maven = ProtocolRepoControllerIT.this.seedRepo(RepoType.MAVEN, uniqueRepoName("m"));
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();

      assertThat(names(this.info("NPM", token))).containsExactly(npm.getName());
      assertThat(names(this.info("MAVEN", token))).containsExactly(maven.getName());
      assertThat(names(this.info("PYPI", token))).isEmpty();
    }

    @Test
    @DisplayName("shows a renamed repo under its new name, and drops a deleted one")
    void reflectsRenameAndDelete() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      final var keep = ProtocolRepoControllerIT.this.seedRepo(RepoType.CARGO, uniqueRepoName("k"));
      final var gone = ProtocolRepoControllerIT.this.seedRepo(RepoType.CARGO, uniqueRepoName("g"));
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();
      final var renamed = uniqueRepoName("renamed");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(keep, "/name")), nameBody(renamed)).header(AUTHORIZATION, token)),
          "repoRenamed",
          "Repo renamed.");
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(gone, "")).header(AUTHORIZATION, token)),
          "repoDeleted",
          "Repo deleted.");

      assertThat(names(this.info("CARGO", token))).containsExactly(renamed);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("lists the repo created for every RepoType under that type")
    void everyType(final RepoType type) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              type, uniqueRepoName(type.name().toLowerCase(Locale.ROOT)));

      final var items =
          dataList(this.info(type.name(), ProtocolRepoControllerIT.this.adminBearerToken()));

      assertThat(items).extracting(i -> i.get("name")).contains(repo.getName());
      assertThat(items).allSatisfy(i -> assertThat(i).containsEntry("type", type.name()));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoType}/count
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoType}/count")
  class Count {

    private long count(final String type, final String token) throws Exception {
      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get("/api/repos/" + type + "/count").header(AUTHORIZATION, token)),
              "repoCountFetched",
              "Repo count fetched.");
      return ((Number) JsonPath.read(body, "$.data")).longValue();
    }

    @Test
    @DisplayName("counts the startup default repo of a type")
    void startupDefault() throws Exception {
      assertThat(this.count("MAVEN", ProtocolRepoControllerIT.this.adminBearerToken()))
          .isEqualTo(1);
    }

    @Test
    @DisplayName("returns 0 when the type has no repos")
    void zero() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();

      assertThat(this.count("NPM", ProtocolRepoControllerIT.this.adminBearerToken())).isZero();
    }

    @Test
    @DisplayName("returns the number of repos of the type, and follows create and delete")
    void countsRepos() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();
      final var first = ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("a"));
      ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("b"));
      ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("c"));

      assertThat(this.count("NPM", token)).isEqualTo(3);

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              delete(repoUrl(first, "")).header(AUTHORIZATION, token)),
          "repoDeleted",
          "Repo deleted.");

      assertThat(this.count("NPM", token)).isEqualTo(2);
    }

    @Test
    @DisplayName("counts each type separately")
    void perTypeIsolation() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();
      ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("n1"));
      ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("n2"));
      ProtocolRepoControllerIT.this.seedRepo(RepoType.MAVEN, uniqueRepoName("m1"));
      ProtocolRepoControllerIT.this.seedRepo(RepoType.GOLANG, uniqueRepoName("g1"));

      assertThat(this.count("NPM", token)).isEqualTo(2);
      assertThat(this.count("MAVEN", token)).isEqualTo(1);
      assertThat(this.count("GOLANG", token)).isEqualTo(1);
      assertThat(this.count("DOCKER", token)).isZero();
    }

    @Test
    @DisplayName("returns the count to a plain USER: MANAGE is declared but never enforced here")
    void plainUserIsAllowed() throws Exception {
      ProtocolRepoControllerIT.this.deleteDefaultRepos();
      ProtocolRepoControllerIT.this.seedRepo(RepoType.PYPI, uniqueRepoName("p"));

      assertThat(this.count("PYPI", ProtocolRepoControllerIT.this.userBearerToken())).isEqualTo(1);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PATCH /api/repos/{repoName}/name
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("PATCH /api/repos/{repoName}/name")
  class Rename {

    private void rename(final Repo repo, final String newName) throws Exception {
      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/name")), nameBody(newName))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoRenamed",
          "Repo renamed.");
    }

    @Test
    @DisplayName("renames the row and keeps id, type, settings and the storage directory")
    void renamesRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("old"), true, "keep me");
      writeFile(repo, "root.txt", "root");
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      final var newName = uniqueRepoName("new");

      this.rename(repo, newName);

      final var after = ProtocolRepoControllerIT.this.reloadRepo(newName);
      assertThat(after.getName()).isEqualTo(newName);
      assertThat(after).usingRecursiveComparison().ignoringFields("name").isEqualTo(before);
      assertThat(ProtocolRepoControllerIT.this.repoRepository.findByName(repo.getName())).isEmpty();
      // Storage is keyed by the repo id, so a rename moves nothing on disk.
      assertThat(storageDirOf(after)).isEqualTo(storageDirOf(before));
      assertThat(storageDirOf(after).resolve("root.txt")).hasContent("root");
    }

    @Test
    @DisplayName("resolves the new name and no longer the old one")
    void oldNameStopsResolving() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var newName = uniqueRepoName("new");
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();

      this.rename(repo, newName);

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get("/api/repos/" + newName + "/format").header(AUTHORIZATION, token)),
          "repoTypeFetched",
          "Repo type fetched.");
      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/format")).header(AUTHORIZATION, token)));
    }

    @Test
    @DisplayName("frees the old name for a new repo")
    void oldNameIsReusable() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.rename(repo, uniqueRepoName("new"));

      assertThat(ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, repo.getName()).getType())
          .isEqualTo(RepoType.NPM);
    }

    @Test
    @DisplayName("returns 409 repoExists when renaming to the current name (pinned)")
    void sameName() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectRepoExists(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/name")), nameBody(repo.getName()))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    @Test
    @DisplayName("returns 409 repoExists for a name used by a repo of the same or another type")
    void existingName() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var sameType = ProtocolRepoControllerIT.this.seedMaven();
      final var otherType =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("other"));
      final var token = ProtocolRepoControllerIT.this.adminBearerToken();

      for (final var taken : List.of(sameType.getName(), otherType.getName(), "maven", "npm")) {
        expectRepoExists(
            ProtocolRepoControllerIT.this.perform(
                json(patch(repoUrl(repo, "/name")), nameBody(taken)).header(AUTHORIZATION, token)));
      }

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getId())
          .isEqualTo(repo.getId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError for an invalid body and changes nothing")
    void invalidBody(final String label, final String body) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectValidationError(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/name")), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("empty object", "{}"),
          Arguments.of("null name", "{\"name\":null}"),
          Arguments.of("blank name", "{\"name\":\"\"}"),
          Arguments.of("26 characters", "{\"name\":\"" + "a".repeat(26) + "\"}"),
          Arguments.of("space", "{\"name\":\"a b\"}"),
          Arguments.of("slash", "{\"name\":\"a/b\"}"),
          Arguments.of("dot", "{\"name\":\"dot.name\"}"),
          Arguments.of("malformed JSON", "{not json"),
          Arguments.of("empty body", ""));
    }

    @Test
    @DisplayName("accepts a 25-character name and every allowed character")
    void acceptsAllowedNames() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var longName = "b".repeat(24) + randomTag().charAt(0);

      this.rename(repo, longName);
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(longName).getName()).hasSize(25);

      final var mixed = "Mixed_Case-" + randomTag();
      this.rename(ProtocolRepoControllerIT.this.reloadRepo(longName), mixed);
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(mixed).getName()).isEqualTo(mixed);
    }

    @Test
    @DisplayName("returns 404 repoNotFound for an unknown repo")
    void unknownRepo() throws Exception {
      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              json(patch("/api/repos/nope-" + randomTag() + "/name"), nameBody("whatever"))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // PATCH /api/repos/{repoName}/description
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("PATCH /api/repos/{repoName}/description")
  class Description {

    private String describe(final Repo repo, final String body) throws Exception {
      return expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/description")), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          "repoDescriptionEdited",
          "Repo description updated.");
    }

    @Test
    @DisplayName("sets the description on the row and in the permissions response")
    void setsDescription() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      final var body = this.describe(repo, descriptionBody("hello world"));

      assertThat(JsonPath.<Object>read(body, "$.data")).isNull();
      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getDescription())
          .isEqualTo("hello world");
      final var permissions =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/permissions"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");
      assertPermissions(
          dataObject(permissions), repo.getName(), "hello world", true, true, true, false);
    }

    @Test
    @DisplayName("replaces an existing description and touches nothing else")
    void replacesDescription() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.NPM, uniqueRepoName("desc"), true, "first");

      this.describe(repo, descriptionBody("second"));

      final var after = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(after.getDescription()).isEqualTo("second");
      assertThat(after).usingRecursiveComparison().ignoringFields("description").isEqualTo(repo);
    }

    @Test
    @DisplayName("clears the description with an empty string, which is stored as empty, not null")
    void clearsDescription() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("clear"), false, "something");

      this.describe(repo, descriptionBody(""));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getDescription())
          .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {1, 499, 500})
    @DisplayName("accepts descriptions up to 500 characters")
    void acceptsUpTo500(final int length) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.describe(repo, descriptionBody("d".repeat(length)));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getDescription())
          .hasSize(length);
    }

    @Test
    @DisplayName("returns 400 validationError for a 501-character description and changes nothing")
    void overLong() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("long"), false, "before");

      expectValidationError(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/description")), descriptionBody("d".repeat(501)))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getDescription())
          .isEqualTo("before");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    @DisplayName("returns 400 validationError when the description is null, missing or unreadable")
    void invalidBody(final String label, final String body) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.MAVEN, uniqueRepoName("inv"), false, "before");

      expectValidationError(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/description")), body)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName()).getDescription())
          .isEqualTo("before");
    }

    static Stream<Arguments> invalidBodies() {
      return Stream.of(
          Arguments.of("null description", "{\"description\":null}"),
          Arguments.of("missing description", "{}"),
          Arguments.of("malformed JSON", "{not json"),
          Arguments.of("empty body", ""));
    }

    @Test
    @DisplayName("returns 404 repoNotFound for an unknown repo")
    void unknownRepo() throws Exception {
      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              json(patch("/api/repos/nope-" + randomTag() + "/description"), descriptionBody("x"))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GET /api/repos/{repoName}/format
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("GET /api/repos/{repoName}/format")
  class Format {

    @ParameterizedTest(name = "{0}")
    @EnumSource(RepoType.class)
    @DisplayName("returns the lower-cased type name for every RepoType")
    void lowerCasedTypeName(final RepoType type) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              type, uniqueRepoName(type.name().toLowerCase(Locale.ROOT)));

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get(repoUrl(repo, "/format"))
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
              "repoTypeFetched",
              "Repo type fetched.");

      assertThat(JsonPath.<String>read(body, "$.data"))
          .isEqualTo(type.name().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("returns the same for the startup default repo, spelled 'golang' for Go")
    void startupGoRepo() throws Exception {
      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get("/api/repos/go/format")
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
              "repoTypeFetched",
              "Repo type fetched.");

      assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo("golang");
    }

    @Test
    @DisplayName("serves a public repo anonymously")
    void anonymousPublic() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedRepo(RepoType.HELM, uniqueRepoName("pub"));

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(get(repoUrl(repo, "/format"))),
              "repoTypeFetched",
              "Repo type fetched.");

      assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo("helm");
    }

    @Test
    @DisplayName("returns 401 unAuthorized to an anonymous caller for a private repo")
    void anonymousPrivate() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.HELM, uniqueRepoName("priv"), true, null);

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(get(repoUrl(repo, "/format"))));
    }

    @Test
    @DisplayName("returns 404 repoNotFound for an unknown repo")
    void unknownRepo() throws Exception {
      expectRepoNotFound(
          ProtocolRepoControllerIT.this.perform(
              get("/api/repos/nope-" + randomTag() + "/format")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Unsupported verbs and unmapped routes (RPS-849)
  // ---------------------------------------------------------------------------------------------

  @Nested
  @DisplayName("routing")
  class Routing {

    static Stream<Arguments> unmappedRoutes() {
      return Stream.of(
          Arguments.of("GET /api/repos", "GET", "/api/repos"),
          Arguments.of("POST /api/repos", "POST", "/api/repos"),
          Arguments.of("GET /api/repos/{repoType}", "GET", "/api/repos/MAVEN"),
          Arguments.of("PUT /api/repos/{repoType}", "PUT", "/api/repos/MAVEN"),
          Arguments.of("PATCH /api/repos/{repoType}", "PATCH", "/api/repos/MAVEN"),
          Arguments.of("DELETE /api/repos/{repoType}/info", "DELETE", "/api/repos/MAVEN/info"),
          Arguments.of("POST /api/repos/{repoType}/info", "POST", "/api/repos/MAVEN/info"),
          Arguments.of("PUT /api/repos/{repoType}/count", "PUT", "/api/repos/MAVEN/count"),
          Arguments.of("POST /api/repos/{repoName}/format", "POST", "/api/repos/some-repo/format"),
          Arguments.of(
              "DELETE /api/repos/{repoName}/format", "DELETE", "/api/repos/some-repo/format"),
          Arguments.of("PUT /api/repos/{repoName}/name", "PUT", "/api/repos/some-repo/name"),
          Arguments.of("POST /api/repos/{repoName}/name", "POST", "/api/repos/some-repo/name"),
          Arguments.of(
              "PUT /api/repos/{repoName}/description", "PUT", "/api/repos/some-repo/description"),
          Arguments.of(
              "PATCH /api/repos/{repoName}/settings", "PATCH", "/api/repos/some-repo/settings"),
          Arguments.of(
              "POST /api/repos/{repoName}/settings", "POST", "/api/repos/some-repo/settings"),
          Arguments.of("POST /api/repos/{repoName}/usage", "POST", "/api/repos/some-repo/usage"),
          Arguments.of(
              "DELETE /api/repos/{repoName}/permissions",
              "DELETE",
              "/api/repos/some-repo/permissions"),
          Arguments.of(
              "PUT /api/repos/{repoName}/contents", "PUT", "/api/repos/some-repo/contents"),
          Arguments.of("GET /api/repos/{repoName}/unknown", "GET", "/api/repos/some-repo/unknown"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unmappedRoutes")
    @DisplayName("answers an unmapped route or verb with 404 itemNotFound, whoever asks")
    void unmappedRoute(final String label, final String method, final String url) throws Exception {
      // Neither credentials nor an existing repo are needed: routing fails before the interceptor.
      expectError(
          ProtocolRepoControllerIT.this.perform(request(method, url)),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          ITEM_NOT_FOUND_TEXT);
      expectError(
          ProtocolRepoControllerIT.this.perform(
              request(method, url)
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.NOT_FOUND,
          "itemNotFound",
          null,
          ITEM_NOT_FOUND_TEXT);
    }

    private static MockHttpServletRequestBuilder request(final String method, final String url) {
      return switch (method) {
        case "GET" -> get(url);
        case "POST" -> post(url);
        case "PUT" -> put(url);
        case "PATCH" -> patch(url);
        default -> delete(url);
      };
    }
  }
}
