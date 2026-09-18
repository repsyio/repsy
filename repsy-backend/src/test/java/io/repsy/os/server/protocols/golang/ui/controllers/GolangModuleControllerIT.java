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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration tests for the Go module-management API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("GolangModuleController /api/go/modules/*")
class GolangModuleControllerIT {

  private static final int API_PORT = 8080;
  private static final int PROTOCOL_PORT = 9090;
  private static final String VALID_PASSWORD = "Password1!";
  private static final String MODULE = "io.repsy/hello-world";
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
    registry.add("storage-gateway.fs.base-path", GolangModuleControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-go-modules-it").toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private GolangApiFacade golangApiFacade;
  @PersistenceContext private EntityManager entityManager;

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

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

  private User createUser() {
    return this.createUser(UserRole.USER);
  }

  private User createUser(final UserRole role) {
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword(VALID_PASSWORD, salt);
    final var userInfo = this.userTxService.create(unique("gomod"), role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private String bearerToken(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
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
    this.mockMvc
        .perform(
            put("/{repo}/{module}/@v/{version}", repoName, MODULE, version)
                .with(protocolPort())
                .header(AUTHORIZATION, token)
                .contentType("application/zip")
                .content(moduleZip(version)))
        .andExpect(status().isOk());
  }

  private static byte[] moduleZip(final String version) {
    try {
      final var output = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(output)) {
        final var prefix = MODULE + "@" + version + "/";
        putEntry(zip, prefix + "go.mod", "module " + MODULE + "\n\ngo 1.23\n");
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
    final var user = this.createUser();
    final var token = this.bearerToken(user);
    final var repo = this.createRepo(unique("go"), true);
    this.upload(repo, "v1.0.0", token);
    this.upload(repo, "v1.2.0", token);

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
        .andExpect(jsonPath("$.text").value("moduleInfoFetched"));
  }

  @Test
  @DisplayName("pins checksum support and authorization behavior")
  void handlesAuthAndSumdb() throws Exception {
    final var user = this.createUser();
    final var token = this.bearerToken(user);
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
    final var user = this.createUser(UserRole.ADMIN);
    final var token = this.bearerToken(user);
    final var repo = this.createRepo(unique("go"), true);
    this.upload(repo, "v1.0.0", token);
    this.upload(repo, "v1.2.0", token);

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
}
