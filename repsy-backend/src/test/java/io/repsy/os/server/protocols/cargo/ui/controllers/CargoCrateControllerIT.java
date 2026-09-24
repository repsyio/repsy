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
package io.repsy.os.server.protocols.cargo.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateIndexRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateMetaRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.services.CargoCrateServiceImpl;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/** Full-stack integration coverage for the Cargo crate-management API. */
@DisplayName("CargoCrateController /api/cargo/crates/*")
class CargoCrateControllerIT extends AbstractIntegrationTest {

  @Autowired private CargoCrateServiceImpl crateService;
  @Autowired private CargoCrateIndexRepository crateIndexRepository;
  @Autowired private CargoCrateMetaRepository crateMetaRepository;
  @Autowired private CargoStorageService cargoStorageService;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private Repo seedRepo(final RepoType type, final boolean privateRepo) throws Exception {
    final var admin = this.createUser(unique("admin"), UserRole.ADMIN);
    final var name = unique("cargo");
    this.mockMvc
        .perform(
            post("/api/repos")
                .with(apiPort())
                .header(AUTHORIZATION, this.bearerTokenFor(admin))
                .contentType("application/json")
                .content(
                    "{\"name\":\"%s\",\"type\":\"%s\",\"privateRepo\":%s}"
                        .formatted(name, type.name(), privateRepo)))
        .andExpect(status().isOk());
    this.entityManager.flush();
    return this.repoRepository.findByName(name).orElseThrow();
  }

  private RepoInfo repoInfo(final Repo repo) {
    return RepoInfo.builder()
        .id(repo.getId())
        .storageKey(repo.getId())
        .name(repo.getName())
        .description(repo.getDescription())
        .privateRepo(repo.isPrivateRepo())
        .allowOverride(repo.isAllowOverride())
        .searchable(repo.isSearchable())
        .securityScanEnabled(repo.isSecurityScanEnabled())
        .type(repo.getType())
        .build();
  }

  /**
   * Publishes metadata and real crate/index files through the storage boundary used in production.
   */
  private void publish(final Repo repo, final String name, final String version) throws Exception {
    final var info = this.repoInfo(repo);
    final var normalized = CrateUtils.normalizeCrateName(name);
    final var indexLine =
        "{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"cksum\":\"checksum-%s\",\"features\":{},\"yanked\":false,\"v\":2}"
            .formatted(normalized, version, version);
    this.cargoStorageService.writeCrateAndIndex(
        repo.getId(),
        repo.getName(),
        normalized,
        version,
        new java.io.ByteArrayInputStream(
            ("crate-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        indexLine);
    this.crateService.publish(
        info,
        new CratePublishRequest(
            name,
            version,
            true,
            List.of(),
            Map.of(),
            List.of("Repsy Test"),
            "Cargo integration fixture",
            "docs.rs",
            "https://example.test",
            "Readme",
            null,
            List.of("integration", "cargo"),
            List.of("testing"),
            "MIT",
            null,
            "https://example.test/repo",
            null,
            "1.85",
            "checksum-" + version,
            null));
  }

  private void publishRich(final Repo repo, final String name, final String version)
      throws Exception {
    final var info = this.repoInfo(repo);
    final var normalized = CrateUtils.normalizeCrateName(name);
    final var dependency =
        new CratePublishDep(
            "serde",
            "^1.0",
            List.of("derive"),
            true,
            false,
            "cfg(unix)",
            "dev",
            "https://example.test/registry",
            "serde1");
    final var features = Map.of("default", List.of("serde1"), "full", List.of("serde1"));
    final var indexLine =
        "{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[{\"name\":\"serde1\",\"req\":\"^1.0\",\"features\":[\"derive\"],\"optional\":true,\"default_features\":false,\"target\":\"cfg(unix)\",\"kind\":\"dev\",\"registry\":\"https://example.test/registry\",\"package\":\"serde\"}],\"cksum\":\"checksum-%s\",\"features\":{\"default\":[\"serde1\"],\"full\":[\"serde1\"]},\"features2\":{\"full\":[\"serde1\"]},\"yanked\":false,\"links\":\"native\",\"v\":2,\"rust_version\":\"1.85\"}"
            .formatted(normalized, version, version);
    this.cargoStorageService.writeCrateAndIndex(
        repo.getId(),
        repo.getName(),
        normalized,
        version,
        new java.io.ByteArrayInputStream(
            ("crate-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        indexLine);
    this.crateService.publish(
        info,
        new CratePublishRequest(
            name,
            version,
            true,
            List.of(dependency),
            features,
            List.of("Ada Lovelace"),
            "A fully described crate",
            "https://docs.example.test",
            "https://example.test",
            "README",
            null,
            List.of("cargo", "integration"),
            List.of("testing", "ffi"),
            "MIT",
            "LICENSE",
            "https://example.test/repo",
            "native",
            "1.85",
            "checksum-" + version,
            Map.of("full", List.of("serde1"))));
  }

  private ResultActions request(final String method, final String path, final String auth)
      throws Exception {
    final var builder =
        switch (method) {
          case "GET" -> get(path);
          case "DELETE" -> delete(path);
          default -> throw new IllegalArgumentException(method);
        };
    final var request = builder.with(apiPort());
    if (auth != null) {
      request.header(AUTHORIZATION, auth);
    }
    return this.mockMvc.perform(request);
  }

  private static String body(final ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private static Map<String, Object> data(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private static void expectError(
      final ResultActions result, final HttpStatus status, final String msgId, final String text)
      throws Exception {
    final var response =
        result
            .andExpect(status().is(status.value()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    final var envelope = (Map<String, Object>) JsonPath.read(response, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "ERROR")
        .containsEntry("text", text);
    assertThat((String) envelope.get("errorCode")).matches(UUID_PATTERN);
  }

  @Nested
  @DisplayName("GET")
  class Reads {

    @Test
    void searchesCratesWithFullPagingEnvelopeAndQuery() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      CargoCrateControllerIT.this.publish(repo, "hello_world", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "hello-world", "1.1.0");
      CargoCrateControllerIT.this.publish(repo, "other", "1.0.0");

      final var response =
          CargoCrateControllerIT.this
              .request(
                  "GET", "/api/cargo/crates/" + repo.getName() + "?q=hello&page=0&size=1", null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      final var envelope = (Map<String, Object>) JsonPath.read(response, "$");
      assertThat(envelope)
          .containsOnlyKeys(ENVELOPE_KEYS)
          .containsEntry("msgId", "cratesFetched")
          .containsEntry("type", "SUCCESS")
          .containsEntry("errorCode", null)
          .containsEntry("text", "Crates fetched.");
      assertThat((Map<String, Object>) envelope.get("data")).containsOnlyKeys("content", "page");
      assertThat((Map<String, Object>) ((Map<String, Object>) envelope.get("data")).get("page"))
          .containsOnlyKeys("size", "number", "totalElements", "totalPages")
          .containsEntry("size", 1)
          .containsEntry("number", 0)
          .containsEntry("totalElements", 1)
          .containsEntry("totalPages", 1);
      assertThat(JsonPath.<Integer>read(response, "$.data.page.size")).isEqualTo(1);
      assertThat(JsonPath.<Integer>read(response, "$.data.page.totalElements")).isEqualTo(1);
      assertThat(JsonPath.<List<?>>read(response, "$.data.content")).hasSize(1);
      assertThat(JsonPath.<String>read(response, "$.data.content[0].name"))
          .isEqualTo("hello_world");
    }

    @Test
    void returnsFullCrateAndVersionShapesAndLiteralVersionsRouteWins() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      CargoCrateControllerIT.this.publish(repo, "demo_crate", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "demo_crate", "2.0.0");

      final var crate =
          CargoCrateControllerIT.this
              .request("GET", "/api/cargo/crates/" + repo.getName() + "/demo_crate", null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(data(crate))
          .containsOnlyKeys(
              "id",
              "name",
              "original_name",
              "max_version",
              "total_downloads",
              "description",
              "homepage",
              "repository",
              "authors",
              "keywords",
              "categories",
              "hasLib");
      assertThat(JsonPath.<String>read(crate, "$.data.name")).isEqualTo("demo_crate");
      assertThat(JsonPath.<String>read(crate, "$.data.max_version")).isEqualTo("2.0.0");

      final var version =
          CargoCrateControllerIT.this
              .request("GET", "/api/cargo/crates/" + repo.getName() + "/demo_crate/1.0.0", null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(data(version))
          .containsKeys(
              "crateId",
              "created_at",
              "deps",
              "documentation",
              "downloads",
              "edition",
              "hasLib",
              "license",
              "license_file",
              "name",
              "readme",
              "rust_version",
              "version",
              "yanked");
      assertThat(JsonPath.<Boolean>read(version, "$.data.yanked")).isFalse();

      final var versions =
          CargoCrateControllerIT.this
              .request(
                  "GET", "/api/cargo/crates/" + repo.getName() + "/demo_crate/versions?q=2", null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(JsonPath.<List<?>>read(versions, "$.data.content")).hasSize(1);
      assertThat(JsonPath.<String>read(versions, "$.data.content[0].version")).isEqualTo("2.0.0");
      assertThat(JsonPath.<Boolean>read(versions, "$.data.content[0].yanked")).isFalse();
    }

    @Test
    void marksOnlyTheYankedVersionInTheVersionList() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      CargoCrateControllerIT.this.publish(repo, "yank_crate", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "yank_crate", "2.0.0");
      CargoCrateControllerIT.this.crateService.yank(
          CargoCrateControllerIT.this.repoInfo(repo), "yank_crate", "2.0.0");

      final var versions =
          CargoCrateControllerIT.this
              .request(
                  "GET",
                  "/api/cargo/crates/" + repo.getName() + "/yank_crate/versions?sort=version,asc",
                  null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(JsonPath.<String>read(versions, "$.data.content[0].version")).isEqualTo("1.0.0");
      assertThat(JsonPath.<Boolean>read(versions, "$.data.content[0].yanked")).isFalse();
      assertThat(JsonPath.<String>read(versions, "$.data.content[1].version")).isEqualTo("2.0.0");
      assertThat(JsonPath.<Boolean>read(versions, "$.data.content[1].yanked")).isTrue();
    }

    @Test
    void returnsDependenciesFeaturesAndYankedVersionMetadata() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      CargoCrateControllerIT.this.publishRich(repo, "rich_crate", "1.2.3");
      CargoCrateControllerIT.this.crateService.yank(
          CargoCrateControllerIT.this.repoInfo(repo), "rich_crate", "1.2.3");

      final var response =
          body(
              CargoCrateControllerIT.this
                  .request("GET", "/api/cargo/crates/" + repo.getName() + "/rich_crate/1.2.3", null)
                  .andExpect(status().isOk()));
      assertThat(JsonPath.<Map<String, Object>>read(response, "$.data"))
          .containsOnlyKeys(
              "crateId",
              "name",
              "version",
              "readme",
              "license",
              "license_file",
              "documentation",
              "edition",
              "rust_version",
              "deps",
              "downloads",
              "hasLib",
              "yanked",
              "created_at");
      assertThat(JsonPath.<Boolean>read(response, "$.data.yanked")).isTrue();
      assertThat(JsonPath.<Map<String, Object>>read(response, "$.data.deps[0]"))
          .containsOnlyKeys(
              "name",
              "req",
              "features",
              "optional",
              "default_features",
              "target",
              "kind",
              "registry",
              "package");
      assertThat(JsonPath.<String>read(response, "$.data.license")).isEqualTo("MIT");
      assertThat(JsonPath.<String>read(response, "$.data.rust_version")).isEqualTo("1.85");
      assertThat(
              CargoCrateControllerIT.this
                  .crateIndexRepository
                  .findAllByCrateRepoIdAndName(repo.getId(), "rich_crate")
                  .getFirst()
                  .isYanked())
          .isTrue();
    }

    @Test
    void honorsPaginationBoundariesAndCaseInsensitiveVersionQuery() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      CargoCrateControllerIT.this.publish(repo, "paged", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "paged", "2.0.0");
      final var response =
          body(
              CargoCrateControllerIT.this
                  .request(
                      "GET",
                      "/api/cargo/crates/" + repo.getName() + "/paged/versions?q=0.0&page=1&size=1",
                      null)
                  .andExpect(status().isOk()));
      assertThat(JsonPath.<List<?>>read(response, "$.data.content")).hasSize(1);
      assertThat(JsonPath.<String>read(response, "$.data.content[0].version")).isEqualTo("1.0.0");
      assertThat(JsonPath.<Integer>read(response, "$.data.page.number")).isEqualTo(1);
      assertThat(JsonPath.<Integer>read(response, "$.data.page.totalElements")).isEqualTo(2);
    }

    @Test
    void reportsNotFoundForMissingRepoCrateAndVersion() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      final var user = CargoCrateControllerIT.this.createUser(unique("reader"), UserRole.USER);
      // A missing repo is only revealed to an authenticated caller; anonymous callers get the
      // same 401 as for a private repo (RPS-887).
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/missing", CargoCrateControllerIT.this.bearerTokenFor(user)),
          HttpStatus.NOT_FOUND,
          "repoNotFound",
          "Repository not found");
      CargoCrateControllerIT.this
          .request("GET", "/api/cargo/crates/missing", null)
          .andExpect(status().isUnauthorized());
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/" + repo.getName() + "/missing", null),
          HttpStatus.NOT_FOUND,
          "crateNotFound",
          "Crate not found.");
      CargoCrateControllerIT.this.publish(repo, "exists", "1.0.0");
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/" + repo.getName() + "/exists/9.9.9", null),
          HttpStatus.NOT_FOUND,
          "crateVersionNotFound",
          "Crate version not found.");
    }
  }

  @Nested
  @DisplayName("Authorization")
  class Authorization {

    @Test
    @DisplayName("answers an unknown Basic username exactly like a wrong password (RPS-906)")
    void basicCredentialsDoNotRevealUsernames() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);
      final var user = CargoCrateControllerIT.this.createUser(unique("basic"), UserRole.USER);
      final var path = "/api/cargo/crates/" + repo.getName();

      for (final var auth :
          List.of(
              basicAuth(user.getUsername(), "wrong"),
              basicAuth("ghost-" + UUID.randomUUID(), "wrong"))) {
        expectError(
            CargoCrateControllerIT.this.request("GET", path, auth),
            HttpStatus.UNAUTHORIZED,
            "unAuthorized",
            "The user has logged in but has no permissions.");
      }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Basic !!!", "Basic dXNlcg=="})
    @DisplayName("answers a Basic header that is not base64 or has no colon with 401 (RPS-927)")
    void undecodableBasicHeaderIsUnauthorized(final String authHeader) throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);

      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/" + repo.getName(), authHeader),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
    }

    @Test
    void rejectsMissingMalformedExpiredAndDeletedUserTokens() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);
      final var user = CargoCrateControllerIT.this.createUser(unique("reader"), UserRole.USER);
      final var path = "/api/cargo/crates/" + repo.getName();
      expectError(
          CargoCrateControllerIT.this.request("GET", path, null),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
      expectError(
          CargoCrateControllerIT.this.request("GET", path, "Bearer garbage"),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "Access isn't allowed.");
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", path, CargoCrateControllerIT.this.expiredBearerTokenFor(user)),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "Session expired.");
      CargoCrateControllerIT.this.userRepository.deleteById(user.getId());
      CargoCrateControllerIT.this.entityManager.flush();
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", path, CargoCrateControllerIT.this.bearerTokenFor(user)),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
    }

    @Test
    void allowsAnonymousPublicReadButRequiresReadOnPrivateRepo() throws Exception {
      final var publicRepo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      final var privateRepo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);
      CargoCrateControllerIT.this.publish(publicRepo, "public", "1.0.0");
      CargoCrateControllerIT.this.publish(privateRepo, "private", "1.0.0");
      CargoCrateControllerIT.this
          .request("GET", "/api/cargo/crates/" + publicRepo.getName(), null)
          .andExpect(status().isOk());
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/" + privateRepo.getName(), null),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
    }

    @Test
    void allowsAnAuthenticatedUserToReadAPrivateRepo() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);
      final var user = CargoCrateControllerIT.this.createUser(unique("noaccess"), UserRole.USER);
      CargoCrateControllerIT.this
          .request(
              "GET",
              "/api/cargo/crates/" + repo.getName(),
              CargoCrateControllerIT.this.bearerTokenFor(user))
          .andExpect(status().isOk());
    }

    @Test
    void pinsBehaviorForARepositoryOfAnotherType() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.MAVEN, false);
      final var response =
          body(
              CargoCrateControllerIT.this
                  .request("GET", "/api/cargo/crates/" + repo.getName(), null)
                  .andExpect(status().isOk()));
      assertThat(JsonPath.<List<?>>read(response, "$.data.content")).isEmpty();
    }
  }

  @Nested
  @DisplayName("DELETE")
  class Deletes {

    @Test
    void deletesOneVersionUpdatesIndexAndThenDeletesTheCrateFiles() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      final var user = CargoCrateControllerIT.this.createUser(unique("manager"), UserRole.ADMIN);
      CargoCrateControllerIT.this.publish(repo, "delete-me", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "delete-me", "2.0.0");

      expectSuccess(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/delete-me/1.0.0",
              CargoCrateControllerIT.this.bearerTokenFor(user)),
          "crateVersionDeleted",
          "Crate version deleted.");
      CargoCrateControllerIT.this.entityManager.flush();
      assertThat(CargoCrateControllerIT.this.crateIndexRepository.findAll()).hasSize(1);
      assertThat(CargoCrateControllerIT.this.crateMetaRepository.findAll()).hasSize(1);
      assertThat(CargoCrateControllerIT.this.repoRepository.findByName(repo.getName())).isPresent();
      assertThatThrownBy(
              () ->
                  CargoCrateControllerIT.this.cargoStorageService.getCrate(
                      repo.getId(), repo.getName(), "delete-me", "1.0.0"))
          .hasMessageContaining("crateNotFound");
      CargoCrateControllerIT.this
          .request("GET", "/api/cargo/crates/" + repo.getName() + "/delete-me/1.0.0", null)
          .andExpect(status().isNotFound());
      CargoCrateControllerIT.this
          .request("GET", "/api/cargo/crates/" + repo.getName() + "/delete-me/2.0.0", null)
          .andExpect(status().isOk());

      expectSuccess(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/delete-me",
              CargoCrateControllerIT.this.bearerTokenFor(user)),
          "crateDeleted",
          "Crate deleted.");
      CargoCrateControllerIT.this.entityManager.flush();
      assertThat(CargoCrateControllerIT.this.crateIndexRepository.findAll()).isEmpty();
      assertThat(CargoCrateControllerIT.this.crateMetaRepository.findAll()).isEmpty();
      assertThatThrownBy(
              () ->
                  CargoCrateControllerIT.this.cargoStorageService.getCrate(
                      repo.getId(), repo.getName(), "delete-me", "2.0.0"))
          .hasMessageContaining("crateNotFound");
      expectError(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/delete-me",
              CargoCrateControllerIT.this.bearerTokenFor(user)),
          HttpStatus.NOT_FOUND,
          "crateNotFound",
          "Crate not found.");
    }

    @Test
    void deniesDeleteToReadOnlyCaller() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      final var user = CargoCrateControllerIT.this.createUser(unique("reader"), UserRole.USER);
      CargoCrateControllerIT.this.publish(repo, "protected", "1.0.0");
      expectError(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/protected",
              CargoCrateControllerIT.this.bearerTokenFor(user)),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "Access Denied. Please check your credentials.");
    }
  }

  @Test
  void unsupportedVerbsReturnItemNotFound() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, false);
    this.mockMvc
        .perform(post("/api/cargo/crates/" + repo.getName()).with(apiPort()))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(put("/api/cargo/crates/" + repo.getName()).with(apiPort()))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(patch("/api/cargo/crates/" + repo.getName()).with(apiPort()))
        .andExpect(status().isNotFound());
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String CRATES = "/api/cargo/crates/{repo}";
    private static final String VERSIONS = "/api/cargo/crates/{repo}/paged/versions";

    static Stream<String> endpoints() {
      return Stream.of(CRATES, VERSIONS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.concat(
          Stream.of(
                  "id",
                  "name",
                  "maxVersion",
                  "lastUpdatedAt",
                  "totalDownloads",
                  "max_version",
                  "updated_at",
                  "downloads")
              .map(property -> Arguments.of(CRATES, property)),
          Stream.of("version", "createdAt").map(property -> Arguments.of(VERSIONS, property)));
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private Repo seededRepo() throws Exception {
      final var it = CargoCrateControllerIT.this;
      final var repo = it.seedRepo(RepoType.CARGO, false);
      it.publish(repo, "paged", "1.0.0");
      it.publish(repo, "paged", "1.1.0");
      it.publish(repo, "other", "0.1.0");
      return repo;
    }

    private ResultActions list(
        final Repo repo, final String path, final String param, final String value)
        throws Exception {
      return CargoCrateControllerIT.this.request(
          "GET", path.replace("{repo}", repo.getName()) + "?" + param + "=" + value, null);
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(repo, path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the versions by the requested sort property")
    void ordersVersions() throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, VERSIONS, "sort", "version,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.0.0"));
      this.list(repo, VERSIONS, "sort", "version,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].version").value("1.1.0"));
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"max_version", "maxVersion"})
    @DisplayName("orders the crates by the response's max_version or the entity's maxVersion")
    void ordersCratesByMaxVersion(final String property) throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, CRATES, "sort", property + ",asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("other"))
          .andExpect(jsonPath("$.data.content[0].max_version").value("0.1.0"));
      this.list(repo, CRATES, "sort", property + ",desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("paged"))
          .andExpect(jsonPath("$.data.content[0].max_version").value("1.1.0"));
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"updated_at", "lastUpdatedAt"})
    @DisplayName("orders the crates by when they were last updated")
    void ordersCratesByLastUpdate(final String property) throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, CRATES, "sort", property + ",desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("other"))
          .andExpect(jsonPath("$.data.content[1].name").value("paged"));
      this.list(repo, CRATES, "sort", property + ",asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("paged"))
          .andExpect(jsonPath("$.data.content[1].name").value("other"));
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"downloads", "totalDownloads"})
    @DisplayName("orders the crates by the response's downloads or the entity's totalDownloads")
    void ordersCratesByDownloads(final String property) throws Exception {
      final var repo = this.seededRepo();
      final var info = CargoCrateControllerIT.this.repoInfo(repo);
      CargoCrateControllerIT.this.crateService.incrementDownloadCount(info, "other", "0.1.0");
      CargoCrateControllerIT.this.crateService.incrementDownloadCount(info, "other", "0.1.0");
      CargoCrateControllerIT.this.crateService.incrementDownloadCount(info, "paged", "1.0.0");

      this.list(repo, CRATES, "sort", property + ",desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("other"))
          .andExpect(jsonPath("$.data.content[0].downloads").value(2))
          .andExpect(jsonPath("$.data.content[1].name").value("paged"));
      this.list(repo, CRATES, "sort", property + ",asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("paged"))
          .andExpect(jsonPath("$.data.content[0].downloads").value(1));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {CRATES, VERSIONS})
    @DisplayName("filters by q only: query, the old name of the filter, is an unknown parameter")
    void filtersByQOnly(final String path) throws Exception {
      final var repo = this.seededRepo();

      PagingAssertions.expectFilterIsQ(
          this.list(repo, path, "page", "0"),
          this.list(repo, path, "query", PagingAssertions.NO_MATCH),
          this.list(repo, path, "q", PagingAssertions.NO_MATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seededRepo(), path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seededRepo(), path, param, value), param);
    }
  }
}
