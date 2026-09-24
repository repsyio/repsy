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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.entities.AllowedKeyserver;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.AllowedKeyserverRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.KeyStoreRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.repositories.PgpPublicKeyRepository;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** Full-stack integration tests for the Maven key-store API. */
@DisplayName("KeyStoreController /api/mvn/key-stores/*")
class KeyStoreControllerIT extends AbstractIntegrationTest {

  private static final String[] KEY_STORE_KEYS = {
    "id", "allowedKeyserverId", "host", "displayName"
  };
  private static final String[] PAGE_KEYS = {"content", "page"};

  @Autowired private RepoTxService repoTxService;
  @Autowired private AllowedKeyserverRepository allowedKeyserverRepository;
  @Autowired private KeyStoreRepository keyStoreRepository;
  @Autowired private PgpPublicKeyRepository pgpPublicKeyRepository;
  @Autowired private ObjectMapper objectMapper;

  private static String unique(final String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);
      final var response =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/allowed-servers")
                      .with(apiPort())
                      .header(AUTHORIZATION, KeyStoreControllerIT.this.bearerTokenFor(user)))
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
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);

      final var response =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/allowed-servers")
                      .with(apiPort())
                      .header(AUTHORIZATION, KeyStoreControllerIT.this.bearerTokenFor(user)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat((List<String>) JsonPath.read(response, "$.data[*].host"))
          .containsExactlyInAnyOrder("keyserver.pgp.com", "pgpkeys.eu");
    }

    @Test
    void rejectsMissingMalformedExpiredAndDeletedUserTokens() throws Exception {
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);
      final var path = "/api/mvn/key-stores/allowed-servers";
      final var missing =
          KeyStoreControllerIT.this.mockMvc.perform(get(path).with(apiPort())).andReturn();
      assertThat(missing.getResponse().getStatus()).isEqualTo(401);
      assertThat(missing.getResponse().getHeader(WWW_AUTHENTICATE)).isEqualTo("Bearer");
      assertError(missing.getResponse().getContentAsString(), "missingRequestHeader");

      final var malformed =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, "Bearer invalid"))
              .andReturn();
      assertThat(malformed.getResponse().getStatus()).isEqualTo(401);
      assertThat(malformed.getResponse().getHeader(WWW_AUTHENTICATE)).isEqualTo("Bearer");
      assertError(malformed.getResponse().getContentAsString(), "accessNotAllowed");

      final var expired =
          AuthUtils.AUTH_BEARER
              + KeyStoreControllerIT.this.jwtUtils.createPanelAccessToken(
                  user.getId(), user.getUsername(), Duration.ofSeconds(-1));
      final var expiredResponse =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, expired))
              .andReturn();
      assertThat(expiredResponse.getResponse().getStatus()).isEqualTo(401);
      assertThat(expiredResponse.getResponse().getHeader(WWW_AUTHENTICATE)).isEqualTo("Bearer");
      assertError(expiredResponse.getResponse().getContentAsString(), "sessionExpired");

      final var refreshToken =
          AuthUtils.AUTH_BEARER
              + KeyStoreControllerIT.this.jwtUtils.createRefreshToken(
                  user.getId(), user.getUsername(), Duration.ofMinutes(30), Instant.now(), 0);
      final var refreshTokenResponse =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(get(path).with(apiPort()).header(AUTHORIZATION, refreshToken))
              .andReturn();
      assertThat(refreshTokenResponse.getResponse().getStatus()).isEqualTo(401);
      assertThat(refreshTokenResponse.getResponse().getHeader(WWW_AUTHENTICATE))
          .isEqualTo("Bearer");
      assertError(refreshTokenResponse.getResponse().getContentAsString(), "accessNotAllowed");
    }
  }

  @Nested
  @DisplayName("repository key-store operations")
  class RepositoryOperations {

    @Test
    void createsListsAndDeletesKeyStoresWithCompleteJson() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);

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
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
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
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");
      final var userToken = KeyStoreControllerIT.this.bearerTokenFor(user);
      final var adminToken = KeyStoreControllerIT.this.bearerTokenFor(admin);

      final var readOnlyList =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/" + maven.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, userToken))
              .andReturn();
      assertThat(readOnlyList.getResponse().getStatus()).isEqualTo(403);
      assertError(readOnlyList.getResponse().getContentAsString(), "accessDenied");

      final var wrongType =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  get("/api/mvn/key-stores/" + npm.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, adminToken))
              .andReturn();
      // RPS-1203: KeyStoreController now declares a Maven repo scope, so a non-Maven repo is
      // rejected before the key-store page is ever reached.
      assertThat(wrongType.getResponse().getStatus()).isEqualTo(400);
      assertError(wrongType.getResponse().getContentAsString(), "repoScopeNotMatched");

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

      // RPS-1201: create now needs MANAGE, so a WRITE-only (USER-role) caller is refused, same as
      // list and delete already were.
      final var createByWriteOnlyUser =
          KeyStoreControllerIT.this.performCreate(
              maven, userToken, KeyStoreControllerIT.this.body(server.getId()));
      assertError(createByWriteOnlyUser, "accessDenied");
      assertThat(KeyStoreControllerIT.this.keyStoreRepository.findAll()).isEmpty();

      final var created =
          KeyStoreControllerIT.this.performCreate(
              maven, adminToken, KeyStoreControllerIT.this.body(server.getId()));
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
      assertThat(delete.getResponse().getStatus()).isEqualTo(403);
      assertError(delete.getResponse().getContentAsString(), "accessDenied");
    }

    @Test
    @DisplayName(
        "a WRITE-only (USER-role) caller is refused to create a key store, like it is for MANAGE-only verbs")
    void aWriteOnlyUserIsRefusedToCreateAKeyStore() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);
      final var userToken = KeyStoreControllerIT.this.bearerTokenFor(user);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");

      final var response =
          KeyStoreControllerIT.this
              .mockMvc
              .perform(
                  post("/api/mvn/key-stores/" + repo.getName())
                      .with(apiPort())
                      .header(AUTHORIZATION, userToken)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(KeyStoreControllerIT.this.body(server.getId())))
              .andReturn();

      assertThat(response.getResponse().getStatus()).isEqualTo(403);
      assertError(response.getResponse().getContentAsString(), "accessDenied");
      assertThat(KeyStoreControllerIT.this.keyStoreRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName(
        "a non-Maven repo is rejected with repoScopeNotMatched before creating a key store")
    void rejectsANonMavenRepoOnCreate() throws Exception {
      final var npm = KeyStoreControllerIT.this.createRepo(RepoType.NPM);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var adminToken = KeyStoreControllerIT.this.bearerTokenFor(admin);
      final var server = KeyStoreControllerIT.this.keyserver("keyserver.pgp.com");

      final var created =
          KeyStoreControllerIT.this.performCreate(
              npm, adminToken, KeyStoreControllerIT.this.body(server.getId()));

      assertError(created, "repoScopeNotMatched");
      assertThat(KeyStoreControllerIT.this.keyStoreRepository.findAll()).isEmpty();
    }

    @Test
    void validatesRequestBodyAndPathVariables() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
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

  @Nested
  @DisplayName("repository PGP public keys (RPS-1189)")
  class PublicKeys {

    // PgpTestKeys carries no OpenPGP user id packet, so a created item's "userId" is always null
    // and the envelope omits it (JSON omits nulls throughout this API), never listed here.
    private static final String[] PGP_PUBLIC_KEY_KEYS = {"id", "keyId", "fingerprint", "createdAt"};

    private String armoredKeyBody(final String armoredKey) throws Exception {
      return KeyStoreControllerIT.this.objectMapper.writeValueAsString(
          Map.of("armoredKey", armoredKey));
    }

    private ResultActions createPublicKey(final Repo repo, final String token, final String body)
        throws Exception {
      return KeyStoreControllerIT.this.mockMvc.perform(
          post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
              .with(apiPort())
              .header(AUTHORIZATION, token)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body));
    }

    private ResultActions listPublicKeys(final Repo repo, final String token) throws Exception {
      return KeyStoreControllerIT.this.mockMvc.perform(
          get("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
              .with(apiPort())
              .header(AUTHORIZATION, token)
              .param("page", "0")
              .param("size", "10"));
    }

    private ResultActions deletePublicKey(final Repo repo, final String token, final UUID id)
        throws Exception {
      return KeyStoreControllerIT.this.mockMvc.perform(
          delete("/api/mvn/key-stores/" + repo.getName() + "/public-keys/" + id)
              .with(apiPort())
              .header(AUTHORIZATION, token));
    }

    @Test
    void createsListsAndDeletesPublicKeysWithCompleteJson() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
      final var key = PgpTestKeys.generate();

      final var createdResult =
          this.createPublicKey(repo, token, this.armoredKeyBody(key.armoredPublicKey()))
              .andExpect(status().isOk())
              .andReturn();
      final var created = createdResult.getResponse().getContentAsString();
      assertSuccess(created, "pgpPublicKeyCreated");
      final Map<String, Object> data = JsonPath.read(created, "$.data");
      assertThat(data).containsOnlyKeys(PGP_PUBLIC_KEY_KEYS);
      assertThat((String) data.get("keyId")).isEqualTo("%016X".formatted(key.keyId()));
      assertThat((String) data.get("id")).matches(UUID_PATTERN);
      assertThat(data.get("userId")).isNull();

      final var row =
          KeyStoreControllerIT.this.pgpPublicKeyRepository.findAll().stream()
              .filter(k -> k.getId().toString().equals(data.get("id")))
              .findFirst()
              .orElseThrow();
      assertThat(row.getFingerprint()).isEqualTo(data.get("fingerprint"));

      final var listed =
          this.listPublicKeys(repo, token)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertSuccess(listed, "pgpPublicKeysFetched");
      final Map<String, Object> listedData = JsonPath.read(listed, "$.data");
      assertThat(listedData).containsKeys(PAGE_KEYS);
      final List<Map<String, Object>> content = JsonPath.read(listed, "$.data.content");
      assertThat(content).hasSize(1);
      assertThat(content.getFirst()).containsOnlyKeys(PGP_PUBLIC_KEY_KEYS);

      final var deleted =
          this.deletePublicKey(repo, token, row.getId())
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertSuccess(deleted, "pgpPublicKeyDeleted");
      assertThat(KeyStoreControllerIT.this.pgpPublicKeyRepository.findById(row.getId())).isEmpty();
    }

    @Test
    void rejectsADuplicateFingerprintOnTheSameRepoButAllowsItOnAnother() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var otherRepo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
      final var key = PgpTestKeys.generate();
      final var body = this.armoredKeyBody(key.armoredPublicKey());

      this.createPublicKey(repo, token, body).andExpect(status().isOk());

      final var duplicate = this.createPublicKey(repo, token, body).andReturn();
      assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
      assertError(duplicate.getResponse().getContentAsString(), "pgpPublicKeyAlreadyExists");

      final var onOtherRepo = this.createPublicKey(otherRepo, token, body).andReturn();
      assertThat(onOtherRepo.getResponse().getStatus()).isEqualTo(200);
      assertSuccess(onOtherRepo.getResponse().getContentAsString(), "pgpPublicKeyCreated");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
        "io.repsy.os.server.protocols.maven.ui.controllers.KeyStoreControllerIT#invalidPublicKeyBodies")
    @DisplayName(
        "refuses a body that is not exactly one armored public key with pgpPublicKeyInvalid")
    void refusesAnInvalidArmoredKey(final String description, final String armoredKey)
        throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);

      final var result =
          this.createPublicKey(repo, token, this.armoredKeyBody(armoredKey)).andReturn();

      assertThat(result.getResponse().getStatus()).as(description).isEqualTo(400);
      assertError(result.getResponse().getContentAsString(), "pgpPublicKeyInvalid");
    }

    @Test
    @DisplayName("refuses an empty or missing armoredKey with validationError")
    void refusesAnEmptyOrMissingArmoredKey() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);

      final var empty = this.createPublicKey(repo, token, this.armoredKeyBody("")).andReturn();
      assertThat(empty.getResponse().getStatus()).isEqualTo(400);
      assertError(empty.getResponse().getContentAsString(), "validationError");

      final var missing = this.createPublicKey(repo, token, "{}").andReturn();
      assertThat(missing.getResponse().getStatus()).isEqualTo(400);
      assertError(missing.getResponse().getContentAsString(), "validationError");
    }

    @Test
    @DisplayName("a USER-role caller is refused for create, list and delete")
    void aUserRoleCallerIsRefusedForCreateListAndDelete() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("admin"), UserRole.ADMIN);
      final var adminToken = KeyStoreControllerIT.this.bearerTokenFor(admin);
      final var user = KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.USER);
      final var userToken = KeyStoreControllerIT.this.bearerTokenFor(user);
      final var key = PgpTestKeys.generate();
      final var created =
          this.createPublicKey(repo, adminToken, this.armoredKeyBody(key.armoredPublicKey()))
              .andReturn();
      final String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

      final var createAttempt =
          this.createPublicKey(
                  repo, userToken, this.armoredKeyBody(PgpTestKeys.generate().armoredPublicKey()))
              .andReturn();
      assertThat(createAttempt.getResponse().getStatus()).isEqualTo(403);
      assertError(createAttempt.getResponse().getContentAsString(), "accessDenied");

      final var listAttempt = this.listPublicKeys(repo, userToken).andReturn();
      assertThat(listAttempt.getResponse().getStatus()).isEqualTo(403);
      assertError(listAttempt.getResponse().getContentAsString(), "accessDenied");

      final var deleteAttempt =
          this.deletePublicKey(repo, userToken, UUID.fromString(id)).andReturn();
      assertThat(deleteAttempt.getResponse().getStatus()).isEqualTo(403);
      assertError(deleteAttempt.getResponse().getContentAsString(), "accessDenied");
    }

    @Test
    @DisplayName("a non-Maven repo is rejected with repoScopeNotMatched before listing public keys")
    void rejectsANonMavenRepoOnListPublicKeys() throws Exception {
      final var npm = KeyStoreControllerIT.this.createRepo(RepoType.NPM);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);

      final var result = this.listPublicKeys(npm, token).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(400);
      assertError(result.getResponse().getContentAsString(), "repoScopeNotMatched");
    }

    @Test
    @DisplayName("deleting another repo's key id answers 404 pgpPublicKeyNotFound")
    void deletingAnotherReposKeyIdIsNotFound() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var otherRepo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
      final var key = PgpTestKeys.generate();
      final var created =
          this.createPublicKey(otherRepo, token, this.armoredKeyBody(key.armoredPublicKey()))
              .andReturn();
      final String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

      final var result = this.deletePublicKey(repo, token, UUID.fromString(id)).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertError(result.getResponse().getContentAsString(), "pgpPublicKeyNotFound");
      assertThat(KeyStoreControllerIT.this.pgpPublicKeyRepository.findById(UUID.fromString(id)))
          .isPresent();
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"id", "keyId", "fingerprint", "userId", "createdAt"})
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String property) throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);
      this.createPublicKey(
              repo, token, this.armoredKeyBody(PgpTestKeys.generate().armoredPublicKey()))
          .andExpect(status().isOk());

      KeyStoreControllerIT.this
          .mockMvc
          .perform(
              get("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .param("sort", property + ",asc"))
          .andExpect(status().isOk());
      KeyStoreControllerIT.this
          .mockMvc
          .perform(
              get("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .param("sort", property + ",desc"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400() throws Exception {
      final var repo = KeyStoreControllerIT.this.createRepo(RepoType.MAVEN);
      final var admin =
          KeyStoreControllerIT.this.createUser(uniqueUsername("user"), UserRole.ADMIN);
      final var token = KeyStoreControllerIT.this.bearerTokenFor(admin);

      PagingAssertions.expectInvalidParameter(
          KeyStoreControllerIT.this.mockMvc.perform(
              get("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                  .with(apiPort())
                  .header(AUTHORIZATION, token)
                  .param("sort", PagingAssertions.UNKNOWN_SORT)),
          "sort");
    }
  }

  /**
   * Bodies {@link PublicKeys#refusesAnInvalidArmoredKey} must refuse with {@code
   * pgpPublicKeyInvalid}.
   */
  static Stream<Arguments> invalidPublicKeyBodies() {
    final var signatureBlock =
        PgpTestKeys.generate().detachedSignature("anything".getBytes(StandardCharsets.UTF_8));
    final var privateKeyBlock =
        "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\n" + PgpTestKeys.generate().armoredPublicKey();

    return Stream.of(
        Arguments.of("plain text", "not a pgp key at all"),
        Arguments.of("a signature block", signatureBlock),
        Arguments.of("a private-key block", privateKeyBlock));
  }

  @Nested
  @DisplayName("paging and sorting of the key-store list")
  class PagingAndSorting {

    private ResultActions list(
        final Repo repo, final String token, final String param, final String value)
        throws Exception {
      return KeyStoreControllerIT.this.mockMvc.perform(
          get("/api/mvn/key-stores/" + repo.getName())
              .with(apiPort())
              .header(AUTHORIZATION, token)
              .param(param, value));
    }

    private Repo seededRepo(final String token) throws Exception {
      final var it = KeyStoreControllerIT.this;
      final var repo = it.createRepo(RepoType.MAVEN);
      final var server = it.keyserver("keyserver.pgp.com");
      assertSuccess(it.performCreate(repo, token, it.body(server.getId())), "keyStoreCreated");
      return repo;
    }

    @ParameterizedTest(name = "sort={0}")
    @ValueSource(strings = {"id", "host", "displayName"})
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String property) throws Exception {
      final var it = KeyStoreControllerIT.this;
      final var token = it.bearerTokenFor(it.createUser(uniqueUsername("user"), UserRole.ADMIN));
      final var repo = this.seededRepo(token);

      this.list(repo, token, "sort", property + ",asc").andExpect(status().isOk());
      this.list(repo, token, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400() throws Exception {
      final var it = KeyStoreControllerIT.this;
      final var token = it.bearerTokenFor(it.createUser(uniqueUsername("user"), UserRole.ADMIN));
      final var repo = this.seededRepo(token);

      PagingAssertions.expectInvalidParameter(
          this.list(repo, token, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("io.repsy.os.PagingAssertions#invalidPagingParams")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String param, final String value) throws Exception {
      final var it = KeyStoreControllerIT.this;
      final var token = it.bearerTokenFor(it.createUser(uniqueUsername("user"), UserRole.ADMIN));
      final var repo = this.seededRepo(token);

      PagingAssertions.expectInvalidParameter(this.list(repo, token, param, value), param);
    }
  }
}
