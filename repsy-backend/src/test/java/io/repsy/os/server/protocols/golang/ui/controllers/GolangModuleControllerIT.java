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
package io.repsy.os.server.protocols.golang.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Full-stack integration tests for the Go module-management API. */
@DisplayName("GolangModuleController /api/go/modules/*")
class GolangModuleControllerIT extends AbstractIntegrationTest {

  private static final int PROTOCOL_PORT = 9090;
  private static final String MODULE = "io.repsy/hello-world";
  private static final String V2_MODULE = "example.com/mod/v2";
  private static final String UPPERCASE_MODULE = "example.com/Upper/Module";

  @Autowired private RepoTxService repoTxService;
  @Autowired private GolangApiFacade golangApiFacade;

  private static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private String createRepo(final String name, final boolean privateRepo) {
    final var repoInfo =
        this.repoTxService.createRepo(
            name, io.repsy.protocols.shared.repo.dtos.RepoType.GOLANG, privateRepo, null);
    this.entityManager.flush();
    assertThat(
            this.repoTxService.getRepoByNameAndType(
                name, io.repsy.protocols.shared.repo.dtos.RepoType.GOLANG))
        .isPresent();
    this.golangApiFacade.createRepo(repoInfo.getStorageKey());
    return name;
  }

  private void upload(final String repoName, final String version, final String token)
      throws Exception {
    this.upload(repoName, MODULE, version, token);
  }

  private void upload(
      final String repoName, final String module, final String version, final String token)
      throws Exception {
    this.mockMvc
        .perform(
            put("/{repo}/{module}/@v/{version}", repoName, module, version)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType("application/zip")
                .content(moduleZip(module, version)))
        .andExpect(status().isOk());
  }

  private static byte[] moduleZip(final String version) {
    return moduleZip(MODULE, version);
  }

  private static byte[] moduleZip(final String module, final String version) {
    try {
      final var output = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(output)) {
        final var prefix = module + "@" + version + "/";
        putEntry(zip, prefix + "go.mod", "module " + module + "\n\ngo 1.23\n");
        putEntry(zip, prefix + "hello.go", "package hello\n");
      }
      return output.toByteArray();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void putEntry(final ZipOutputStream zip, final String name, final String content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  @Test
  @DisplayName("lists, searches, versions, and details expose complete response shapes")
  void returnsModuleManagementData() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), true);
    this.upload(repo, "v1.0.0", this.protocolBearerTokenFor(user));
    this.upload(repo, "v1.2.0", this.protocolBearerTokenFor(user));

    this.mockMvc
        .perform(get("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.msgId").value("modulesFetched"))
        .andExpect(jsonPath("$.type").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.data.content[0].modulePath").value(MODULE))
        .andExpect(jsonPath("$.data.content[0].createdAt", notNullValue()))
        .andExpect(jsonPath("$.data.page.size").value(10))
        .andExpect(jsonPath("$.data.page.number").value(0))
        .andExpect(jsonPath("$.data.page.totalElements").value(1))
        .andExpect(jsonPath("$.data.page.totalPages").value(1))
        .andExpect(jsonPath("$.errorCode").value(nullValue()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/search", repo)
                .param("search", "HELLO")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].modulePath").value(MODULE));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleVersionsFetched"))
        .andExpect(jsonPath("$.data.content", hasSize(2)))
        .andExpect(jsonPath("$.data.content[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.data.content[0].version").value("v1.2.0"))
        .andExpect(jsonPath("$.data.content[0].goVersion").value("1.23"))
        .andExpect(jsonPath("$.data.content[0].createdAt", notNullValue()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleInfoFetched"))
        .andExpect(jsonPath("$.data.id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.data.modulePath").value(MODULE))
        .andExpect(jsonPath("$.data.latestVersion").value("v1.2.0"))
        .andExpect(jsonPath("$.data.createdAt", notNullValue()))
        .andExpect(jsonPath("$.data.versions", hasSize(2)))
        .andExpect(jsonPath("$.text").value("Module info fetched."));
  }

  @Test
  @DisplayName("supports Go module paths and semver variants while preserving DTO shapes")
  void supportsModulePathAndVersionVariants() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("variants"), false);
    this.upload(repo, V2_MODULE, "v2.0.0", this.protocolBearerTokenFor(user));
    this.upload(
        repo,
        UPPERCASE_MODULE,
        "v1.0.0-20240101120000-0123456789ab",
        this.protocolBearerTokenFor(user));
    this.upload(repo, MODULE, "v1.2.3+incompatible", this.protocolBearerTokenFor(user));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .param("page", "0")
                .param("size", "2")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.msgId").value("modulesFetched"))
        .andExpect(jsonPath("$.type").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content", hasSize(2)))
        .andExpect(jsonPath("$.data.content[*].id", hasSize(2)))
        .andExpect(jsonPath("$.data.content[*].createdAt", hasSize(2)))
        .andExpect(jsonPath("$.data.page.size").value(2))
        .andExpect(jsonPath("$.data.page.totalElements").value(3))
        .andExpect(jsonPath("$.data.page.totalPages").value(2))
        .andExpect(jsonPath("$.errorCode").value(nullValue()))
        .andExpect(jsonPath("$.text").value("Modules fetched."));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/search", repo)
                .param("search", "upper")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].modulePath").value(UPPERCASE_MODULE.toLowerCase()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .param("search", "incompatible")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.data.content", hasSize(1)))
        .andExpect(jsonPath("$.data.content[0].version").value("v1.2.3+incompatible"))
        .andExpect(jsonPath("$.data.content[0].goVersion").value("1.23"));
  }

  @Test
  @DisplayName("returns complete validation and not-found envelopes for module queries")
  void validatesQueriesAndNotFoundModules() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("validation"), true);

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .param("search", "missing")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.data.content", hasSize(0)))
        .andExpect(jsonPath("$.data.page.totalElements").value(0))
        .andExpect(jsonPath("$.errorCode").value(nullValue()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("modulePath"))
        .andExpect(jsonPath("$.errorCode", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.text").value("Incoming data couldn't be validated."));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("moduleNotFound"))
        .andExpect(jsonPath("$.text").value("Module not found."))
        .andExpect(jsonPath("$.errorCode", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", "does-not-exist")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("repoNotFound"));
  }

  @Test
  @DisplayName("enforces authentication, visibility, repository type, and management permissions")
  void enforcesAuthorizationAndRepositoryBoundaries() throws Exception {
    final var owner = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(owner);
    final var repo = this.createRepo(unique("private"), true);
    final var publicRepo = this.createRepo(unique("public"), false);
    this.upload(publicRepo, "v1.0.0", this.protocolBearerTokenFor(owner));

    this.mockMvc
        .perform(get("/api/go/modules/{repo}", repo).with(apiPort()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("unAuthorized"))
        .andExpect(jsonPath("$.errorCode", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .with(apiPort())
                .header(AUTHORIZATION, "Bearer malformed"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("ERROR"));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", publicRepo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].modulePath").value(MODULE));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .with(apiPort())
                .header(
                    AUTHORIZATION,
                    this.bearerTokenFor(this.createUser(uniqueUsername("gomod"), UserRole.USER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content", hasSize(0)));

    final var mavenRepo =
        this.repoTxService.createRepo(
            unique("maven"), io.repsy.protocols.shared.repo.dtos.RepoType.MAVEN, false, null);
    this.entityManager.flush();
    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", mavenRepo.getName())
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SUCCESS"))
        .andExpect(jsonPath("$.data.content", hasSize(0)));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("ERROR"));
  }

  @Test
  @DisplayName("rejects unsupported verbs and keeps sumdb as a deliberate 404")
  void rejectsUnsupportedVerbsAndSumdbVariants() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("verbs"), true);

    this.mockMvc
        .perform(post("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(patch("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(get("/api/go/modules/{repo}/sumdb/supported", repo).with(apiPort()))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(get("/api/go/modules/{repo}/sumdb/supported", "unknown").with(apiPort()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("pins checksum support and authorization behavior")
  void handlesAuthAndSumdb() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), true);

    this.mockMvc
        .perform(get("/api/go/modules/{repo}/sumdb/supported", repo).with(apiPort()))
        .andExpect(status().isNotFound());

    this.mockMvc
        .perform(get("/api/go/modules/{repo}", repo).with(apiPort()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("unAuthorized"))
        .andExpect(jsonPath("$.errorCode", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(jsonPath("$.data").value("modulePath"));
  }

  @Test
  @DisplayName("deletes a version and module through the management endpoints")
  void deletesVersionsAndModules() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.ADMIN);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), true);
    this.upload(repo, "v1.0.0", this.protocolBearerTokenFor(user));
    this.upload(repo, "v1.2.0", this.protocolBearerTokenFor(user));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .param("version", "v1.0.0")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleVersionDeleted"))
        .andExpect(jsonPath("$.data").value(nullValue()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.versions", hasSize(1)))
        .andExpect(jsonPath("$.data.versions[0].version").value("v1.2.0"));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleDeleted"));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("ERROR"));
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String MODULES = "/api/go/modules/{repo}";
    private static final String SEARCH = "/api/go/modules/{repo}/search";
    private static final String VERSIONS = "/api/go/modules/{repo}/versions";

    static Stream<String> endpoints() {
      return Stream.of(MODULES, SEARCH, VERSIONS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.of(
          Arguments.of(MODULES, "id"),
          Arguments.of(MODULES, "modulePath"),
          Arguments.of(MODULES, "createdAt"),
          Arguments.of(SEARCH, "id"),
          Arguments.of(SEARCH, "modulePath"),
          Arguments.of(SEARCH, "createdAt"),
          Arguments.of(VERSIONS, "id"),
          Arguments.of(VERSIONS, "version"),
          Arguments.of(VERSIONS, "createdAt"));
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private ResultActions list(
        final String path, final String repo, final String token, final String... params)
        throws Exception {
      final var request =
          get(path, repo).param("modulePath", MODULE).with(apiPort()).header(AUTHORIZATION, token);

      for (int i = 0; i < params.length; i += 2) {
        request.param(params[i], params[i + 1]);
      }

      return GolangModuleControllerIT.this.mockMvc.perform(request);
    }

    private String seededRepo(final User user) throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var repo = it.createRepo(unique("paging"), false);
      it.upload(repo, "v1.0.0", it.protocolBearerTokenFor(user));
      it.upload(repo, "v1.2.0", it.protocolBearerTokenFor(user));
      return repo;
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var user = it.createUser(uniqueUsername("gomod"), UserRole.USER);
      final var token = it.bearerTokenFor(user);
      final var repo = this.seededRepo(user);

      this.list(path, repo, token, "sort", property + ",asc").andExpect(status().isOk());
      this.list(path, repo, token, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the versions by the requested sort property")
    void ordersVersions() throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var user = it.createUser(uniqueUsername("gomod"), UserRole.USER);
      final var token = it.bearerTokenFor(user);
      final var repo = this.seededRepo(user);

      this.list(VERSIONS, repo, token, "sort", "version,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("v1.0.0"));
      this.list(VERSIONS, repo, token, "sort", "version,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("v1.2.0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var user = it.createUser(uniqueUsername("gomod"), UserRole.USER);
      final var token = it.bearerTokenFor(user);
      final var repo = this.seededRepo(user);

      PagingAssertions.expectInvalidParameter(
          this.list(path, repo, token, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var user = it.createUser(uniqueUsername("gomod"), UserRole.USER);
      final var token = it.bearerTokenFor(user);
      final var repo = this.seededRepo(user);

      PagingAssertions.expectInvalidParameter(this.list(path, repo, token, param, value), param);
    }
  }
}
