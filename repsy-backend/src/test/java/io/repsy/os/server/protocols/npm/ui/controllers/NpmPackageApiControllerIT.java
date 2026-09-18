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
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.RepsyApplication;
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
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** End-to-end coverage for the npm package-management API. */
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
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String PASSWORD = "Password1!";

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
    try {
      return Files.createTempDirectory("repsy-npm-api-it").toString();
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
  }

  private User createUser(final UserRole role) {
    final var username = "npm859-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        """
        {
          "name": "%s",
          "dist-tags": {"%s": "%s"},
          "versions": {
            "%s": {
              "name": "%s",
              "version": "%s",
              "description": "integration fixture",
              "keywords": ["fixture", "npm"],
              "author": {"name": "Repsy", "email": "test@repsy.io"},
              "dependencies": {"left-pad": "1.3.0"},
              "dist": {
                "tarball": "http://localhost/%s-%s.tgz",
                "shasum": "abc",
                "integrity": "sha512-abc"
              }
            }
          },
          "_attachments": {
            "%s-%s.tgz": {
              "content_type": "application/octet-stream",
              "data": "%s",
              "length": %d
            }
          }
        }
        """
            .formatted(
                packagePath,
                tag,
                version,
                version,
                packagePath,
                version,
                packagePath,
                version,
                packageName,
                version,
                tarball,
                packagePath.length() + version.length());

    this.mockMvc
        .perform(
            put("/{repo}/{package}", this.repoName, packagePath)
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

  private String bearerToken() {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            this.admin.getId(), this.admin.getUsername(), Duration.ofMinutes(30));
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
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    return this.mockMvc.perform(request.with(apiPort()));
  }

  @Nested
  @DisplayName("read mappings")
  class Reads {

    @Test
    void listsPackagesAndSupportsNameAndScopeFilters() throws Exception {
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}", NpmPackageApiControllerIT.this.repoName))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("packagesFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Packages are fetched."))
          .andExpect(jsonPath("$.data.content", hasSize(2)))
          .andExpect(jsonPath("$.data.page.size").value(10))
          .andExpect(jsonPath("$.data.page.totalElements").value(2))
          .andExpect(jsonPath("$.data.content[0].name").value("scoped-package"))
          .andExpect(jsonPath("$.data.content[0].scope").value("tools"))
          .andExpect(jsonPath("$.data.content[0].latestVersion").value("1.0.0"));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                      "/api/npm/packages/{repo}/{ignoredScope}",
                      NpmPackageApiControllerIT.this.repoName,
                      "ignored")
                  .param("name", "plain"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].name").value("plain-package"));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/scope/{scope}",
                  NpmPackageApiControllerIT.this.repoName,
                  "tools"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].scope").value("tools"));
    }

    @Test
    void returnsFullVersionDetailsVersionsAndTagsForScopedAndUnscopedPackages() throws Exception {
      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/{package}/versions/{version}",
                  NpmPackageApiControllerIT.this.repoName,
                  "plain-package",
                  "1.0.0"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("packageVersionFetched"))
          .andExpect(jsonPath("$.data.packageName").value("plain-package"))
          .andExpect(jsonPath("$.data.distributionTags", hasSize(1)))
          .andExpect(jsonPath("$.data.createdAt").value(notNullValue()));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/{scope}/{package}",
                  NpmPackageApiControllerIT.this.repoName,
                  "tools",
                  "scoped-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.scopeName").value("tools"))
          .andExpect(jsonPath("$.data.packageName").value("scoped-package"));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                      "/api/npm/packages/{repo}/package/{package}/versions",
                      NpmPackageApiControllerIT.this.repoName,
                      "plain-package")
                  .param("version", "2.0"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].version").value("2.0.0-next.1"))
          .andExpect(jsonPath("$.data.content[0].deprecated").value(false));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/package/{package}/tags",
                  NpmPackageApiControllerIT.this.repoName,
                  "plain-package"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data", hasSize(2)))
          .andExpect(jsonPath("$.data[0].tagName").value("latest"));
    }
  }

  @Nested
  @DisplayName("authentication and deletion")
  class SecurityAndDeletes {

    @Test
    void rejectsDeleteWithoutManageAuthorizationAndAllowsAuthorizedVersionDelete()
        throws Exception {
      NpmPackageApiControllerIT.this
          .perform(
              delete(
                  "/api/npm/packages/{repo}/{package}",
                  NpmPackageApiControllerIT.this.repoName,
                  "plain-package"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(jsonPath("$.data").value("unAuthorized"));

      NpmPackageApiControllerIT.this
          .perform(
              delete(
                      "/api/npm/packages/{repo}/{package}/versions/{version}",
                      NpmPackageApiControllerIT.this.repoName,
                      "plain-package",
                      "2.0.0-next.1")
                  .header(AUTHORIZATION, NpmPackageApiControllerIT.this.bearerToken()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("packageVersionDeleted"))
          .andExpect(jsonPath("$.data").value(nullValue()));

      NpmPackageApiControllerIT.this
          .perform(
              get(
                  "/api/npm/packages/{repo}/{package}/versions/{version}",
                  NpmPackageApiControllerIT.this.repoName,
                  "plain-package",
                  "2.0.0-next.1"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)));
    }

    @Test
    void returnsEmptyResultsForMissingRepositoryAndPackageAndErrorsForUnsupportedVerb()
        throws Exception {
      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/missing-repo"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)));

      NpmPackageApiControllerIT.this
          .perform(get("/api/npm/packages/{repo}/missing", NpmPackageApiControllerIT.this.repoName))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)));

      NpmPackageApiControllerIT.this
          .perform(
              put(
                      "/api/npm/packages/{repo}/{package}",
                      NpmPackageApiControllerIT.this.repoName,
                      "plain-package")
                  .header(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
          .andExpect(status().isInternalServerError());
    }
  }
}
