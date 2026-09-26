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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Full-stack integration coverage for the NuGet package-management API. */
@DisplayName("NuGetPackageController /api/nuget/packages/*")
class NuGetPackageControllerIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;
  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetStorageService nugetStorageService;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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
    this.publish(repoName, id, version, null);
  }

  private void publish(
      final String repoName, final String id, final String version, final Instant publishedAt) {
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
          "project_url", "repository_url", "readme", "dependencies", "created_at")
        values (?, ?, ?, ?, true, coalesce(?, current_timestamp), 0, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), current_timestamp)
        """,
        UUID.randomUUID(),
        packageId,
        version,
        version.contains("-"),
        publishedAt == null ? null : Timestamp.from(publishedAt),
        "NuGet fixture",
        "integration fixture",
        "Repsy",
        "searchable fixture",
        "https://example.test/license",
        "https://example.test/project",
        "https://example.test/repository.git",
        "# NuGet fixture",
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
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearerTokenFor(user);
      NuGetPackageControllerIT.this.publish(repo.getName(), "Fixture.Package", "1.0.0");
      NuGetPackageControllerIT.this.publish(repo.getName(), "Fixture.Package", "1.0.1-beta.1");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .param("q", "fixture")
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
          .andExpect(jsonPath("$.data.repositoryUrl").value("https://example.test/repository.git"))
          .andExpect(jsonPath("$.data.readme").value("# NuGet fixture"))
          .andExpect(jsonPath("$.data.dependencies", hasSize(0)));
    }

    @Test
    @DisplayName("rejects missing, malformed, expired, and unknown-user authorization")
    void rejectsInvalidAuthorization() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
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
          .andExpect(status().isUnauthorized());
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
  @DisplayName("a package with unlisted versions (RPS-1580)")
  class UnlistedVersions {

    private void unlist(final String repoName, final String id, final String version) {
      final var updated =
          NuGetPackageControllerIT.this.jdbcTemplate.update(
              """
              update "public"."nuget_package_version" set "is_listed" = false
              where "version" = ? and "package_id" in (
                select p."id" from "public"."nuget_package" p
                where p."package_id" = ? and p."repo_id" = ?)
              """,
              version,
              id,
              NuGetPackageControllerIT.this.repoTxService.getRepoByName(repoName).getId());
      assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("the detail of a package whose only version is unlisted agrees with the list")
    void unlistedOnlyPackageHasADetail() throws Exception {
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearerTokenFor(user);
      NuGetPackageControllerIT.this.publish(repo.getName(), "Only.Unlisted", "1.0.0");
      this.unlist(repo.getName(), "only.unlisted", "1.0.0");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].packageId").value("only.unlisted"));

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}", repo.getName(), "only.unlisted")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetPackageFetched"))
          .andExpect(jsonPath("$.data.packageId").value("only.unlisted"))
          .andExpect(jsonPath("$.data.latestVersion").value("1.0.0"))
          .andExpect(jsonPath("$.data.title").value("NuGet fixture"))
          .andExpect(jsonPath("$.data.description").value("integration fixture"))
          .andExpect(jsonPath("$.data.totalDownloads").value(0));
    }

    @Test
    @DisplayName("the newest listed version describes the package, an unlisted newer one does not")
    void newestListedVersionWins() throws Exception {
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearerTokenFor(user);
      final var now = Instant.now();
      NuGetPackageControllerIT.this.publish(
          repo.getName(), "Mixed.Package", "1.0.0", now.minusSeconds(300));
      NuGetPackageControllerIT.this.publish(
          repo.getName(), "Mixed.Package", "2.0.0", now.minusSeconds(200));
      NuGetPackageControllerIT.this.publish(
          repo.getName(), "Mixed.Package", "3.0.0", now.minusSeconds(100));
      this.unlist(repo.getName(), "mixed.package", "3.0.0");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}", repo.getName(), "mixed.package")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.latestVersion").value("2.0.0"));
    }

    @Test
    @DisplayName("with every version unlisted, the newest published one describes the package")
    void newestUnlistedVersionWhenNoneIsListed() throws Exception {
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearerTokenFor(user);
      final var now = Instant.now();
      NuGetPackageControllerIT.this.publish(
          repo.getName(), "All.Unlisted", "1.0.0", now.minusSeconds(200));
      NuGetPackageControllerIT.this.publish(
          repo.getName(), "All.Unlisted", "2.0.0", now.minusSeconds(100));
      this.unlist(repo.getName(), "all.unlisted", "1.0.0");
      this.unlist(repo.getName(), "all.unlisted", "2.0.0");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}/{id}", repo.getName(), "all.unlisted")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.latestVersion").value("2.0.0"));
    }
  }

  @Nested
  @DisplayName("DELETE endpoints")
  class DeleteEndpoints {

    @Test
    @DisplayName("deletes a version and package, returning the correct deleted item")
    void deletesVersionAndPackage() throws Exception {
      final var admin =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.ADMIN);
      final var repo = NuGetPackageControllerIT.this.createRepo(RepoType.NUGET, true);
      final var token = NuGetPackageControllerIT.this.bearerTokenFor(admin);
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
      final var user =
          NuGetPackageControllerIT.this.createUser(uniqueUsername("nuget"), UserRole.USER);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              delete("/api/nuget/packages/{repo}/{id}", repo.getName(), "missing")
                  .with(apiPort())
                  .header(AUTHORIZATION, NuGetPackageControllerIT.this.bearerTokenFor(user)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.msgId").value("accessDenied"))
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
      final var user = it.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = it.createRepo(RepoType.NUGET, true);

      it.publish(repo.getName(), "Fixture.Package", "1.0.0");
      it.publish(repo.getName(), "Fixture.Package", "1.0.1-beta.1");

      return new Seed(repo, it.bearerTokenFor(user));
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
    @ValueSource(
        strings = {
          "/api/nuget/packages/{repo}",
          "/api/nuget/packages/{repo}/fixture.package/versions"
        })
    @DisplayName("filters by q only: query, its old name, is an unknown parameter")
    void filtersByQOnly(final String path) throws Exception {
      final var seed = this.seed();

      PagingAssertions.expectFilterIsQ(
          this.list(seed, path, "page", "0"),
          this.list(seed, path, "query", PagingAssertions.NO_MATCH),
          this.list(seed, path, "q", PagingAssertions.NO_MATCH));
    }

    private static Matcher<Iterable<? extends String>> inOrder(final List<String> values) {
      return contains(values.toArray(String[]::new));
    }

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private Seed seedPackages(final String... packageIds) {
      final var it = NuGetPackageControllerIT.this;
      final var user = it.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = it.createRepo(RepoType.NUGET, true);

      for (final var packageId : packageIds) {
        it.publish(repo.getName(), packageId, "1.0.0");
      }

      return new Seed(repo, it.bearerTokenFor(user));
    }

    /** Publishes 1.0.0, 2.0.0 and 3.0.0 of one package, oldest first, one day apart. */
    private Seed seedDatedVersions() {
      final var it = NuGetPackageControllerIT.this;
      final var user = it.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = it.createRepo(RepoType.NUGET, true);

      it.publish(repo.getName(), "Fixture.Package", "2.0.0", EPOCH.plus(Duration.ofDays(1)));
      it.publish(repo.getName(), "Fixture.Package", "3.0.0", EPOCH.plus(Duration.ofDays(2)));
      it.publish(repo.getName(), "Fixture.Package", "1.0.0", EPOCH);

      return new Seed(repo, it.bearerTokenFor(user));
    }

    private ResultActions listPage(
        final Seed seed, final String path, final String page, final String size, final String sort)
        throws Exception {
      final var request =
          get(path, seed.repo().getName())
              .param("page", page)
              .param("size", size)
              .with(apiPort())
              .header(AUTHORIZATION, seed.token());

      return NuGetPackageControllerIT.this.mockMvc.perform(
          sort == null ? request : request.param("sort", sort));
    }

    @Test
    @DisplayName("pages through the package list instead of returning the first page again")
    void packageListHonoursPage() throws Exception {
      final var seed = this.seedPackages("pkg.c", "pkg.a", "pkg.b");

      this.listPage(seed, PACKAGES, "0", "2", null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[*].packageId", inOrder(List.of("pkg.a", "pkg.b"))))
          .andExpect(jsonPath("$.data.page.number").value(0))
          .andExpect(jsonPath("$.data.page.totalElements").value(3));

      this.listPage(seed, PACKAGES, "1", "2", null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[*].packageId", inOrder(List.of("pkg.c"))))
          .andExpect(jsonPath("$.data.page.number").value(1));

      this.listPage(seed, PACKAGES, "2", "2", null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @ParameterizedTest(name = "sort={0}")
    @MethodSource("packageSortOrders")
    @DisplayName("orders the package list by packageId in the requested direction")
    void packageListHonoursSort(final String sort, final List<String> expected) throws Exception {
      final var seed = this.seedPackages("pkg.b", "pkg.c", "pkg.a");

      this.listPage(seed, PACKAGES, "0", "10", sort)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[*].packageId", inOrder(expected)));
    }

    static Stream<Arguments> packageSortOrders() {
      return Stream.of(
          Arguments.of(null, List.of("pkg.a", "pkg.b", "pkg.c")),
          Arguments.of("packageId,asc", List.of("pkg.a", "pkg.b", "pkg.c")),
          Arguments.of("packageId,desc", List.of("pkg.c", "pkg.b", "pkg.a")));
    }

    @Test
    @DisplayName("sorts the package list by packageId across pages")
    void packageListSortsAcrossPages() throws Exception {
      final var seed = this.seedPackages("pkg.b", "pkg.c", "pkg.a");

      this.listPage(seed, PACKAGES, "0", "2", "packageId,desc")
          .andExpect(jsonPath("$.data.content[*].packageId", inOrder(List.of("pkg.c", "pkg.b"))));
      this.listPage(seed, PACKAGES, "1", "2", "packageId,desc")
          .andExpect(jsonPath("$.data.content[*].packageId", inOrder(List.of("pkg.a"))));
    }

    @ParameterizedTest(name = "sort={0}")
    @MethodSource("versionSortOrders")
    @DisplayName("orders the version list by publishedAt in the requested direction")
    void versionListHonoursSort(final String sort, final List<String> expected) throws Exception {
      final var seed = this.seedDatedVersions();

      this.listPage(seed, VERSIONS, "0", "10", sort)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[*].version", inOrder(expected)));
    }

    static Stream<Arguments> versionSortOrders() {
      return Stream.of(
          Arguments.of(null, List.of("3.0.0", "2.0.0", "1.0.0")),
          Arguments.of("publishedAt,desc", List.of("3.0.0", "2.0.0", "1.0.0")),
          Arguments.of("publishedAt,asc", List.of("1.0.0", "2.0.0", "3.0.0")),
          Arguments.of("version,desc", List.of("3.0.0", "2.0.0", "1.0.0")),
          Arguments.of("version,asc", List.of("1.0.0", "2.0.0", "3.0.0")));
    }

    @Test
    @DisplayName("pages through the version list in the requested order")
    void versionListHonoursPageAndSort() throws Exception {
      final var seed = this.seedDatedVersions();

      this.listPage(seed, VERSIONS, "0", "2", "publishedAt,asc")
          .andExpect(jsonPath("$.data.content[*].version", inOrder(List.of("1.0.0", "2.0.0"))))
          .andExpect(jsonPath("$.data.page.totalElements").value(3));
      this.listPage(seed, VERSIONS, "1", "2", "publishedAt,asc")
          .andExpect(jsonPath("$.data.content[*].version", inOrder(List.of("3.0.0"))));
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

  @Nested
  @DisplayName("version search of GET /api/nuget/packages/{repo}/{id}/versions")
  class VersionSearch {

    private static final String VERSIONS = "/api/nuget/packages/{repo}/fixture.package/versions";

    private record Seed(RepoInfo repo, String token) {}

    /** Seven versions of one package, and one version of another that also contains "2.". */
    private Seed seed() {
      final var it = NuGetPackageControllerIT.this;
      final var user = it.createUser(uniqueUsername("nuget"), UserRole.USER);
      final var repo = it.createRepo(RepoType.NUGET, true);

      for (final var version :
          List.of("1.0.0", "1.0.1-Beta.1", "1.1.0", "2.0.0", "2.1.0", "2.2.0", "3.0.0")) {
        it.publish(repo.getName(), "Fixture.Package", version);
      }
      it.publish(repo.getName(), "Other.Package", "2.0.0");

      return new Seed(repo, it.bearerTokenFor(user));
    }

    private ResultActions list(final Seed seed, final String... params) throws Exception {
      var request =
          get(VERSIONS, seed.repo().getName()).with(apiPort()).header(AUTHORIZATION, seed.token());
      for (int i = 0; i < params.length; i += 2) {
        request = request.param(params[i], params[i + 1]);
      }
      return NuGetPackageControllerIT.this.mockMvc.perform(request);
    }

    private static Matcher<Iterable<? extends String>> inOrder(final String... values) {
      return contains(values);
    }

    @Test
    @DisplayName("finds the versions that contain the text, ignoring case")
    void findsVersionsCaseInsensitively() throws Exception {
      final var seed = this.seed();

      this.list(seed, "q", "beta", "sort", "version,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[*].version", inOrder("1.0.1-Beta.1")))
          .andExpect(jsonPath("$.data.page.totalElements").value(1));
      this.list(seed, "q", "BETA")
          .andExpect(jsonPath("$.data.content[*].version", inOrder("1.0.1-Beta.1")));
      this.list(seed, "q", "1.0.", "sort", "version,asc")
          .andExpect(jsonPath("$.data.content[*].version", inOrder("1.0.0", "1.0.1-Beta.1")))
          .andExpect(jsonPath("$.data.page.totalElements").value(2));
      // A substring anywhere in the version counts, not only a prefix: "1.0" is inside "2.1.0".
      this.list(seed, "q", "1.0", "sort", "version,asc")
          .andExpect(
              jsonPath(
                  "$.data.content[*].version", inOrder("1.0.0", "1.0.1-Beta.1", "1.1.0", "2.1.0")));
    }

    @Test
    @DisplayName("answers an empty page when no version contains the text")
    void missAnswersEmptyPage() throws Exception {
      this.list(this.seed(), "q", "zzz")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)))
          .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("lists every version of the package for an empty or absent query")
    void emptyQueryListsEverything() throws Exception {
      final var seed = this.seed();

      this.list(seed, "q", "", "size", "20")
          .andExpect(jsonPath("$.data.content", hasSize(7)))
          .andExpect(jsonPath("$.data.page.totalElements").value(7));
      this.list(seed, "size", "20")
          .andExpect(jsonPath("$.data.content", hasSize(7)))
          .andExpect(jsonPath("$.data.page.totalElements").value(7));
    }

    @Test
    @DisplayName("applies the search before paging, so it spans all pages")
    void searchSpansAllPages() throws Exception {
      final var seed = this.seed();

      this.list(seed, "q", "2.", "size", "2", "page", "0", "sort", "version,asc")
          .andExpect(jsonPath("$.data.content[*].version", inOrder("2.0.0", "2.1.0")))
          .andExpect(jsonPath("$.data.page.totalElements").value(3))
          .andExpect(jsonPath("$.data.page.totalPages").value(2));
      this.list(seed, "q", "2.", "size", "2", "page", "1", "sort", "version,asc")
          .andExpect(jsonPath("$.data.content[*].version", inOrder("2.2.0")));
    }

    @Test
    @DisplayName("takes the LIKE wildcards of the text literally")
    void wildcardsAreLiteral() throws Exception {
      final var seed = this.seed();

      this.list(seed, "q", "%").andExpect(jsonPath("$.data.page.totalElements").value(0));
      this.list(seed, "q", "_.0.0").andExpect(jsonPath("$.data.page.totalElements").value(0));
    }
  }
}
