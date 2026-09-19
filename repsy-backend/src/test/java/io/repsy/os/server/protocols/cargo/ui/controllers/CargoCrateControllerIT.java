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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateIndexRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.repositories.CargoCrateMetaRepository;
import io.repsy.os.server.protocols.cargo.shared.crate.services.CargoCrateServiceImpl;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration coverage for the Cargo crate-management API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("CargoCrateController /api/cargo/crates/*")
class CargoCrateControllerIT {

  private static final int API_PORT = 8080;
  private static final String VALID_PASSWORD = "Password1!";
  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
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
    registry.add("storage-gateway.fs.base-path", CargoCrateControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-cargo-it").toString();
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoRepository repoRepository;
  @Autowired private CargoCrateServiceImpl crateService;
  @Autowired private CargoCrateIndexRepository crateIndexRepository;
  @Autowired private CargoCrateMetaRepository crateMetaRepository;
  @Autowired private CargoStorageService cargoStorageService;
  @PersistenceContext private EntityManager entityManager;

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private User createUser(final String prefix, final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var info = this.userTxService.create(unique(prefix), role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private static String basicAuth(final String username, final String password) {
    final var raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(raw);
  }

  private String token(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private String expiredToken(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofSeconds(-30));
  }

  private Repo seedRepo(final RepoType type, final boolean privateRepo) throws Exception {
    final var admin = this.createUser("admin", UserRole.ADMIN);
    final var name = unique("cargo");
    this.mockMvc
        .perform(
            post("/api/repos/" + type.name())
                .with(apiPort())
                .header(AUTHORIZATION, this.token(admin))
                .contentType("application/json")
                .content("{\"name\":\"%s\",\"privateRepo\":%s}".formatted(name, privateRepo)))
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
        ("crate-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8),
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
        ("crate-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8),
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

  private static void expectSuccess(final ResultActions result, final String msgId)
      throws Exception {
    final var envelope = (Map<String, Object>) JsonPath.read(body(result), "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null)
        .containsEntry("text", msgId);
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
                  "GET", "/api/cargo/crates/" + repo.getName() + "?query=hello&page=0&size=1", null)
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
          .containsEntry("text", "cratesFetched");
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
              "version");

      final var versions =
          CargoCrateControllerIT.this
              .request(
                  "GET",
                  "/api/cargo/crates/" + repo.getName() + "/demo_crate/versions?query=2",
                  null)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(JsonPath.<List<?>>read(versions, "$.data.content")).hasSize(1);
      assertThat(JsonPath.<String>read(versions, "$.data.content[0].version")).isEqualTo("2.0.0");
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
              "created_at");
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
                      "/api/cargo/crates/"
                          + repo.getName()
                          + "/paged/versions?query=0.0&page=1&size=1",
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
      final var user = CargoCrateControllerIT.this.createUser("reader", UserRole.USER);
      // A missing repo is only revealed to an authenticated caller; anonymous callers get the
      // same 401 as for a private repo (RPS-887).
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/missing", CargoCrateControllerIT.this.token(user)),
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
          "crateNotFound");
      CargoCrateControllerIT.this.publish(repo, "exists", "1.0.0");
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", "/api/cargo/crates/" + repo.getName() + "/exists/9.9.9", null),
          HttpStatus.NOT_FOUND,
          "crateVersionNotFound",
          "crateVersionNotFound");
    }
  }

  @Nested
  @DisplayName("Authorization")
  class Authorization {

    @Test
    @DisplayName("answers an unknown Basic username exactly like a wrong password (RPS-906)")
    void basicCredentialsDoNotRevealUsernames() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, true);
      final var user = CargoCrateControllerIT.this.createUser("basic", UserRole.USER);
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
      final var user = CargoCrateControllerIT.this.createUser("reader", UserRole.USER);
      final var path = "/api/cargo/crates/" + repo.getName();
      expectError(
          CargoCrateControllerIT.this.request("GET", path, null),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
      expectError(
          CargoCrateControllerIT.this.request("GET", path, "Bearer garbage"),
          HttpStatus.FORBIDDEN,
          "accessNotAllowed",
          "Access isn't allowed.");
      expectError(
          CargoCrateControllerIT.this.request(
              "GET", path, CargoCrateControllerIT.this.expiredToken(user)),
          HttpStatus.FORBIDDEN,
          "sessionExpired",
          "Session expired.");
      CargoCrateControllerIT.this.userRepository.deleteById(user.getId());
      CargoCrateControllerIT.this.entityManager.flush();
      expectError(
          CargoCrateControllerIT.this.request("GET", path, CargoCrateControllerIT.this.token(user)),
          HttpStatus.NOT_FOUND,
          "userNotFound",
          "User not found.");
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
      final var user = CargoCrateControllerIT.this.createUser("noaccess", UserRole.USER);
      CargoCrateControllerIT.this
          .request(
              "GET", "/api/cargo/crates/" + repo.getName(), CargoCrateControllerIT.this.token(user))
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
      final var user = CargoCrateControllerIT.this.createUser("manager", UserRole.ADMIN);
      CargoCrateControllerIT.this.publish(repo, "delete-me", "1.0.0");
      CargoCrateControllerIT.this.publish(repo, "delete-me", "2.0.0");

      expectSuccess(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/delete-me/1.0.0",
              CargoCrateControllerIT.this.token(user)),
          "crateVersionDeleted");
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
              CargoCrateControllerIT.this.token(user)),
          "crateDeleted");
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
              CargoCrateControllerIT.this.token(user)),
          HttpStatus.NOT_FOUND,
          "crateNotFound",
          "crateNotFound");
    }

    @Test
    void deniesDeleteToReadOnlyCaller() throws Exception {
      final var repo = CargoCrateControllerIT.this.seedRepo(RepoType.CARGO, false);
      final var user = CargoCrateControllerIT.this.createUser("reader", UserRole.USER);
      CargoCrateControllerIT.this.publish(repo, "protected", "1.0.0");
      expectError(
          CargoCrateControllerIT.this.request(
              "DELETE",
              "/api/cargo/crates/" + repo.getName() + "/protected",
              CargoCrateControllerIT.this.token(user)),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "The user has logged in but has no permissions.");
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
}
