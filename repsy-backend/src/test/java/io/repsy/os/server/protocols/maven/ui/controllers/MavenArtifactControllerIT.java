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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.RepsyApplication;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
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
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

/** End-to-end coverage for the Maven artifact-management API. */
@Testcontainers
@AutoConfigureMockMvc
@Transactional
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("MavenArtifactController /api/mvn/artifacts/*")
class MavenArtifactControllerIT {

  private static final int API_PORT = 8080;
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final String GROUP = "com.example.app";
  private static final String ARTIFACT = "demo";
  private static final String STORAGE_BASE_PATH = createTempStoragePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  @DynamicPropertySource
  static void registerDynamicProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", () -> STORAGE_BASE_PATH);
  }

  private static String createTempStoragePath() {
    try {
      return Files.createTempDirectory("repsy-maven-artifacts-it").toString();
    } catch (final IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtils jwtUtils;
  @Autowired private UserTxService userTxService;
  @Autowired private UserRepository userRepository;
  @Autowired private RepoTxService repoTxService;
  @Autowired private RepoRepository repoRepository;
  @Autowired private MavenApiFacade mavenApiFacade;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  @Value("${storage-gateway.fs.base-path}")
  private String storageBasePath;

  @PersistenceContext private EntityManager entityManager;

  private Repo repo;
  private User admin;
  private String repoName;

  @BeforeEach
  void setUp() throws IOException {
    this.admin = this.createUser(UserRole.ADMIN);
    this.repoName = "mvn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final var repoInfo =
        this.repoTxService.createRepo(this.repoName, RepoType.MAVEN, false, "Maven IT");
    this.repo = this.repoRepository.findById(repoInfo.getId()).orElseThrow();
    this.mavenApiFacade.createRepo(this.repo.getId());
    this.seedVersion("1.0.0", false);
    this.seedVersion("1.1.0-SNAPSHOT", true);
    this.entityManager.flush();
  }

  private User createUser(final UserRole role) {
    final var username = "mvn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    final var salt = PasswordGeneratorUtil.generateSalt();
    final var hash = PasswordGeneratorUtil.hashPassword("Password1!", salt);
    final var info = this.userTxService.create(username, role, hash, salt);
    this.entityManager.flush();
    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private void seedVersion(final String versionName, final boolean snapshot) throws IOException {
    var artifact =
        this.artifactRepository
            .findByRepoIdAndGroupNameAndArtifactName(this.repo.getId(), GROUP, ARTIFACT)
            .orElseGet(
                () -> {
                  final var created = new Artifact();
                  created.setRepo(this.repo);
                  created.setGroupName(GROUP);
                  created.setArtifactName(ARTIFACT);
                  created.setName(ARTIFACT);
                  created.setPackaging("jar");
                  created.setPlugin(false);
                  created.setLatest(versionName);
                  created.setRelease(snapshot ? null : versionName);
                  return this.artifactRepository.save(created);
                });
    artifact.setLatest(versionName);
    if (!snapshot) {
      artifact.setRelease(versionName);
    }
    artifact.setLastUpdatedAt(Instant.now());
    this.artifactRepository.saveAndFlush(artifact);
    final var version = new ArtifactVersion();
    version.setArtifact(artifact);
    version.setCreatedAt(Instant.now());
    version.setLastUpdatedAt(Instant.now());
    version.setType(snapshot ? ArtifactVersionType.SNAPSHOT : ArtifactVersionType.RELEASE);
    version.setVersionName(versionName);
    version.setName(ARTIFACT);
    version.setPackaging("jar");
    version.setHasSources(!snapshot);
    version.setHasDocuments(!snapshot);
    version.setHasModules(false);
    this.artifactVersionRepository.saveAndFlush(version);
    final Path pom =
        Path.of(
            this.storageBasePath,
            "maven",
            this.repo.getId().toString(),
            GROUP.replace('.', '/'),
            ARTIFACT,
            versionName,
            ARTIFACT + "-" + versionName + ".pom");
    Files.createDirectories(pom.getParent());
    Files.writeString(pom, pomXml(versionName), StandardCharsets.UTF_8);
    Files.writeString(
        pom.getParent().getParent().resolve("maven-metadata.xml"),
        metadataXml(),
        StandardCharsets.UTF_8);
    if (snapshot) {
      Files.writeString(
          pom.getParent().resolve("maven-metadata.xml"),
          snapshotMetadataXml(),
          StandardCharsets.UTF_8);
      Files.writeString(
          pom.getParent().resolve(ARTIFACT + "-1.1.0-20260918.000000-1.pom"),
          pomXml(versionName),
          StandardCharsets.UTF_8);
    }
  }

  private static String pomXml(final String version) {
    return "<project><modelVersion>4.0.0</modelVersion><groupId>"
        + GROUP
        + "</groupId><artifactId>"
        + ARTIFACT
        + "</artifactId><version>"
        + version
        + "</version><packaging>jar</packaging><name>Demo</name></project>";
  }

  private static String metadataXml() {
    return "<metadata><groupId>"
        + GROUP
        + "</groupId><artifactId>"
        + ARTIFACT
        + "</artifactId><versioning><latest>1.1.0-SNAPSHOT</latest><release>1.0.0</release>"
        + "<versions><version>1.0.0</version><version>1.1.0-SNAPSHOT</version></versions>"
        + "</versioning></metadata>";
  }

  private static String snapshotMetadataXml() {
    return "<metadata><groupId>"
        + GROUP
        + "</groupId><artifactId>"
        + ARTIFACT
        + "</artifactId><version>1.1.0-SNAPSHOT</version><versioning>"
        + "<snapshot><timestamp>20260918.000000</timestamp><buildNumber>1</buildNumber></snapshot>"
        + "<snapshotVersions><snapshotVersion><extension>pom</extension><value>"
        + "1.1.0-20260918.000000-1</value><updated>20260918000000</updated></snapshotVersion>"
        + "</snapshotVersions></versioning></metadata>";
  }

  private static RequestPostProcessor apiPort() {
    return request -> {
      request.setLocalPort(API_PORT);
      return request;
    };
  }

  private String bearerToken() {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createTokenWithDuration(
            this.admin.getId(), this.admin.getUsername(), Duration.ofMinutes(30));
  }

  @Nested
  @DisplayName("listing and version details")
  class Reads {

    @Test
    void listsArtifactsByGroupAndArtifactFilters() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get("/api/mvn/artifacts/{repo}", MavenArtifactControllerIT.this.repoName)
                  .param("groupName", "example")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("artifactsFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Artifacts have been fetched."))
          .andExpect(jsonPath("$.data.page.*", hasSize(4)))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].groupName").value(GROUP))
          .andExpect(jsonPath("$.data.content[0].artifactName").value(ARTIFACT))
          .andExpect(jsonPath("$.data.page.size").value(10))
          .andExpect(jsonPath("$.data.page.number").value(0))
          .andExpect(jsonPath("$.data.page.totalElements").value(1))
          .andExpect(jsonPath("$.data.page.totalPages").value(1));

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP)
                  .param("artifactName", "dem")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].artifactName").value(ARTIFACT));
    }

    @Test
    void returnsLatestVersionAndPagedVersionList() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT)
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("artifactVersionFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data.artifactName").value(ARTIFACT))
          .andExpect(jsonPath("$.data.artifactGroupName").value(GROUP))
          .andExpect(jsonPath("$.data.artifactVersionName").value("1.1.0-SNAPSHOT"))
          .andExpect(jsonPath("$.data.versionName").value("1.1.0-SNAPSHOT"))
          .andExpect(jsonPath("$.data.pomFile").value(notNullValue()));

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT)
                  .param("size", "1")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactVersionsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].versionName").value("1.1.0-SNAPSHOT"))
          .andExpect(jsonPath("$.data.page.size").value(1))
          .andExpect(jsonPath("$.data.page.totalElements").value(2))
          .andExpect(jsonPath("$.data.page.totalPages").value(2));
    }
  }

  @Nested
  @DisplayName("deletion")
  class Deletes {

    @Test
    void deletesOneVersionAndLeavesItsSibling() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT,
                      "1.0.0")
                  .header(AUTHORIZATION, MavenArtifactControllerIT.this.bearerToken())
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("artifactVersionDeleted"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.errorCode").value(nullValue()))
          .andExpect(jsonPath("$.text").value("Artifact version has been deleted."))
          .andExpect(jsonPath("$.data", notNullValue()));

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT)
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].versionName").value("1.1.0-SNAPSHOT"));
    }

    @Test
    void rejectsDeleteWithoutManageAuthorization() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/mvn/artifacts/{repo}/{group}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP)
                  .with(apiPort()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.msgId").value("unAuthorized"))
          .andExpect(jsonPath("$.data").value("unAuthorized"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(jsonPath("$.text").value(notNullValue()));
    }
  }

  @Nested
  @DisplayName("authentication and missing data")
  class Security {

    @Test
    void missingAuthorizationIsForbiddenForDelete() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT)
                  .with(apiPort()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.msgId").value("unAuthorized"))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value("unAuthorized"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(jsonPath("$.text").value("The user has logged in but has no permissions."));
    }

    @Test
    void unknownArtifactReturnsCompleteErrorEnvelope() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}/missing",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP)
                  .with(apiPort()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.*", hasSize(5)))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value("artifactNotFound"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(jsonPath("$.text").value(notNullValue()));
    }
  }
}
