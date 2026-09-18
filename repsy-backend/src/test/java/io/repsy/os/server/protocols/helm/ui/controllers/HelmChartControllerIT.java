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
package io.repsy.os.server.protocols.helm.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.auth.utils.JwtUtils;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.UserTxService;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full-stack integration tests for the Helm chart-management API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("HelmChartController /api/helm/charts/*")
class HelmChartControllerIT {

  private static final int API_PORT = 8080;
  private static final String PASSWORD = "Password1!";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String[] ENVELOPE_KEYS = {"msgId", "type", "data", "errorCode", "text"};

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", HelmChartControllerIT::tempStoragePath);
  }

  private static String tempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-helm-it").toString();
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmChartService helmChartService;
  @Autowired private HelmOciManifestService helmOciManifestService;
  @Autowired private HelmStorageService helmStorageService;
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
    final var userInfo = this.userTxService.create(unique("helm-user-"), role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private String bearerTokenFor(final User user) {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            user.getId(), user.getUsername(), Duration.ofMinutes(30));
  }

  private RepoInfo createRepo(final boolean privateRepo) {
    final var repo =
        this.repoTxService.createRepo(unique("helm-repo-"), RepoType.HELM, privateRepo, null);
    this.helmStorageService.createRepo(repo.getStorageKey());
    return repo;
  }

  private HelmChartInfo seedChart(
      final RepoInfo repo,
      final String name,
      final String version,
      final String description,
      final String appVersion,
      final boolean withTag)
      throws IOException {
    final byte[] content = (name + ":" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
    final var info =
        this.helmChartService.findOrCreate(
            HelmChartForm.builder()
                .name(name)
                .version(version)
                .description(description)
                .appVersion(appVersion)
                .type("application")
                .digest(digest)
                .size(content.length)
                .build(),
            repo.getStorageKey());
    this.entityManager.flush();
    this.helmStorageService.saveChart(
        repo.getName(),
        io.repsy.libs.storage.core.dtos.StoragePath.of(
            repo.getStorageKey(), "charts/" + name + "-" + version + ".tgz"),
        new ByteArrayInputStream(content));
    if (withTag) {
      this.helmOciManifestService.save(
          HelmOciManifestForm.builder()
              .chartId(info.id())
              .name(name)
              .reference(version)
              .digest(digest)
              .mediaType("application/vnd.cncf.helm.config.v1+json")
              .content("{\"name\":\"" + name + "\"}")
              .build(),
          repo.getStorageKey());
    }
    return info;
  }

  private static String body(final ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private static void assertSuccessEnvelope(final String body, final String msgId) {
    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("msgId", msgId)
        .containsEntry("type", "SUCCESS")
        .containsEntry("errorCode", null)
        .containsEntry("text", msgId);
  }

  private static void assertErrorEnvelope(
      final String body, final int status, final String expectedMsgId) {
    final Map<String, Object> envelope = JsonPath.read(body, "$");
    assertThat(envelope)
        .containsOnlyKeys(ENVELOPE_KEYS)
        .containsEntry("type", "ERROR")
        .containsEntry("msgId", expectedMsgId);
    assertThat((String) envelope.get("errorCode")).matches(UUID_PATTERN);
    assertThat(status).isBetween(400, 499);
  }

  @Test
  @DisplayName("covers search, versions, detail, OCI tags and complete DTO shapes")
  void readsAllChartViews() throws Exception {
    final var repo = this.createRepo(false);
    final var chart = this.seedChart(repo, "payments", "1.2.3", "Payment service", "2.0.0", true);
    this.seedChart(repo, "payments", "1.1.0", "Payment service", "1.9.0", false);
    this.seedChart(repo, "orders", "2.0.0-beta.1", "Order service", "3.0.0", false);
    final var admin = this.createUser(UserRole.ADMIN);
    final var auth = bearerTokenFor(admin);

    final var searchBody =
        body(
            this.mockMvc
                .perform(
                    get("/api/helm/charts/{repo}", repo.getName())
                        .with(apiPort())
                        .param("query", "PAY")
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.msgId").value("chartsFetched"))
                .andExpect(jsonPath("$.type").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("payments"))
                .andExpect(jsonPath("$.data.content[0].latestVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.page.size").value(10))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1)));
    assertSuccessEnvelope(searchBody, "chartsFetched");

    final var versionsBody =
        body(
            this.mockMvc
                .perform(
                    get("/api/helm/charts/{repo}/{name}", repo.getName(), "payments")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].version").isNotEmpty())
                .andExpect(jsonPath("$.data[0].appVersion").isNotEmpty())
                .andExpect(jsonPath("$.data[0].description").value("Payment service"))
                .andExpect(jsonPath("$.data[0].type").value("application"))
                .andExpect(jsonPath("$.data[0].digest").isNotEmpty())
                .andExpect(jsonPath("$.data[0].size").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty()));
    assertSuccessEnvelope(versionsBody, "chartVersionsFetched");

    final var detailBody =
        body(
            this.mockMvc
                .perform(
                    get(
                            "/api/helm/charts/{repo}/{name}/{version}",
                            repo.getName(),
                            "payments",
                            "1.2.3")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("payments"))
                .andExpect(jsonPath("$.data.version").value("1.2.3"))
                .andExpect(jsonPath("$.data.description").value("Payment service"))
                .andExpect(jsonPath("$.data.appVersion").value("2.0.0"))
                .andExpect(jsonPath("$.data.type").value("application"))
                .andExpect(jsonPath("$.data.digest").value(chart.digest()))
                .andExpect(jsonPath("$.data.size").value(chart.size()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lastUpdatedAt").isNotEmpty()));
    assertSuccessEnvelope(detailBody, "chartDetailFetched");

    final var tagsBody =
        body(
            this.mockMvc
                .perform(
                    get("/api/helm/charts/{repo}/{name}/tags", repo.getName(), "payments")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.contains("1.2.3"))));
    assertSuccessEnvelope(tagsBody, "chartTagsFetched");
  }

  @Test
  @DisplayName("enforces public/private authentication and MANAGE permissions")
  void enforcesAuthenticationRules() throws Exception {
    final var publicRepo = this.createRepo(false);
    final var privateRepo = this.createRepo(true);
    this.seedChart(publicRepo, "public-chart", "1.0.0", "Public", "1.0.0", false);
    this.seedChart(privateRepo, "private-chart", "1.0.0", "Private", "1.0.0", false);
    final var user = this.createUser(UserRole.USER);
    final var admin = this.createUser(UserRole.ADMIN);

    this.mockMvc
        .perform(get("/api/helm/charts/{repo}", publicRepo.getName()).with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].name").value("public-chart"));

    final var privateError =
        body(
            this.mockMvc
                .perform(get("/api/helm/charts/{repo}", privateRepo.getName()).with(apiPort()))
                .andExpect(status().isUnauthorized()));
    assertErrorEnvelope(privateError, 401, "unAuthorized");

    this.mockMvc
        .perform(
            get("/api/helm/charts/{repo}", privateRepo.getName())
                .with(apiPort())
                .header(AUTHORIZATION, bearerTokenFor(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].name").value("private-chart"));

    this.mockMvc
        .perform(
            delete("/api/helm/charts/{repo}/{name}", publicRepo.getName(), "public-chart")
                .with(apiPort())
                .header(AUTHORIZATION, bearerTokenFor(user)))
        .andExpect(status().isUnauthorized());

    final var missingHeader =
        body(
            this.mockMvc
                .perform(
                    get("/api/helm/charts/{repo}/{name}/{version}", privateRepo.getName(), "x", "1")
                        .with(apiPort()))
                .andExpect(status().isUnauthorized()));
    assertErrorEnvelope(missingHeader, 401, "unAuthorized");

    this.mockMvc
        .perform(
            delete("/api/helm/charts/{repo}/{name}", publicRepo.getName(), "public-chart")
                .with(apiPort())
                .header(AUTHORIZATION, bearerTokenFor(admin)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("deletes one version and all versions while preserving complete state transitions")
  void deletesVersionsAndCharts() throws Exception {
    final var repo = this.createRepo(false);
    this.seedChart(repo, "retained", "1.0.0", "Retained", "1.0.0", false);
    this.seedChart(repo, "retained", "1.1.0", "Retained", "1.1.0", false);
    this.seedChart(repo, "removed", "1.0.0", "Removed", "1.0.0", false);
    final var admin = this.createUser(UserRole.ADMIN);
    final var auth = bearerTokenFor(admin);

    final var singleDelete =
        body(
            this.mockMvc
                .perform(
                    delete(
                            "/api/helm/charts/{repo}/{name}/{version}",
                            repo.getName(),
                            "retained",
                            "1.0.0")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue())));
    assertSuccessEnvelope(singleDelete, "chartDeleted");

    this.mockMvc
        .perform(
            get("/api/helm/charts/{repo}/{name}", repo.getName(), "retained")
                .with(apiPort())
                .header(AUTHORIZATION, auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(1)))
        .andExpect(jsonPath("$.data[0].version").value("1.1.0"));

    final var allDelete =
        body(
            this.mockMvc
                .perform(
                    delete("/api/helm/charts/{repo}/{name}", repo.getName(), "removed")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue())));
    assertSuccessEnvelope(allDelete, "chartDeleted");

    final var missingChart =
        body(
            this.mockMvc
                .perform(
                    get("/api/helm/charts/{repo}/{name}", repo.getName(), "removed")
                        .with(apiPort())
                        .header(AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0))));
    assertSuccessEnvelope(missingChart, "chartVersionsFetched");

    this.mockMvc
        .perform(
            delete("/api/helm/charts/{repo}/{name}", repo.getName(), "removed")
                .with(apiPort())
                .header(AUTHORIZATION, auth))
        .andExpect(status().isNotFound());
  }
}
