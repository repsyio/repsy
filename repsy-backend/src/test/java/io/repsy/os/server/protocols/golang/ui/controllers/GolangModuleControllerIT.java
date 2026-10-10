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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import io.repsy.os.HeapOrder;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.golang.ui.facades.GoApiFacade;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Full-stack integration tests for the Go module-management API. */
@DisplayName("GoModuleController /api/go/modules/*")
class GolangModuleControllerIT extends AbstractIT {

  private static final String MODULE = "io.repsy/hello-world";
  private static final String V2_MODULE = "example.com/mod/v2";
  private static final String UPPERCASE_MODULE = "example.com/Upper/Module";

  @Autowired private RepoTxService repoTxService;
  @Autowired private GoApiFacade golangApiFacade;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private String createRepo(final String name, final boolean privateRepo) {
    final var repoInfo =
        this.repoTxService.createRepo(
            name, io.repsy.protocols.shared.repo.dtos.RepoType.GOLANG, privateRepo, null);
    this.entityManager.flush();
    assertThat(
            this.repoTxService.findRepoByNameAndType(
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
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.content[0].modulePath").value(MODULE))
        .andExpect(jsonPath("$.content[0].createdAt", notNullValue()))
        .andExpect(jsonPath("$.page.size").value(10))
        .andExpect(jsonPath("$.page.number").value(0))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.page.totalPages").value(1));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .param("q", "HELLO")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].modulePath").value(MODULE));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(2)))
        .andExpect(jsonPath("$.content[0].id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.content[0].version").value("v1.2.0"))
        .andExpect(jsonPath("$.content[0].goVersion").value("1.23"))
        .andExpect(jsonPath("$.content[0].createdAt", notNullValue()));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.modulePath").value(MODULE))
        .andExpect(jsonPath("$.latestVersion").value("v1.2.0"))
        .andExpect(jsonPath("$.createdAt", notNullValue()))
        .andExpect(jsonPath("$.versions", hasSize(2)));
  }

  @Test
  @DisplayName(
      "the module info lists versions stored in the same instant newest id first (RPS-1614)")
  void moduleInfoOrdersVersionsOfOneInstantById() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("order"), true);
    final var writer = this.protocolBearerTokenFor(user);
    this.upload(repo, "v1.0.0", writer);
    this.upload(repo, "v1.1.0", writer);
    this.upload(repo, "v1.2.0", writer);

    final var ids = new HashMap<String, UUID>();
    for (final var version : List.of("v1.0.0", "v1.1.0", "v1.2.0")) {
      ids.put(
          version,
          this.jdbcTemplate.queryForObject(
              "select v.\"id\" from \"go_module_version\" v"
                  + " join \"go_module\" m on m.\"id\" = v.\"module_id\""
                  + " join \"repo\" r on r.\"id\" = m.\"repo_id\""
                  + " where r.\"name\" = ? and v.\"version\" = ?",
              UUID.class,
              repo,
              version));
    }

    // The same instant for all three, and a heap that holds them in none of the id orders.
    this.jdbcTemplate.update(
        "update \"go_module_version\" set \"created_at\" = ? where \"id\" in (?, ?, ?)",
        Timestamp.from(Instant.now().minusSeconds(60)),
        ids.get("v1.0.0"),
        ids.get("v1.1.0"),
        ids.get("v1.2.0"));
    HeapOrder.rewriteInOrder(
        this.jdbcTemplate,
        "go_module_version",
        List.of(ids.get("v1.1.0"), ids.get("v1.0.0"), ids.get("v1.2.0")));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[*].version").value(contains("v1.2.0", "v1.1.0", "v1.0.0")));
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
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.content", hasSize(2)))
        .andExpect(jsonPath("$.content[*].id", hasSize(2)))
        .andExpect(jsonPath("$.content[*].createdAt", hasSize(2)))
        .andExpect(jsonPath("$.page.size").value(2))
        .andExpect(jsonPath("$.page.totalElements").value(3))
        .andExpect(jsonPath("$.page.totalPages").value(2));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .param("q", "upper")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].modulePath").value(UPPERCASE_MODULE));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .param("q", "incompatible")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].version").value("v1.2.3+incompatible"))
        .andExpect(jsonPath("$.content[0].goVersion").value("1.23"));
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
                .param("q", "missing")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(2)))
        .andExpect(jsonPath("$.content", hasSize(0)))
        .andExpect(jsonPath("$.page.totalElements").value(0));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("validationError"))
        .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("modulePath")))
        .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.detail").value("Incoming data couldn't be validated."));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("moduleNotFound"))
        .andExpect(jsonPath("$.detail").value("Module not found."))
        .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", "does-not-exist")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("repoNotFound"));
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
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("loginRequired"))
        .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .with(apiPort())
                .header(AUTHORIZATION, "Bearer malformed"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", publicRepo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].modulePath").value(MODULE));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .with(apiPort())
                .header(
                    AUTHORIZATION,
                    this.bearerTokenFor(this.createUser(uniqueUsername("gomod"), UserRole.USER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(0)));

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
        .andExpect(jsonPath("$.content", hasSize(0)));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isForbidden())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("accessDenied"));
  }

  @Test
  @DisplayName("rejects unsupported verbs and keeps sumdb as a deliberate 404")
  void rejectsUnsupportedVerbsAndSumdbVariants() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("verbs"), true);

    this.mockMvc
        .perform(post("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isMethodNotAllowed());
    this.mockMvc
        .perform(patch("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isMethodNotAllowed());
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
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("loginRequired"))
        .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("validationError"))
        .andExpect(
            jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("modulePath")));
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
        .andExpect(status().isNoContent());

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions", hasSize(1)))
        .andExpect(jsonPath("$.versions[0].version").value("v1.2.0"));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNoContent());

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")));
  }

  @Test
  @DisplayName("deleting the last version removes the module; the wire keeps its old answers")
  void lastVersionDeleteRemovesTheModule() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.ADMIN);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), false);
    this.upload(repo, "v1.0.0", this.protocolBearerTokenFor(user));
    this.upload(repo, "v1.2.0", this.protocolBearerTokenFor(user));

    for (final var version : new String[] {"v1.0.0", "v1.2.0"}) {
      this.mockMvc
          .perform(
              delete("/api/go/modules/{repo}/versions", repo)
                  .param("modulePath", MODULE)
                  .param("version", version)
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isNoContent());

      final var moduleListed = version.equals("v1.0.0") ? 1 : 0;
      this.mockMvc
          .perform(get("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content", hasSize(moduleListed)))
          .andExpect(jsonPath("$.page.totalElements").value(moduleListed));
    }

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", MODULE)
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
        .andExpect(jsonPath("$.code").value("moduleNotFound"));

    // The proxy answers as it does for a module that was never published: no list, no latest, both
    // not found so the go command tries the next GOPROXY entry (RPS-1428).
    this.mockMvc
        .perform(get("/{repo}/{module}/@v/list", repo, MODULE).with(protocolPort()))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(get("/{repo}/{module}/@latest", repo, MODULE).with(protocolPort()))
        .andExpect(status().isNotFound());

    // Publishing again creates the module again.
    this.upload(repo, "v1.0.0", this.protocolBearerTokenFor(user));
    this.mockMvc
        .perform(get("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].modulePath").value(MODULE));
  }

  @Test
  @DisplayName("the old search and sumdb routes are gone: search is q on the list (RPS-1781)")
  void oldSearchAndSumdbRoutesAreGone() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.USER);
    final var token = this.bearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), false);
    this.upload(repo, "v1.0.0", this.protocolBearerTokenFor(user));

    for (final var path : new String[] {"search", "sumdb/supported"}) {
      this.mockMvc
          .perform(
              get("/api/go/modules/{repo}/" + path, repo)
                  .param("q", "hello")
                  .with(apiPort())
                  .header(AUTHORIZATION, token))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  @DisplayName(
      "a module whose last path element is a route literal is listed, versioned and deleted as itself")
  void moduleNamedLikeARouteDoesNotCollide() throws Exception {
    final var user = this.createUser(uniqueUsername("gomod"), UserRole.ADMIN);
    final var token = this.bearerTokenFor(user);
    final var protocolToken = this.protocolBearerTokenFor(user);
    final var repo = this.createRepo(unique("go"), false);
    this.upload(repo, "example.com/versions", "v1.0.0", protocolToken);
    this.upload(repo, "example.com/info", "v1.0.0", protocolToken);

    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}", repo)
                .param("q", "example.com/versions")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].modulePath").value("example.com/versions"));
    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/versions", repo)
                .param("modulePath", "example.com/versions")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].version").value("v1.0.0"));
    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo)
                .param("modulePath", "example.com/info")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modulePath").value("example.com/info"));

    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo)
                .param("modulePath", "example.com/versions")
                .with(apiPort())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNoContent());
    this.mockMvc
        .perform(get("/api/go/modules/{repo}", repo).with(apiPort()).header(AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].modulePath").value("example.com/info"));
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String MODULES = "/api/go/modules/{repo}";
    private static final String VERSIONS = "/api/go/modules/{repo}/versions";

    static Stream<String> endpoints() {
      return Stream.of(MODULES, VERSIONS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.of(
          Arguments.of(MODULES, "id"),
          Arguments.of(MODULES, "modulePath"),
          Arguments.of(MODULES, "createdAt"),
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
          .andExpect(jsonPath("$.content[0].version").value("v1.0.0"));
      this.list(VERSIONS, repo, token, "sort", "version,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].version").value("v1.2.0"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({MODULES + ",search", VERSIONS + ",search"})
    @DisplayName("filters by q only: search, the old name of the filter, is an unknown parameter")
    void filtersByQOnly(final String path, final String oldName) throws Exception {
      final var it = GolangModuleControllerIT.this;
      final var user = it.createUser(uniqueUsername("gomod"), UserRole.USER);
      final var token = it.bearerTokenFor(user);
      final var repo = this.seededRepo(user);

      PagingAssertions.expectFilterIsQ(
          this.list(path, repo, token, "page", "0"),
          this.list(path, repo, token, oldName, PagingAssertions.NO_MATCH),
          this.list(path, repo, token, "q", PagingAssertions.NO_MATCH));
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
