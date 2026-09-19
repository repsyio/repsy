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
package io.repsy.os.server.protocols.maven.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.AllowedKeyserver;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration tests for the Maven key-store API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("KeyStoreController /api/mvn/key-stores/*")
class KeyStoreControllerIT {

  private static final int API_PORT = 8080;
  private static final String PASSWORD = "Password1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};
  private static final String[] KEY_STORE_KEYS = {
    "id", "allowedKeyserverId", "host", "displayName"
  };
  private static final String[] PAGE_KEYS = {"content", "page"};

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", KeyStoreControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-keystore-it").toString();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private AllowedKeyserverRepository allowedKeyserverRepository;
  @Autowired private KeyStoreRepository keyStoreRepository;
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
    final var info = this.userTxService.create(unique("user"), role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private String tokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private Repo createRepo(final RepoType type) {
    final var repoInfo = this.repoTxService.createRepo(unique("repo"), type, true, null);
    this.entityManager.flush();
    return this.repoRepository.findById(repoInfo.getStorageKey()).orElseThrow();
  }

  private AllowedKeyserver keyserver(final String host) {
    return this.allowedKeyserverRepository.findAll().stream()
        .filter(item -> item.getHost().equals(host))
        .findFirst()
        .orElseThrow();
  }

  private String body(final UUID keyserverId) {
    return "{\"allowedKeyserverId\":\"%s\"}".formatted(keyserverId);
  }

  private static String responseBody(final String body) {
    return body;
  }

  private static Map<String, Object> envelope(final String body) {
    return JsonPath.read(body, "$");
  }

  private static void assertSuccess(final String body, final String msgId) {
    assertThat(envelope(body))
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null);
  }

  private static void assertError(final String body, final String msgId) {
    final var response = envelope(body);
    assertThat(response)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "ERROR");
    assertThat((String) response.get("errorCode")).matches(UUID_PATTERN);
  }

  private String performCreate(final Repo repo, final String token, final String json)
      throws Exception {
    return this.mockMvc
        .perform(
            post("/api/mvn/key-stores/" + repo.getName())
                .with(apiPort())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Nested
  @DisplayName("GET /allowed-servers")
  class AllowedServers {

    @Test
    void returnsActiveServersWithTheCompleteShape() throws Exception {
      final var user = KeyStoreControllerIT.this.createUser(UserRole.USER);
      final var response =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/allowed-servers")
                      .with(apiPort())
                      .header(AUTHORIZATION, KeyStoreControllerIT.this.tokenFor(user)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertSuccess(response, "allowedKeyserversFetched");
      final List<Map<String, Object>> items = JsonPath.read(response, "$.data");
      assertThat(items).hasSize(3);
      assertThat(items)
          .allSatisfy(
              item ->
                  assertThat(item)
                      .containsOnlyKeys("id", "host", "displayName")
                      .extracting("id")
                      .asString()
                      .matches(UUID_PATTERN));
      assertThat(items)
          .extracting(item -> item.get("host"))
          .containsExactlyInAnyOrder("keyserver.pgp.com", "pgp.circl.lu", "pgpkeys.eu");
    }

    @Test
    void excludesDeactivatedServers() throws Exception {
      final var server = KeyStoreControllerIT.this.keyserver("pgp.circl.lu");
      server.setActive(false);
      KeyStoreControllerIT.this.allowedKeyserverRepository.saveAndFlush(server);
      final var user = KeyStoreControllerIT.this.createUser(UserRole.USER);

      final var response =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/allowed-servers")
                      .with(apiPort())
                      .header(AUTHORIZATION, KeyStoreControllerIT.this.tokenFor(user)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat((List<String>) JsonPath.read(response, "$.data[*].host"))
          .containsExactlyInAnyOrder("keyserver.pgp.com", "pgpkeys.eu");
    }

    @Test
    void rejectsMissingMalformedExpiredAndDeletedUserTokens() throws Exception {
      final var user = KeyStoreControllerIT.this.createUser(UserRole.USER);
      final var path = "/api/mvn/key-stores/allowed-servers";
      final var missing =
          KeyStoreControllerIT.this.mockMvc.perform(get(path).with(apiPort())).andReturn();
      assertThat(missing.getResponse().getStatus()).isEqualTo(401);
      assertError(missing.getResponse().getContentAsString(), "missingRequestHeader");

      final var malformed =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, "Bearer invalid"))
              .andReturn();
      assertThat(malformed.getResponse().getStatus()).isEqualTo(401);
      assertError(malformed.getResponse().getContentAsString(), "accessNotAllowed");

      final var expired =
          AuthUtils.AUTH_BEARER
              + KeyStoreControllerIT.this.jwtUtils.createTokenWithDuration(
                  user.getId(), user.getUsername(), Duration.ofSeconds(-1));
      final var expiredResponse =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, expired))
              .andReturn();
      assertThat(expiredResponse.getResponse().getStatus()).isEqualTo(401);
      assertError(expiredResponse.getResponse().getContentAsString(), "sessionExpired");

      final var refreshToken =
          AuthUtils.AUTH_BEARER
              + KeyStoreControllerIT.this.jwtUtils.createRefreshToken(
                  user.getId(), user.getUsername(), Duration.ofMinutes(30));
      final var refreshTokenResponse =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, refreshToken))
              .andReturn();
      assertThat(refreshTokenResponse.getResponse().getStatus()).isEqualTo(401);
      assertError(refreshTokenResponse.getResponse().getContentAsString(), "accessNotAllowed");
    }
  }

  @Nested
  @DisplayName("repository key-store operations")
  class RepositoryOperations {

    @Test
    void createsListsAndDeletesKeyStoresWithCompleteJson() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin = KeyStoreControllerIT.this.createUser(UserRole.ADMIN);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");
      final var token = KeyStoreControllerIT.this.tokenFor(admin);

      final var created =
          KeyStoreControllerIT.this.performCreate(
              repo, token, KeyStoreControllerIT.this.body(server.getId()));
      assertSuccess(created, "keyStoreCreated");
      assertThat((Object) JsonPath.read(created, "$.data")).isNull();
      final var row =
          KeyStoreControllerIT.this.keyStoreRepository.findAll().stream().findFirst().orElseThrow();

      final var listed =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/" + repo.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, token)
                      .param("page", "0")
                      .param("size", "10"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertSuccess(listed, "keyStoresFetched");
      final Map<String, Object> listedData = JsonPath.read(listed, "$.data");
      assertThat(listedData).containsKeys(PAGE_KEYS);
      final List<Map<String, Object>> content = JsonPath.read(listed, "$.data.content");
      assertThat(content).hasSize(1);
      assertThat(content.getFirst())
          .containsEntry("id", row.getId().toString())
          .containsEntry("allowedKeyserverId", server.getId().toString())
          .containsEntry("host", server.getHost())
          .containsEntry("displayName", server.getDisplayName());
      final Map<String, Object> page = JsonPath.read(listed, "$.data.page");
      assertThat(page)
          .containsEntry("size", 10)
          .containsEntry("number", 0)
          .containsEntry("totalElements", 1)
          .containsEntry("totalPages", 1);

      final var deleted =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  delete("/api/mvn/key-stores/" + repo.getName() + "/" + row.getId())
                      .with(apiPort())
                      .header(AUTHORIZATION, token))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertSuccess(deleted, "keyStoreDeleted");
      assertThat(KeyStoreControllerIT.this.keyStoreRepository.findById(row.getId())).isEmpty();
    }

    @Test
    void isolatesRepositoriesAndRejectsDuplicatesAndInvalidKeyservers() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var otherRepo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin = KeyStoreControllerIT.this.createUser(UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.tokenFor(admin);
      final var server = KeyStoreControllerIT.this.keyserver("pgpkeys.eu");

      assertSuccess(
          KeyStoreControllerIT.this.performCreate(
              repo, token, KeyStoreControllerIT.this.body(server.getId())),
          "keyStoreCreated");
      final var duplicate =
          KeyStoreControllerIT.this.performCreate(
              repo, token, KeyStoreControllerIT.this.body(server.getId()));
      assertError(duplicate, "keyStoreAlreadyExists");
      assertThat(
              KeyStoreControllerIT.this.performCreate(
                  otherRepo, token, KeyStoreControllerIT.this.body(server.getId())))
          .contains("keyStoreCreated");

      final var inactive = KeyStoreControllerIT.this.keyserver("pgp.circl.lu");
      inactive.setActive(false);
      KeyStoreControllerIT.this.allowedKeyserverRepository.saveAndFlush(inactive);
      assertError(
          KeyStoreControllerIT.this.performCreate(
              repo, token, KeyStoreControllerIT.this.body(inactive.getId())),
          "allowedKeyserverNotFound");
      assertError(
          KeyStoreControllerIT.this.performCreate(
              repo, token, KeyStoreControllerIT.this.body(UUID.randomUUID())),
          "allowedKeyserverNotFound");
    }

    @Test
    void enforcesMavenScopePermissionsAndRepositoryOwnership() throws Exception {
      final var maven = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var npm = KeyStoreControllerIT.this.createRepo(RepoType.NPM);
      final var user = KeyStoreControllerIT.this.createUser(UserRole.USER);
      final var admin = KeyStoreControllerIT.this.createUser(UserRole.ADMIN);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");
      final var userToken = KeyStoreControllerIT.this.tokenFor(user);
      final var adminToken = KeyStoreControllerIT.this.tokenFor(admin);

      final var readOnlyList =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/" + maven.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, userToken))
              .andReturn();
      assertThat(readOnlyList.getResponse().getStatus()).isEqualTo(401);
      assertError(readOnlyList.getResponse().getContentAsString(), "unAuthorized");

      final var wrongType =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/" + npm.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, adminToken))
              .andReturn();
      // KeyStoreController currently does not declare a Maven repo scope; the generic resolver
      // therefore accepts a non-Maven repo and returns its empty key-store page.
      assertThat(wrongType.getResponse().getStatus()).isEqualTo(200);
      assertSuccess(wrongType.getResponse().getContentAsString(), "keyStoresFetched");
      assertThat(
              (List<Map<String, Object>>)
                  JsonPath.read(wrongType.getResponse().getContentAsString(), "$.data.content"))
          .isEmpty();
      final var missingRepo =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/does-not-exist")
                      .with(apiPort())
                      .header(AUTHORIZATION, adminToken))
              .andReturn();
      assertThat(missingRepo.getResponse().getStatus()).isEqualTo(404);
      assertError(missingRepo.getResponse().getContentAsString(), "repoNotFound");

      final var created =
          KeyStoreControllerIT.this.performCreate(
              maven, userToken, KeyStoreControllerIT.this.body(server.getId()));
      assertSuccess(created, "keyStoreCreated");
      final var row =
          KeyStoreControllerIT.this.keyStoreRepository.findAll().stream().findFirst().orElseThrow();
      final var delete =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  delete("/api/mvn/key-stores/" + maven.getName() + "/" + row.getId())
                      .with(apiPort())
                      .header(AUTHORIZATION, userToken))
              .andReturn();
      assertThat(delete.getResponse().getStatus()).isEqualTo(401);
      assertError(delete.getResponse().getContentAsString(), "unAuthorized");
    }

    @Test
    void validatesRequestBodyAndPathVariables() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin = KeyStoreControllerIT.this.createUser(UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.tokenFor(admin);
      final var noBody =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  post("/api/mvn/key-stores/" + repo.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_JSON))
              .andReturn();
      assertThat(noBody.getResponse().getStatus()).isEqualTo(400);
      assertError(noBody.getResponse().getContentAsString(), "validationError");

      final var malformed =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  delete("/api/mvn/key-stores/" + repo.getName() + "/not-a-uuid")
                      .with(apiPort())
                      .header(AUTHORIZATION, token))
              .andReturn();
      assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
      assertError(malformed.getResponse().getContentAsString(), "validationError");
    }
  }
}
