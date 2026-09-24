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
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
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
 * <p>The repository collection ({@code GET /api/repos}, {@code GET /api/repos/counts} and {@code
 * POST /api/repos}) is {@code RepoCollectionController}, tested in {@code
 * RepoCollectionControllerIT}. Every route here names a repo. The three per-type routes that used
 * to live here ({@code GET /api/repos/{repoType}/info}, {@code GET /api/repos/{repoType}/count} and
 * {@code POST /api/repos/{repoType}}) are gone, and {@link Routing} pins that.
 *
 * <p>Notes on what is deliberately pinned rather than "fixed", because these are characterization
 * tests of today's behavior:
 *
 * <ul>
 *   <li>authorization is only {@code READ}/{@code WRITE} for any authenticated user and {@code
 *       MANAGE} for {@code ADMIN}; there is no repo owner concept;
 *   <li>a request without credentials to a repo that does not exist gets 401, like one to a private
 *       repo, so a missing repo cannot be told from a private one;
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
  private static final String REPO_NOT_FOUND_TEXT = "Repository not found";
  private static final String REPO_EXISTS_TEXT = "The repository exists. Please try another name.";
  private static final String REPO_NAME_RESERVED_TEXT =
      "This name is reserved for the panel. Please try another name.";
  private static final String UNAUTHORIZED_TEXT = "The user has logged in but has no permissions.";
  private static final String ACCESS_NOT_ALLOWED_TEXT = "Access isn't allowed.";
  private static final String SESSION_EXPIRED_TEXT = "Session expired.";
  private static final String ITEM_NOT_FOUND_TEXT = "The requested item is not found.";
  private static final String REPO_SCOPE_NOT_MATCHED_TEXT = "Repository scope does not match.";
  private static final String ERROR_OCCURRED_TEXT = "An error occurred.";

  private static final String[] SETTINGS_KEYS = {
    "privateRepo", "releases", "snapshots", "allowOverride", "searchable", "securityScanEnabled"
  };

  /** What GET returns for a Maven repo: the settings plus the two PGP ones (RPS-1188, RPS-1204). */
  private static final String[] MAVEN_SETTINGS_KEYS = {
    "privateRepo",
    "releases",
    "snapshots",
    "allowOverride",
    "searchable",
    "securityScanEnabled",
    "pgpVerifyAllSignaturesEnabled",
    "pgpKeyServerLookupEnabled"
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

  /** The body of {@code POST /api/repos} for a Maven repo. */
  private static String createBody(final String name) {
    return "{\"name\":\"%s\",\"type\":\"MAVEN\"}".formatted(name);
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

  /** {@link #settingsBody} with every field set to {@code value}, except {@code omittedField}. */
  private static String settingsBodyWithout(final String omittedField, final boolean value) {
    final var fields = new HashMap<String, Boolean>();
    fields.put("privateRepo", value);
    fields.put("allowOverride", value);
    fields.put("releases", value);
    fields.put("snapshots", value);
    fields.put("securityScanEnabled", value);
    fields.remove(omittedField);

    return fields.entrySet().stream()
        .map(entry -> "\"%s\":%s".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(",", "{", "}"));
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

  // ---------------------------------------------------------------------------------------------
  // Authentication & authorization -- identical rules, so parameterized over every endpoint
  // ---------------------------------------------------------------------------------------------

  /** How the interceptor treats a route, which decides what an anonymous caller gets. */
  private enum Kind {
    /** Route with a repo name and {@code READ}: public repos are readable without credentials. */
    REPO_READ,
    /** Route with a repo name and {@code MANAGE}: only an ADMIN gets in. */
    REPO_MANAGE
  }

  /** What a request targets: the name of a repo. */
  private record Target(String repoName) {}

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

    private Target target() {
      return new Target(ProtocolRepoControllerIT.this.seedMaven().getName());
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
    @DisplayName("returns 401 unAuthorized when the token's user no longer exists")
    void tokenUserNoLongerExists(final Endpoint endpoint) throws Exception {
      final var target = this.target();
      // Authentication resolves the caller by the token's username claim, not by its subject id.
      final var token =
          ProtocolRepoControllerIT.this.bearerTokenFor(UUID.randomUUID(), uniqueUsername("ghost"));

      expectError(
          ProtocolRepoControllerIT.this.perform(
              endpoint.request().apply(target).header(AUTHORIZATION, token)),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          UNAUTHORIZED_TEXT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoScopedEndpoints")
    @DisplayName("returns 404 repoNotFound for an unknown repo once the caller is authorized")
    void unknownRepo(final Endpoint endpoint) throws Exception {
      final var target = new Target("nope-" + randomTag());

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
      final var target = new Target("nope-" + randomTag());

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
      final var target = new Target(repo.getName());

      expectUnauthorized(ProtocolRepoControllerIT.this.perform(endpoint.request().apply(target)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoManageEndpoints")
    @DisplayName(
        "returns 403 accessDenied for a non-admin caller on a MANAGE route, changing nothing")
    void manageRouteAsPlainUser(final Endpoint endpoint) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      final var target = new Target(repo.getName());

      expectForbidden(
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
      ProtocolRepoControllerIT.this.createUser(username, UserRole.ADMIN);

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              get(this.basicUrl()).header(AUTHORIZATION, basicAuth(username, VALID_PASSWORD))),
          "settingsFetched",
          "Settings fetched.");
    }

    @Test
    @DisplayName("returns 401 unAuthorized for HTTP Basic credentials with a wrong password")
    void basicCredentialsWrongPassword() throws Exception {
      final var username = uniqueUsername("basic");
      ProtocolRepoControllerIT.this.createUser(username, UserRole.USER);

      expectUnauthorized(
          ProtocolRepoControllerIT.this.perform(
              get(this.basicUrl()).header(AUTHORIZATION, basicAuth(username, "wrong"))));
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

    /** A MANAGE route of a repo that exists, which takes Basic credentials as well as a Bearer. */
    private String basicUrl() {
      return repoUrl(ProtocolRepoControllerIT.this.seedMaven(), "/settings");
    }

    /** The status and error envelope of a Basic-authenticated call, minus the random errorCode. */
    private Map<String, Object> basicError(final String authHeader) throws Exception {
      final var response =
          ProtocolRepoControllerIT.this
              .perform(get(this.basicUrl()).header(AUTHORIZATION, authHeader))
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
    @DisplayName("does not grant owner privileges: repository access is role-based")
    void creatorIsNotAnOwner() throws Exception {
      final var creator =
          ProtocolRepoControllerIT.this.createUser(uniqueUsername("creator"), UserRole.ADMIN);
      final var token = ProtocolRepoControllerIT.this.bearerTokenFor(creator);
      final var name = uniqueRepoName("mine");

      expectSuccess(
          ProtocolRepoControllerIT.this.perform(
              json(post("/api/repos"), createBody(name)).header(AUTHORIZATION, token)),
          "repoCreated",
          "Repo created.");

      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get("/api/repos/" + name + "/permissions").header(AUTHORIZATION, token)),
              "repoPermissionsFetched",
              "Repo permissions of the user have fetched.");

      assertPermissions(dataObject(body), name, null, true, true, true, false);
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
    @DisplayName("returns 400 repoScopeNotMatched for a non-Maven repo")
    void nonMavenRepo(final RepoType type) throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              type, uniqueRepoName(type.name().toLowerCase(Locale.ROOT)));

      expectError(
          ProtocolRepoControllerIT.this.perform(
              get(repoUrl(repo, "/contents"))
                  .param("path", "/")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "repoScopeNotMatched",
          "repoScopeNotMatched",
          REPO_SCOPE_NOT_MATCHED_TEXT);
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

    private static final List<String> SETTINGS_FIELDS =
        List.of("privateRepo", "allowOverride", "releases", "snapshots", "securityScanEnabled");

    /**
     * {@link #SETTINGS_KEYS} without {@code releases}/{@code snapshots}: what GET returns for a
     * repo type whose publish path does not consult them (RPS-1210).
     */
    private static final String[] SETTINGS_KEYS_WITHOUT_RELEASES_SNAPSHOTS = {
      "privateRepo", "allowOverride", "searchable", "securityScanEnabled"
    };

    /** Repo types other than Maven and NuGet, whose publish path never reads releases/snapshots. */
    private static final List<RepoType> RELEASES_SNAPSHOTS_UNSUPPORTED_TYPES =
        List.of(
            RepoType.NPM,
            RepoType.PYPI,
            RepoType.DOCKER,
            RepoType.CARGO,
            RepoType.GOLANG,
            RepoType.HELM,
            RepoType.RUBY);

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
          .containsOnlyKeys(MAVEN_SETTINGS_KEYS)
          .containsEntry("privateRepo", false)
          .containsEntry("releases", true)
          .containsEntry("snapshots", true)
          .containsEntry("allowOverride", true)
          .containsEntry("searchable", false)
          .containsEntry("securityScanEnabled", true)
          .containsEntry("pgpVerifyAllSignaturesEnabled", false)
          .containsEntry("pgpKeyServerLookupEnabled", true);
    }

    @Test
    @DisplayName(
        "returns the settings of a private repo of any type, omitting releases/snapshots for a"
            + " type that does not support them (RPS-1210)")
    void privateNonMavenRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(
              RepoType.DOCKER, uniqueRepoName("dkr"), true, null);

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(SETTINGS_KEYS_WITHOUT_RELEASES_SNAPSHOTS)
          .containsEntry("privateRepo", true)
          .containsEntry("securityScanEnabled", true);
    }

    @Test
    @DisplayName("PUT replaces every setting, and GET and the row show the new values")
    void updateAllSettings() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.updateSettings(repo, settingsBody(true, false, false, false, false));

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(MAVEN_SETTINGS_KEYS)
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

    private void assertOtherFieldsEqual(
        final Map<String, Object> settings, final String changedField, final boolean otherValue) {
      for (final var other : SETTINGS_FIELDS) {
        if (!other.equals(changedField)) {
          assertThat(settings).containsEntry(other, otherValue);
        }
      }
    }

    static Stream<Arguments> settingsFieldsWithInitialValues() {
      return SETTINGS_FIELDS.stream()
          .flatMap(field -> Stream.of(Arguments.of(field, true), Arguments.of(field, false)));
    }

    @ParameterizedTest(name = "{0} initial={1}")
    @MethodSource("settingsFieldsWithInitialValues")
    @DisplayName("a field omitted from the PUT body keeps its current value; the rest are applied")
    void omittedFieldIsLeftUnchanged(final String field, final boolean initial) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      this.updateSettings(repo, settingsBody(initial, initial, initial, initial, initial));

      this.updateSettings(repo, settingsBodyWithout(field, !initial));

      final var settings = this.settingsOf(repo);
      assertThat(settings).containsEntry(field, initial);
      this.assertOtherFieldsEqual(settings, field, !initial);

      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.getReleases()).isNotNull();
      assertThat(row.getSnapshots()).isNotNull();
      if (field.equals("releases")) {
        assertThat(row.getReleases()).isEqualTo(initial);
      }
      if (field.equals("snapshots")) {
        assertThat(row.getSnapshots()).isEqualTo(initial);
      }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {"privateRepo", "allowOverride", "releases", "snapshots", "securityScanEnabled"})
    @DisplayName("an explicit false for one field is applied although every other field is omitted")
    void explicitFalseIsApplied(final String field) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      this.updateSettings(repo, settingsBody(true, true, true, true, true));

      this.updateSettings(repo, "{\"%s\":false}".formatted(field));

      final var settings = this.settingsOf(repo);
      assertThat(settings).containsEntry(field, false);
      this.assertOtherFieldsEqual(settings, field, true);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {"privateRepo", "allowOverride", "releases", "snapshots", "securityScanEnabled"})
    @DisplayName("an explicit true for one field is applied although every other field is omitted")
    void explicitTrueIsApplied(final String field) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      this.updateSettings(repo, settingsBody(false, false, false, false, false));

      this.updateSettings(repo, "{\"%s\":true}".formatted(field));

      final var settings = this.settingsOf(repo);
      assertThat(settings).containsEntry(field, true);
      this.assertOtherFieldsEqual(settings, field, false);
    }

    @Test
    @DisplayName("PUT {} changes nothing: every field, including releases/snapshots, is untouched")
    void emptyBodyChangesNothing() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      this.updateSettings(repo, settingsBody(true, false, true, false, true));
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      this.updateSettings(repo, "{}");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(MAVEN_SETTINGS_KEYS)
          .containsEntry("privateRepo", true)
          .containsEntry("allowOverride", false)
          .containsEntry("releases", true)
          .containsEntry("snapshots", false)
          .containsEntry("securityScanEnabled", true);
    }

    @Test
    @DisplayName("an explicit JSON null is treated like an omitted field and changes nothing")
    void explicitNullIsLeftUnchanged() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      this.updateSettings(repo, settingsBody(true, false, true, false, true));
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      this.updateSettings(repo, "{\"privateRepo\":null,\"releases\":null}");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    @Test
    @DisplayName(
        "a partial PUT on a non-Maven/NuGet repo leaves its creation defaults untouched, and GET"
            + " omits releases/snapshots although the row still carries their creation defaults"
            + " (RPS-1210)")
    void partialPutOnNonMavenRepo() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("npmset"));
      assertThat(this.settingsOf(repo))
          .doesNotContainKeys("releases", "snapshots")
          .containsEntry("privateRepo", false)
          .containsEntry("allowOverride", true)
          .containsEntry("securityScanEnabled", true);

      this.updateSettings(repo, "{\"securityScanEnabled\":false}");

      assertThat(this.settingsOf(repo))
          .doesNotContainKeys("releases", "snapshots")
          .containsEntry("privateRepo", false)
          .containsEntry("allowOverride", true)
          .containsEntry("securityScanEnabled", false);
      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.isPrivateRepo()).isFalse();
      assertThat(row.isAllowOverride()).isTrue();
      // Still true from createRepo's defaults: this endpoint never touched them, and the row keeps
      // them even though the type does not expose them through settings any more.
      assertThat(row.getReleases()).isTrue();
      assertThat(row.getSnapshots()).isTrue();
      assertThat(row.isSecurityScanEnabled()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY"})
    @DisplayName(
        "PUT rejects releases/snapshots with 400 releasesSnapshotsUnsupported for a repo type"
            + " whose publish path does not consult them, and changes nothing (RPS-1210)")
    void rejectsReleasesSnapshotsForUnsupportedType(final RepoType repoType) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedRepo(repoType, uniqueRepoName("scoped"));
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectError(
          ProtocolRepoControllerIT.this.perform(
              json(put(repoUrl(repo, "/settings")), "{\"releases\":false}")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "releasesSnapshotsUnsupported",
          "releasesSnapshotsUnsupported",
          "The releases and snapshots settings only apply to Maven and NuGet repositories.");

      expectError(
          ProtocolRepoControllerIT.this.perform(
              json(put(repoUrl(repo, "/settings")), "{\"snapshots\":false}")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "releasesSnapshotsUnsupported",
          "releasesSnapshotsUnsupported",
          "The releases and snapshots settings only apply to Maven and NuGet repositories.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    @Test
    @DisplayName(
        "a PUT that changes another field alongside a rejected releases/snapshots field changes"
            + " nothing at all (RPS-1210)")
    void rejectedReleasesSnapshotsFieldBlocksTheWholeUpdate() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NPM, uniqueRepoName("scopedall"));
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectError(
          ProtocolRepoControllerIT.this.perform(
              json(
                      put(repoUrl(repo, "/settings")),
                      "{\"securityScanEnabled\":false,\"releases\":false}")
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "releasesSnapshotsUnsupported",
          "releasesSnapshotsUnsupported",
          "The releases and snapshots settings only apply to Maven and NuGet repositories.");

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
    }

    @Test
    @DisplayName(
        "NuGet, like Maven, exposes and accepts releases/snapshots through settings (RPS-1210)")
    void nuGetSupportsReleasesSnapshots() throws Exception {
      final var repo =
          ProtocolRepoControllerIT.this.seedRepo(RepoType.NUGET, uniqueRepoName("nugetset"));

      assertThat(this.settingsOf(repo))
          .containsOnlyKeys(SETTINGS_KEYS)
          .containsEntry("releases", true)
          .containsEntry("snapshots", true);

      this.updateSettings(repo, "{\"releases\":false,\"snapshots\":false}");

      assertThat(this.settingsOf(repo))
          .containsEntry("releases", false)
          .containsEntry("snapshots", false);
      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.getReleases()).isFalse();
      assertThat(row.getSnapshots()).isFalse();
    }

    @Test
    @DisplayName("the PGP settings of a Maven repo are updated field by field (RPS-1188, RPS-1204)")
    void pgpSettingsAreUpdatedFieldByField() throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();

      this.updateSettings(repo, "{\"pgpVerifyAllSignaturesEnabled\":true}");

      assertThat(this.settingsOf(repo))
          .containsEntry("pgpVerifyAllSignaturesEnabled", true)
          .containsEntry("pgpKeyServerLookupEnabled", true);

      this.updateSettings(repo, "{\"pgpKeyServerLookupEnabled\":false}");

      assertThat(this.settingsOf(repo))
          .containsEntry("pgpVerifyAllSignaturesEnabled", true)
          .containsEntry("pgpKeyServerLookupEnabled", false)
          .containsEntry("securityScanEnabled", true);
      final var row = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());
      assertThat(row.isPgpVerifyAllSignaturesEnabled()).isTrue();
      assertThat(row.isPgpKeyServerLookupEnabled()).isFalse();

      // A body that leaves the PGP fields out, or sends null, keeps them (RPS-1200 style).
      this.updateSettings(repo, settingsBody(true, false, true, false, true));
      this.updateSettings(repo, "{}");
      this.updateSettings(
          repo, "{\"pgpVerifyAllSignaturesEnabled\":null,\"pgpKeyServerLookupEnabled\":null}");

      assertThat(this.settingsOf(repo))
          .containsEntry("pgpVerifyAllSignaturesEnabled", true)
          .containsEntry("pgpKeyServerLookupEnabled", false)
          .containsEntry("privateRepo", true);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = RepoType.class,
        names = {"NPM", "PYPI", "DOCKER", "CARGO", "GOLANG", "HELM", "RUBY", "NUGET"})
    @DisplayName(
        "PUT rejects a PGP setting with 400 pgpSettingsUnsupported for a repo that is not a Maven"
            + " one, changes nothing, and GET omits both (RPS-1188, RPS-1204)")
    void rejectsPgpSettingsForANonMavenRepo(final RepoType repoType) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedRepo(repoType, uniqueRepoName("pgpset"));
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      for (final var body :
          List.of(
              "{\"pgpVerifyAllSignaturesEnabled\":true}",
              "{\"pgpKeyServerLookupEnabled\":false}",
              "{\"securityScanEnabled\":false,\"pgpKeyServerLookupEnabled\":false}")) {
        expectError(
            ProtocolRepoControllerIT.this.perform(
                json(put(repoUrl(repo, "/settings")), body)
                    .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
            HttpStatus.BAD_REQUEST,
            "pgpSettingsUnsupported",
            "pgpSettingsUnsupported",
            "The PGP signature settings only apply to Maven repositories.");
      }

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
      assertThat(this.settingsOf(repo))
          .doesNotContainKeys("pgpVerifyAllSignaturesEnabled", "pgpKeyServerLookupEnabled");
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

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
        strings = {
          "login",
          "profile",
          "repositories",
          "users",
          "security",
          "not-found",
          "api",
          "assets",
          "SECURITY",
          "Not-Found"
        })
    @DisplayName(
        "returns 400 repoNameReserved when renaming to a name reserved by the panel's routes"
            + " (RPS-1158)")
    void rejectsReservedName(final String name) throws Exception {
      final var repo = ProtocolRepoControllerIT.this.seedMaven();
      final var before = ProtocolRepoControllerIT.this.reloadRepo(repo.getName());

      expectError(
          ProtocolRepoControllerIT.this.perform(
              json(patch(repoUrl(repo, "/name")), nameBody(name))
                  .header(AUTHORIZATION, ProtocolRepoControllerIT.this.adminBearerToken())),
          HttpStatus.BAD_REQUEST,
          "repoNameReserved",
          "repoNameReserved",
          REPO_NAME_RESERVED_TEXT);

      assertThat(ProtocolRepoControllerIT.this.reloadRepo(repo.getName())).isEqualTo(before);
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
    @DisplayName("returns the upper-case type name, the RepoType enum, for every RepoType")
    void upperCasedTypeName(final RepoType type) throws Exception {
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

      assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo(type.name());
    }

    @Test
    @DisplayName("returns the same for the startup default repo, spelled 'GOLANG' for Go")
    void startupGoRepo() throws Exception {
      final var body =
          expectSuccess(
              ProtocolRepoControllerIT.this.perform(
                  get("/api/repos/go/format")
                      .header(AUTHORIZATION, ProtocolRepoControllerIT.this.userBearerToken())),
              "repoTypeFetched",
              "Repo type fetched.");

      assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo("GOLANG");
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

      assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo("HELM");
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
          Arguments.of("PUT /api/repos", "PUT", "/api/repos"),
          Arguments.of("DELETE /api/repos", "DELETE", "/api/repos"),
          Arguments.of("GET /api/repos/{repoName}", "GET", "/api/repos/some-repo"),
          Arguments.of("PUT /api/repos/{repoName}", "PUT", "/api/repos/some-repo"),
          Arguments.of("PATCH /api/repos/{repoName}", "PATCH", "/api/repos/some-repo"),
          Arguments.of("POST /api/repos/{repoName}", "POST", "/api/repos/some-repo"),
          // The per-type routes RPS-1268 removed: GET /api/repos/{repoType}/info and /count, and
          // POST /api/repos/{repoType}. Their replacements are GET /api/repos, GET
          // /api/repos/counts and POST /api/repos.
          Arguments.of("GET /api/repos/MAVEN/info (removed)", "GET", "/api/repos/MAVEN/info"),
          Arguments.of("GET /api/repos/MAVEN/count (removed)", "GET", "/api/repos/MAVEN/count"),
          Arguments.of("POST /api/repos/MAVEN (removed)", "POST", "/api/repos/MAVEN"),
          Arguments.of("POST /api/repos/maven (removed)", "POST", "/api/repos/maven"),
          Arguments.of("DELETE /api/repos/MAVEN/info", "DELETE", "/api/repos/MAVEN/info"),
          Arguments.of("POST /api/repos/MAVEN/info", "POST", "/api/repos/MAVEN/info"),
          Arguments.of("PUT /api/repos/MAVEN/count", "PUT", "/api/repos/MAVEN/count"),
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
