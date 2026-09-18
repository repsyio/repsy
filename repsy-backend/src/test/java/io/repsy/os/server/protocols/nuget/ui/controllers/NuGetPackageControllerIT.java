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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetPackageServiceImpl;
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
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end coverage for the NuGet package-management API. */
@Testcontainers
@AutoConfigureMockMvc
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
      return Files.createTempDirectory("repsy-nuget-it").toString();
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private NuGetStorageService nugetStorageService;
  @Autowired private NuGetPackageServiceImpl nugetPackageService;
  @Autowired private JdbcTemplate jdbcTemplate;

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
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private String bearer(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private RepoInfo createRepo(final boolean privateRepo) {
    final var repo =
        this.repoTxService.createRepo(unique("nugetrepo"), RepoType.NUGET, privateRepo, null);
    this.nugetStorageService.createRepo(repo.getId());
    return repo;
  }

  /** Seeds metadata through the transactional package service used by the NuGet push path. */
  private void publish(final RepoInfo repo, final String id, final String version) {
    final var packageId = this.nugetPackageService.findOrCreatePackage(repo, id);
    final var publishedAt =
        version.contains("beta")
            ? Instant.parse("2026-01-01T00:00:00Z")
            : Instant.parse("2026-01-02T00:00:00Z");
    this.jdbcTemplate.update(
        "insert into nuget_package_version "
            + "(id, package_id, version, is_prerelease, is_listed, published_at, download_count, "
            + "title, description, authors, tags, icon_url, license_url, project_url, "
            + "repository_url, readme, dependencies, created_at) "
            + "values (?, ?, ?, ?, true, ?, 0, ?, ?, ?, ?, null, ?, ?, null, null, ?::jsonb, ?)",
        UUID.randomUUID(),
        packageId,
        version,
        version.contains("-"),
        java.sql.Timestamp.from(publishedAt),
        "NuGet fixture",
        "integration fixture",
        "Repsy",
        "searchable fixture",
        "https://example.test/license",
        "https://example.test/project",
        null,
        java.sql.Timestamp.from(Instant.now()));
  }

  private static String nuspec(final String id, final String version) {
    return "<package><metadata><id>"
        + id
        + "</id><version>"
        + version
        + "</version>"
        + "<title>NuGet fixture</title><authors>Repsy</authors>"
        + "<description>integration fixture</description><tags>searchable fixture</tags>"
        + "<licenseUrl>https://example.test/license</licenseUrl>"
        + "<projectUrl>https://example.test/project</projectUrl>"
        + "</metadata></package>";
  }

  @Nested
  @DisplayName("GET package endpoints")
  class GetEndpoints {

    @Test
    @DisplayName("returns search, package, versions, and version detail with full DTO fields")
    void returnsPackageViews() throws Exception {
      final var user = NuGetPackageControllerIT.this.createUser(UserRole.USER);
      final var repo = NuGetPackageControllerIT.this.createRepo(true);
      final var token = NuGetPackageControllerIT.this.bearer(user);
      NuGetPackageControllerIT.this.publish(repo, "Fixture.Package", "1.0.0");
      NuGetPackageControllerIT.this.publish(repo, "Fixture.Package", "1.0.1-beta.1");

      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("nugetPackagesFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("nugetPackagesFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].packageId").value("fixture.package"))
          .andExpect(jsonPath("$.data.content[0].latestVersion").value("1.0.0"))
          .andExpect(jsonPath("$.data.content[0].description").value("integration fixture"))
          .andExpect(jsonPath("$.data.content[0].totalDownloads").value(0));

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
                      "FIXTURE.PACKAGE",
                      "1.0.0")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetVersionFetched"))
          .andExpect(jsonPath("$.data.packageId").value("FIXTURE.PACKAGE"))
          .andExpect(jsonPath("$.data.version").value("1.0.0"))
          .andExpect(jsonPath("$.data.dependencies", hasSize(0)));
    }

    @Test
    @DisplayName("rejects missing, malformed, expired, and unknown-user authorization")
    void rejectsInvalidAuthorization() throws Exception {
      final var repo = NuGetPackageControllerIT.this.createRepo(true);
      final var user = NuGetPackageControllerIT.this.createUser(UserRole.USER);
      final var expired =
          AuthUtils.AUTH_BEARER
              + NuGetPackageControllerIT.this.jwtUtils.createTokenWithDuration(
                  user.getId(), user.getUsername(), Duration.ofSeconds(-30));
      final var unknown =
          AuthUtils.AUTH_BEARER
              + NuGetPackageControllerIT.this.jwtUtils.createTokenWithDuration(
                  UUID.randomUUID(), unique("unknown"), Duration.ofMinutes(30));

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
          .andExpect(status().isForbidden());
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(
              get("/api/nuget/packages/{repo}", repo.getName())
                  .with(apiPort())
                  .header(AUTHORIZATION, expired))
          .andExpect(status().isForbidden());
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
      final var repo = NuGetPackageControllerIT.this.createRepo(false);
      NuGetPackageControllerIT.this
          .mockMvc
          .perform(get("/api/nuget/packages/{repo}", repo.getName()).with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("nugetPackagesFetched"));
    }
  }

  @Nested
  @DisplayName("DELETE package endpoints")
  class DeleteEndpoints {

    @Test
    @DisplayName("deletes one version and then the remaining package, including storage")
    void deletesVersionAndPackage() throws Exception {
      final var admin = NuGetPackageControllerIT.this.createUser(UserRole.ADMIN);
      final var repo = NuGetPackageControllerIT.this.createRepo(true);
      final var token = NuGetPackageControllerIT.this.bearer(admin);
      NuGetPackageControllerIT.this.publish(repo, "Delete.Me", "1.0.0");
      NuGetPackageControllerIT.this.publish(repo, "Delete.Me", "2.0.0");

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
      final var repo = NuGetPackageControllerIT.this.createRepo(false);
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
  }
}
