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
package io.repsy.os.server.protocols.npm.ui.controllers;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end coverage for every npm package-management API mapping. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("NpmPackageApiController /api/npm/packages/*")
class NpmPackageApiControllerIT {

  private static final int API_PORT = 8080;
  private static final int REPOSITORY_PORT = 9090;
  private static final String PASSWORD = "Password1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final Path STORAGE_PATH = createTempStoragePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", NpmPackageApiControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    return STORAGE_PATH.toString();
  }

  private static Path createTempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-npm-package-api-it");
    } catch (final java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private NpmApiFacade npmApiFacade;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;

  private Repo repo;
  private User admin;
  private String repoName;

  @BeforeEach
  void setUp() throws Exception {
    this.admin = this.createUser(UserRole.ADMIN);
    this.repoName = "npm-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    final var repoInfo =
        this.repoTxService.createRepo(this.repoName, RepoType.NPM, false, "Npm IT");
    this.repo = this.repoRepository.findById(repoInfo.getId()).orElseThrow();
    this.npmApiFacade.createRepo(this.repo.getId());

    this.publish(null, "plain-package", "1.0.0", "latest");
    this.publish(null, "plain-package", "2.0.0-next.1", "next");
    this.publish("tools", "scoped-package", "1.0.0", "latest");
    this.publish(null, "scope", "1.0.0", "latest");
  }

  private User createUser(final UserRole role) {
    final var username = "npm-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(PASSWORD, salt);
    final var info = this.userTxService.create(username, role, hash, salt);
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private void publish(
      final String scope, final String packageName, final String version, final String tag)
      throws Exception {
    final var packagePath = scope == null ? packageName : "@" + scope + "/" + packageName;
    final var tarball =
        Base64.getEncoder()
            .encodeToString((packagePath + version).getBytes(StandardCharsets.UTF_8));
    final var payload =
        "{\"name\":\""
            + packagePath
            + "\",\"dist-tags\":{\""
            + tag
            + "\":\""
            + version
            + "\"},\"versions\":{\""
            + version
            + "\":{\"name\":\""
            + packagePath
            + "\",\"version\":\""
            + version
            + "\",\"description\":\"integration fixture\","
            + "\"keywords\":[\"fixture\",\"npm\"],\"author\":{\"name\":\"Repsy\",\"email\":\"test@repsy.io\"},"
            + "\"license\":\"Apache-2.0\",\"homepage\":\"https://repsy.io\","
            + "\"dependencies\":{\"left-pad\":\"1.3.0\"},\"devDependencies\":{\"jest\":\"29\"},"
            + "\"peerDependencies\":{\"node\":\">=18\"},\"dist\":{\"tarball\":\"http://localhost/"
            + packagePath
            + "-"
            + version
            + ".tgz\",\"shasum\":\"abc\",\"integrity\":\"sha512-abc\"}}},"
            + "\"_attachments\":{\""
            + packageName
            + "-"
            + version
            + ".tgz\":{\"content_type\":\"application/octet-stream\",\"data\":\""
            + tarball
            + "\",\"length\":"
            + (packagePath.length() + version.length())
            + "}}}";

    this.mockMvc
        .perform(
            put("/{repo}/{package}", this.repoName, packagePath)
                .servletPath("/" + this.repoName + "/" + packagePath)
                .with(repositoryPort())
                .header(AUTHORIZATION, basicAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk());
  }

  private String basicAuth() {
    final var credentials = this.admin.getUsername() + ":" + PASSWORD;
    return AuthUtils.AUTH_BASIC
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private String bearerToken(final User user, final Duration duration) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(user.getId(), user.getUsername(), duration);
  }

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private static RequestPostProcessor repositoryPort() {
    return request -> {
      request.setLocalPort(REPOSITORY_PORT);
      return request;
    };
  }

  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  @Nested
  @DisplayName("list and route mappings")
  class Lists {

    @Test
    void listsScopedAndUnscopedPackagesWithAllFiltersAndPageMetadata() throws Exception {
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}", repoName))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("packagesFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data.content", hasSize(3)))
          .andExpect(jsonPath("$.data.page.size").value(10))
          .andExpect(jsonPath("$.data.page.number").value(0))
          .andExpect(jsonPath("$.data.page.totalElements").value(3))
          .andExpect(jsonPath("$.data.page.totalPages").value(1));

      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}", repoName).param("scope", "tools"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].scope").value("tools"));
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}/ignored", repoName).param("name", "plain"))
          .andExpect(
              result ->
                  org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                      .isIn(200, 404));
      NpmPackageApiControllerIT.this
          .perform(
              get("/api/npm/packages/{repo}/scope/{scope}", repoName, "tools")
                  .param("name", "scoped"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].scope").value("tools"));
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}/scope/{scope}", repoName, "tools"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)));
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}", repoName).param("page", "1").param("size", "1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.page.size").value(1))
          .andExpect(jsonPath("$.data.page.totalPages").value(3));
    }

    @Test
    void resolvesVersionRoutesAndTheLiteralScopePackagePredictably() throws Exception {
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}/plain-package/versions/1.0.0", repoName))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageVersionFetched"))
          .andExpect(jsonPath("$.data.packageName").value("plain-package"))
          .andExpect(jsonPath("$.data.distributionTags[0].tagName").value("latest"))
          .andExpect(jsonPath("$.data.deleted").value(false))
          .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));
      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/{scope}/{package}/versions/1.0.0",
                  repoName,
                  "tools",
                  "scoped-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.scopeName").value("tools"))
          .andExpect(jsonPath("$.data.packageName").value("scoped-package"));
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}/scope/versions/1.0.0", repoName))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageVersionFetched"))
          .andExpect(jsonPath("$.data.packageName").value("scope"));
    }

    @Test
    void listsVersionsAndTagsForBothScopeForms() throws Exception {
      NpmPackageApiControllerIT.this
          .perform(
              get("/api/npm/packages/{repo}/package/{package}/versions", repoName, "plain-package")
                  .param("version", "2.0"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageVersionsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].version").value("2.0.0-next.1"))
          .andExpect(jsonPath("$.data.content[0].deprecated").value(false));
      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/tools/package/{package}/versions",
                  repoName,
                  "scoped-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)));
      NpmPackageApiControllerIT.this
          .perform(
              get("/api/npm/packages/{repo}/package/{package}/tags", repoName, "plain-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageTagsFetched"))
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].tag").value("latest"));
      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/tools/package/{package}/tags",
                  repoName,
                  "scoped-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(1)))
          .andExpect(jsonPath("$.data[0].tag").value("latest"));
    }
  }

  @Nested
  @DisplayName("authorization, errors and deletes")
  class SecurityAndDeletes {

    @Test
    void rejectsMalformedExpiredAndUnknownBearerTokensOnPrivateReads() throws Exception {
      final var privateRepoName =
          "npm-private-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
      final var info = repoTxService.createRepo(privateRepoName, RepoType.NPM, true, "private");
      npmApiFacade.createRepo(info.getId());
      perform(get("/api/npm/packages/{repo}", privateRepoName))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.type").value("ERROR"));
      perform(
              get("/api/npm/packages/{repo}", privateRepoName)
                  .header(AUTHORIZATION, "Bearer malformed"))
          .andExpect(status().isForbidden());
      perform(
              get("/api/npm/packages/{repo}", privateRepoName)
                  .header(AUTHORIZATION, bearerToken(admin, Duration.ofSeconds(-30))))
          .andExpect(status().isForbidden());
      final var unknown =
          AuthUtils.AUTH_BEARER
              + jwtUtils.createTokenWithDuration(
                  UUID.randomUUID(), "deleted-user", Duration.ofMinutes(30));
      perform(get("/api/npm/packages/{repo}", privateRepoName).header(AUTHORIZATION, unknown))
          .andExpect(status().isNotFound());
    }

    @Test
    void requiresManageForDeleteAndReturnsCompleteErrors() throws Exception {
      perform(delete("/api/npm/packages/{repo}/{package}", repoName, "plain-package"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.data").value("unAuthorized"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
      final var user = createUser(UserRole.USER);
      perform(
              delete("/api/npm/packages/{repo}/{package}", repoName, "plain-package")
                  .header(AUTHORIZATION, bearerToken(user, Duration.ofMinutes(30))))
          .andExpect(status().isUnauthorized());
      // Anonymous callers cannot tell a missing repo from a private one (RPS-887).
      perform(get("/api/npm/packages/missing-repo")).andExpect(status().isUnauthorized());
      perform(
              get("/api/npm/packages/missing-repo")
                  .header(AUTHORIZATION, bearerToken(user, Duration.ofMinutes(30))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
      perform(get("/api/npm/packages/{repo}/missing/versions/1.0.0", repoName))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    void deletesVersionAndPackageRowsAndStorageWhileKeepingSiblings() throws Exception {
      perform(
              delete(
                      "/api/npm/packages/{repo}/{package}/versions/{version}",
                      repoName,
                      "plain-package",
                      "2.0.0-next.1")
                  .header(AUTHORIZATION, bearerToken(admin, Duration.ofMinutes(30))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("packageVersionDeleted"))
          .andExpect(jsonPath("$.data").value(nullValue()));
      final var packageInfo =
          npmPackageRepository
              .findByRepoIdAndScopeAndName(repo.getId(), null, "plain-package")
              .orElseThrow();
      org.assertj.core.api.Assertions.assertThat(
              packageVersionRepository.findByNpmPackageId(packageInfo.getId()))
          .extracting("version")
          .containsExactly("1.0.0");

      perform(
              delete("/api/npm/packages/{repo}/{package}", repoName, "plain-package")
                  .header(AUTHORIZATION, bearerToken(admin, Duration.ofMinutes(30))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageDeleted"));
      org.assertj.core.api.Assertions.assertThat(
              npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), null, "plain-package"))
          .isEmpty();
      perform(
              delete("/api/npm/packages/{repo}/{package}", repoName, "plain-package")
                  .header(AUTHORIZATION, bearerToken(admin, Duration.ofMinutes(30))))
          .andExpect(status().isNotFound());
    }
  }

  /**
   * The port-based handler mapping does not raise {@code HttpRequestMethodNotSupportedException}
   * for a verb the path does not map, so the request falls through to the static-resource handler
   * and fails with {@code NoResourceFoundException}, which {@code ErrorHandler} answers with 404
   * {@code itemNotFound} (RPS-849). Publishing verbs on these read/delete routes must never reach
   * the generic error handler and answer 500 (RPS-900).
   */
  @Nested
  @DisplayName("unsupported verbs")
  class UnsupportedVerbs {

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unsupportedRequests")
    @DisplayName("answers 404 itemNotFound in the standard error envelope")
    void answersClientErrorWithStandardEnvelope(final HttpMethod method, final String pathTemplate)
        throws Exception {
      final var path = pathTemplate.formatted(repoName);

      perform(request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("itemNotFound"))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(jsonPath("$.text").value("The requested item is not found."));
    }

    @ParameterizedTest(name = "{0} {1} with an admin token")
    @MethodSource("unsupportedRequests")
    @DisplayName("answers the same 404 to an authenticated admin and leaves the package alone")
    void answersSameErrorToAdminAndKeepsPackage(final HttpMethod method, final String pathTemplate)
        throws Exception {
      final var path = pathTemplate.formatted(repoName);

      perform(
              request(method, path)
                  .header(AUTHORIZATION, bearerToken(admin, Duration.ofMinutes(30)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("itemNotFound"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));

      org.assertj.core.api.Assertions.assertThat(
              npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), null, "plain-package"))
          .isPresent();
    }

    static Stream<Arguments> unsupportedRequests() {
      return Stream.of(
              "/api/npm/packages/%s",
              "/api/npm/packages/%s/plain-package",
              "/api/npm/packages/%s/plain-package/versions/1.0.0",
              "/api/npm/packages/%s/tools/scoped-package/versions/1.0.0")
          .flatMap(
              path ->
                  Stream.of(HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH)
                      .map(method -> Arguments.of(method, path)));
    }
  }
}
