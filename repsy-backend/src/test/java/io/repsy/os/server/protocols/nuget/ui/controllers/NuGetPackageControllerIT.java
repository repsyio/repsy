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
package io.repsy.os.server.protocols.nuget.ui.controllers;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.PagingAssertions;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration coverage for the NuGet package-management API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("NuGetPackageController /api/nuget/packages/*")
class NuGetPackageControllerIT {

  private static final int API_PORT = 8080;
  private static final String PASSWORD = "Password1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", NuGetPackageControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-nuget-api-it").toString();
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private NuGetStorageService nugetStorageService;
  @PersistenceContext private EntityManager entityManager;

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private User createUser(final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(PASSWORD, salt);
    final var info = this.userTxService.create(unique("nuget"), role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private String bearer(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private RepoInfo createRepo(final RepoType type, final boolean privateRepo) {
    final var repo = this.repoTxService.createRepo(unique("nugetrepo"), type, privateRepo, null);
    if (type == RepoType.NUGET) {
      this.nugetStorageService.createRepo(repo.getId());
    }
    this.entityManager.flush();
    return repo;
  }

  private void publish(final String repoName, final String id, final String version) {
    final var repo = this.repoTxService.getRepoByName(repoName);
    final var packageEntity =
        this.nugetPackageRepository
            .findByRepoIdAndPackageIdIgnoreCase(repo.getId(), id)
            .orElseGet(
                () -> {
                  final var created = new NuGetPackage();
                  created.setRepo(
                      this.entityManager.getReference(
                          io.repsy.os.shared.repo.entities.Repo.class, repo.getId()));
                  created.setPackageId(id.toLowerCase(java.util.Locale.ROOT));
                  return this.nugetPackageRepository.save(created);
                });
    final var packageId = packageEntity.getId();
    this.entityManager.flush();
    this.jdbcTemplate.update(
        """
        insert into "public"."nuget_package_version"
          ("id", "package_id", "version", "is_prerelease", "is_listed", "published_at",
          "download_count", "title", "description", "authors", "tags", "license_url",
          "project_url", "dependencies", "created_at")
        values (?, ?, ?, ?, true, current_timestamp, 0, ?, ?, ?, ?, ?, ?, cast(? as jsonb), current_timestamp)
        """,
        UUID.randomUUID(),
        packageId,
        version,
        version.contains("-"),
        "NuGet fixture",
        "integration fixture",
        "Repsy",
        "searchable fixture",
        "https://example.test/license",
        "https://example.test/project",
        "[]");
  }

  private static String nuspec(final String id, final String version) {
    return "<package><metadata><id>"
        + id
        + "</id><version>"
        + version
        + "</version><title>NuGet fixture</title><authors>Repsy</authors>"
        + "<description>integration fixture</description><tags>searchable fixture</tags>"
        + "<licenseUrl>https://example.test/license</licenseUrl>"
        + "<projectUrl>https://example.test/project</projectUrl>"
        + "</metadata></package>";
  }

  @Nested
  @DisplayName("GET endpoints")
  class GetEndpoints {

    @Test
    @DisplayName("returns full search, package, version-list, and version DTOs")
    void returnsPackageViews() throws Exception {
      final var user = NuGetPackageControllerIT.this.createUser(UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearer(user);
      NuGetPackageControllerIT.this.publish(repo.getName(), "Fixture.Package", "1.0.0");
      NuGetPackageControllerIT.this.publish(repo.getName(), "Fixture.Package", "1.0.1-beta.1");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .param("query", "fixture")
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("nugetPackagesFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].packageId").value("fixture.package"))
          .andExpect(jsonPath("$.data.content[0].latestVersion").value("1.0.0"))
          .andExpect(jsonPath("$.data.content[0].description").value("integration fixture"))
          .andExpect(jsonPath("$.data.content[0].totalDownloads").value(0))
          .andExpect(jsonPath("$.data.page.size").value(10))
          .andExpect(jsonPath("$.data.page.totalElements").value(1));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}", repo.getName(), "fixture.package")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetPackageFetched"))
          .andExpect(jsonPath("$.data.packageId").value("fixture.package"))
          .andExpect(jsonPath("$.data.latestVersion").value("1.0.0"))
          .andExpect(jsonPath("$.data.title").value("NuGet fixture"))
          .andExpect(jsonPath("$.data.authors").value("Repsy"))
          .andExpect(jsonPath("$.data.tags").value("searchable fixture"))
          .andExpect(jsonPath("$.data.totalDownloads").value(0));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}/versions", repo.getName(), "FIXTURE.PACKAGE")
                  .param("size", "10")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetVersionsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(2)))
          .andExpect(jsonPath("$.data.content[0].version").value("1.0.0"))
          .andExpect(jsonPath("$.data.content[1].version").value("1.0.1-beta.1"))
          .andExpect(jsonPath("$.data.content[1].prerelease").value(true))
          .andExpect(jsonPath("$.data.content[0].listed").value(true));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/nuget/packages/{repo}/{id}/{version}",
                      repo.getName(),
                      "fixture.package",
                      "1.0.0")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetVersionFetched"))
          .andExpect(jsonPath("$.data.packageId").value("fixture.package"))
          .andExpect(jsonPath("$.data.version").value("1.0.0"))
          .andExpect(jsonPath("$.data.dependencies", hasSize(0)));
    }

    @Test
    @DisplayName("rejects missing, malformed, expired, and unknown-user authorization")
    void rejectsInvalidAuthorization() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var user = NuGetPackageControllerIT.this.createUser(UserRole.USER);
      final var expired =
          AuthUtils.AUTH_BEARER
              + NuGetPackageControllerIT.this.jwtUtils.createPanelAccessToken(
                  user.getId(), user.getUsername(), Duration.ofSeconds(-30));
      final var unknown =
          AuthUtils.AUTH_BEARER
              + NuGetPackageControllerIT.this.jwtUtils.createPanelAccessToken(
                  UUID.randomUUID(), unique("missing-user"), Duration.ofMinutes(30));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(get("/api/nuget/packages/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isUnauthorized());
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, "Bearer garbage"))
          .andExpect(status().isUnauthorized());
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, expired))
          .andExpect(status().isUnauthorized());
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, unknown))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("allows anonymous reads only for public repositories")
    void publicReadAccess() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, false);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(get("/api/nuget/packages/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetPackagesFetched"));
    }

    @Test
    @DisplayName("returns not found for an unknown package and version")
    void unknownItemsAreNotFound() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, false);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}", repo.getName(), "missing").with(apiPort()))
          .andExpect(status().isNotFound());
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}/{version}", repo.getName(), "missing", "1.0.0")
                  .with(apiPort()))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("DELETE endpoints")
  class DeleteEndpoints {

    @Test
    @DisplayName("deletes a version and package, returning the correct deleted item")
    void deletesVersionAndPackage() throws Exception {
      final var admin = NuGetPackageControllerIT.this.createUser(UserRole.ADMIN);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearer(admin);
      NuGetPackageControllerIT.this.publish(repo.getName(), "Delete.Me", "1.0.0");
      NuGetPackageControllerIT.this.publish(repo.getName(), "Delete.Me", "2.0.0");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/nuget/packages/{repo}/{id}/{version}",
                      repo.getName(),
                      "Delete.Me",
                      "1.0.0")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("nugetVersionDeleted"))
          .andExpect(jsonPath("$.data").value("VERSION"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              delete("/api/nuget/packages/{repo}/{id}", repo.getName(), "Delete.Me")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetPackageDeleted"))
          .andExpect(jsonPath("$.data").value("PACKAGE"));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              delete("/api/nuget/packages/{repo}/{id}", repo.getName(), "Delete.Me")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("does not allow a read-only caller to delete")
    void readOnlyCallerCannotDelete() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, false);
      final var user = NuGetPackageControllerIT.this.createUser(UserRole.USER);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              delete("/api/nuget/packages/{repo}/{id}", repo.getName(), "missing")
                  .with(apiPort())
                  .header(AUTHORIZATION, NuGetPackageControllerIT.this.bearer(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    @DisplayName("rejects unsupported methods on the package endpoint")
    void unsupportedMethodsAreRejected() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, false);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(post("/api/nuget/packages/{repo}", repo.getName()).with(apiPort()))
          // RPS-849 owns the unsupported-verb behavior; pin today's error-handler response.
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String PACKAGES = "/api/nuget/packages/{repo}";
    private static final String VERSIONS = "/api/nuget/packages/{repo}/fixture.package/versions";

    static Stream<String> endpoints() {
      return Stream.of(PACKAGES, VERSIONS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.concat(
          Stream.of(Arguments.of(PACKAGES, "packageId")),
          Stream.of("version", "publishedAt").map(property -> Arguments.of(VERSIONS, property)));
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private record Seed(RepoInfo repo, String token) {}

    private Seed seed() {
      final var it = NuGetPackageControllerIT.this;
      final var user = it.createUser(UserRole.USER);
      final var repo = it.createRepo(RepoType.NUGET, true);

      it.publish(repo.getName(), "Fixture.Package", "1.0.0");
      it.publish(repo.getName(), "Fixture.Package", "1.0.1-beta.1");

      return new Seed(repo, it.bearer(user));
    }

    private ResultActions list(
        final Seed seed, final String path, final String param, final String value)
        throws Exception {
      return NuGetPackageControllerIT.this.mockMvc.perform(
          get(path, seed.repo().getName())
              .param(param, value)
              .with(apiPort())
              .header(AUTHORIZATION, seed.token()));
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var seed = this.seed();

      this.list(seed, path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(seed, path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seed(), path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(this.list(this.seed(), path, param, value), param);
    }
  }
}
