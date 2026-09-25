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

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.ui.facades.MavenApiFacade;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end coverage for the Maven artifact-management API. */
@DisplayName("MavenArtifactController /api/mvn/artifacts/*")
class MavenArtifactControllerIT extends AbstractIntegrationTest {

  private static final String GROUP = "com.example.app";
  private static final String ARTIFACT = "demo";

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenApiFacade mavenApiFacade;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  @Value("${storage-gateway.fs.base-path}")
  private String storageBasePath;

  private Repo repo;
  private User admin;
  private String repoName;

  @BeforeEach
  void setUp() throws IOException {
    this.admin = this.createUser(uniqueUsername("mvn"), UserRole.ADMIN);
    this.repoName = "mvn" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final var repoInfo =
        this.repoTxService.createRepo(this.repoName, RepoType.MAVEN, false, "Maven IT");
    this.repo = this.repoRepository.findById(repoInfo.getId()).orElseThrow();
    this.mavenApiFacade.createRepo(this.repo.getId());
    this.seedVersion("1.0.0", false);
    this.seedVersion("1.1.0-SNAPSHOT", true);
    this.entityManager.flush();
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

  /** A further artifact, DB rows only: the summary counts rows and reads no storage. */
  private void seedExtraArtifact(
      final String group, final String artifactName, final String... versions) {
    final var artifact = new Artifact();
    artifact.setRepo(this.repo);
    artifact.setGroupName(group);
    artifact.setArtifactName(artifactName);
    artifact.setName(artifactName);
    artifact.setPackaging("jar");
    artifact.setPlugin(false);
    artifact.setLatest(versions[versions.length - 1]);
    artifact.setRelease(versions[versions.length - 1]);
    artifact.setLastUpdatedAt(Instant.now());
    this.artifactRepository.saveAndFlush(artifact);
    for (final var versionName : versions) {
      final var version = new ArtifactVersion();
      version.setArtifact(artifact);
      version.setCreatedAt(Instant.now());
      version.setLastUpdatedAt(Instant.now());
      version.setType(ArtifactVersionType.RELEASE);
      version.setVersionName(versionName);
      version.setName(artifactName);
      version.setPackaging("jar");
      version.setHasSources(false);
      version.setHasDocuments(false);
      version.setHasModules(false);
      this.artifactVersionRepository.saveAndFlush(version);
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

  private String bearerToken() {
    return AuthUtils.AUTH_BEARER
        + this.jwtUtils.createPanelAccessToken(
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
                  .param("q", "example")
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
                  .param("q", "dem")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactsFetched"))
          .andExpect(jsonPath("$.data.content", hasSize(1)))
          .andExpect(jsonPath("$.data.content[0].artifactName").value(ARTIFACT));
    }

    /**
     * RPS-1288: the search matches the whole {@code group:artifact} key a list row shows, next to
     * its parts, so typing the pair finds the row.
     */
    @Test
    void searchMatchesTheWholeGroupArtifactKeyAndItsParts() throws Exception {
      final var key = GROUP + ":" + ARTIFACT;
      for (final var term : new String[] {key, GROUP, ARTIFACT, "app:dem"}) {
        MavenArtifactControllerIT.this
            .mockMvc
            .perform(
                get("/api/mvn/artifacts/{repo}", MavenArtifactControllerIT.this.repoName)
                    .param("q", term)
                    .with(apiPort()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(1)))
            .andExpect(jsonPath("$.data.content[0].artifactName").value(ARTIFACT));
      }
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP)
                  .param("q", key)
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(1)));
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get("/api/mvn/artifacts/{repo}", MavenArtifactControllerIT.this.repoName)
                  .param("q", ARTIFACT + ":" + GROUP)
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    /**
     * RPS-1288: the delete confirmation of a group says what goes with it, so the summary counts
     * the group's artifacts and the versions of all of them, and nothing of another group.
     */
    @Test
    void groupSummaryCountsTheArtifactsAndVersionsOfExactlyThatGroup() throws Exception {
      MavenArtifactControllerIT.this.seedExtraArtifact(GROUP, "second", "1.0", "2.0", "3.0");
      MavenArtifactControllerIT.this.seedExtraArtifact(GROUP + ".sub", "nested", "9.9");

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get("/api/mvn/groups/{repo}/{group}", MavenArtifactControllerIT.this.repoName, GROUP)
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("groupSummaryFetched"))
          .andExpect(jsonPath("$.type").value("SUCCESS"))
          .andExpect(jsonPath("$.data.groupName").value(GROUP))
          .andExpect(jsonPath("$.data.artifactCount").value(2))
          // demo: 1.0.0 and 1.1.0-SNAPSHOT, second: three
          .andExpect(jsonPath("$.data.versionCount").value(5));

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/groups/{repo}/{group}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP + ".sub")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.artifactCount").value(1))
          .andExpect(jsonPath("$.data.versionCount").value(1));
    }

    @Test
    void groupSummaryOfAnUnknownGroupIsNotFound() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/groups/{repo}/{group}",
                      MavenArtifactControllerIT.this.repoName,
                      "com.nobody.published")
                  .with(apiPort()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value("groupNotFound"));
    }

    /**
     * RPS-1296: the detail of a version that is not the artifact's latest must describe that
     * version. {@code artifactVersionName} feeds the dependency snippets and the detail page's
     * Delete button, so the latest version's name there deleted the wrong version.
     */
    @Test
    void detailOfAnOlderVersionNamesThatVersionNotTheLatest() throws Exception {
      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              get(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT,
                      "1.0.0")
                  .with(apiPort()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactVersionFetched"))
          .andExpect(jsonPath("$.data.artifactName").value(ARTIFACT))
          .andExpect(jsonPath("$.data.artifactGroupName").value(GROUP))
          .andExpect(jsonPath("$.data.artifactVersionName").value("1.0.0"))
          .andExpect(jsonPath("$.data.versionName").value("1.0.0"));
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

    /**
     * RPS-1296: the detail page deletes the version whose name the detail response carries in
     * {@code artifactVersionName}. Replay that flow for the older of two versions and check that
     * the latest one survives.
     */
    @Test
    void deleteFromTheDetailOfAnOlderVersionRemovesExactlyThatVersion() throws Exception {
      final var detail =
          MavenArtifactControllerIT.this
              .mockMvc
              .perform(
                  get(
                          "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                          MavenArtifactControllerIT.this.repoName,
                          GROUP,
                          ARTIFACT,
                          "1.0.0")
                      .with(apiPort()))
              .andExpect(status().isOk())
              .andReturn();
      final String shownVersion =
          JsonPath.read(detail.getResponse().getContentAsString(), "$.data.artifactVersionName");

      MavenArtifactControllerIT.this
          .mockMvc
          .perform(
              delete(
                      "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                      MavenArtifactControllerIT.this.repoName,
                      GROUP,
                      ARTIFACT,
                      shownVersion)
                  .header(AUTHORIZATION, MavenArtifactControllerIT.this.bearerToken())
                  .with(apiPort()))
          .andExpect(status().isOk());

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
          .andExpect(jsonPath("$.msgId").value("loginRequired"))
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
          .andExpect(jsonPath("$.msgId").value("loginRequired"))
          .andExpect(jsonPath("$.type").value("ERROR"))
          .andExpect(jsonPath("$.data").value("unAuthorized"))
          .andExpect(jsonPath("$.errorCode").value(matchesPattern(UUID_PATTERN)))
          .andExpect(
              jsonPath("$.text")
                  .value(
                      "Please log in: the credentials are missing or invalid, or the account is gone."));
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

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String ARTIFACTS = "/api/mvn/artifacts/{repo}";
    private static final String GROUP_ARTIFACTS = "/api/mvn/artifacts/{repo}/{group}";
    private static final String VERSIONS = "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions";
    private static final String VERSIONS_LIKE = VERSIONS + "?q=1";

    static Stream<String> endpoints() {
      return Stream.of(ARTIFACTS, GROUP_ARTIFACTS, VERSIONS, VERSIONS_LIKE);
    }

    static Stream<Arguments> acceptedSorts() {
      final var artifactSorts =
          Stream.of(ARTIFACTS, GROUP_ARTIFACTS)
              .flatMap(
                  path ->
                      Stream.of("id", "groupName", "artifactName", "lastUpdatedAt")
                          .map(property -> Arguments.of(path, property)));
      final var versionSorts =
          Stream.of(VERSIONS, VERSIONS_LIKE)
              .flatMap(
                  path ->
                      Stream.of("id", "versionName", "lastUpdatedAt")
                          .map(property -> Arguments.of(path, property)));

      return Stream.concat(artifactSorts, versionSorts);
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private ResultActions list(final String path, final String param, final String value)
        throws Exception {
      final var it = MavenArtifactControllerIT.this;

      return it.mockMvc.perform(
          get(path, it.repoName, GROUP, ARTIFACT).param(param, value).with(apiPort()));
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      this.list(path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the versions by the requested sort property")
    void ordersVersions() throws Exception {
      this.list(VERSIONS, "sort", "versionName,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].versionName").value("1.0.0"));
      this.list(VERSIONS, "sort", "versionName,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].versionName").value("1.1.0-SNAPSHOT"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({ARTIFACTS + ",groupName", GROUP_ARTIFACTS + ",artifactName", VERSIONS + ",version"})
    @DisplayName("filters by q only: the old name of the filter is an unknown parameter")
    void filtersByQOnly(final String path, final String oldName) throws Exception {
      PagingAssertions.expectFilterIsQ(
          this.list(path, "page", "0"),
          this.list(path, oldName, PagingAssertions.NO_MATCH),
          this.list(path, "q", PagingAssertions.NO_MATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(this.list(path, param, value), param);
    }
  }

  /**
   * A snapshot of its own artifact, as sbt and Ivy leave it: the POM and jar files, no {@code
   * maven-metadata.xml} at any level. It sits under another artifactId than {@code demo}, so the
   * counts of the other tests hold.
   */
  private void seedSnapshotWithoutMetadata(
      final String artifactName, final String versionName, final Map<String, String> files)
      throws IOException {
    seedExtraArtifact(GROUP, artifactName, versionName);
    final var version =
        this.artifactVersionRepository
            .findByArtifactIdAndVersionName(
                this.artifactRepository
                    .findByRepoIdAndGroupNameAndArtifactName(this.repo.getId(), GROUP, artifactName)
                    .orElseThrow()
                    .getId(),
                versionName)
            .orElseThrow();
    version.setType(ArtifactVersionType.SNAPSHOT);
    this.artifactVersionRepository.saveAndFlush(version);

    final Path versionDir =
        Path.of(
            this.storageBasePath,
            "maven",
            this.repo.getId().toString(),
            GROUP.replace('.', '/'),
            artifactName,
            versionName);
    Files.createDirectories(versionDir);

    for (final var file : files.entrySet()) {
      Files.writeString(versionDir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
    }
  }

  private ResultActions versionDetail(final String artifactName, final String versionName)
      throws Exception {
    return this.mockMvc.perform(
        get(
                "/api/mvn/artifacts/{repo}/{group}/{artifact}/versions/{version}",
                this.repoName,
                GROUP,
                artifactName,
                versionName)
            .with(apiPort()));
  }

  @Nested
  @DisplayName("a snapshot deployed without maven-metadata.xml (RPS-1370)")
  class SnapshotWithoutMetadata {

    @Test
    @DisplayName("the detail shows the literal POM, as sbt and Ivy leave it")
    void showsTheLiteralPom() throws Exception {
      MavenArtifactControllerIT.this.seedSnapshotWithoutMetadata(
          "sbtlib",
          "2.0-SNAPSHOT",
          Map.of(
              "sbtlib-2.0-SNAPSHOT.pom", "<project>literal pom</project>",
              "sbtlib-2.0-SNAPSHOT.jar", "jar bytes"));

      MavenArtifactControllerIT.this
          .versionDetail("sbtlib", "2.0-SNAPSHOT")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactVersionFetched"))
          .andExpect(jsonPath("$.data.versionName").value("2.0-SNAPSHOT"))
          .andExpect(jsonPath("$.data.pomFile").value("<project>literal pom</project>"));
    }

    @Test
    @DisplayName("the detail shows the newest timestamped POM of a directory without metadata")
    void showsTheNewestTimestampedPom() throws Exception {
      MavenArtifactControllerIT.this.seedSnapshotWithoutMetadata(
          "hand",
          "2.0-SNAPSHOT",
          Map.of(
              "hand-2.0-SNAPSHOT.pom", "<project>literal</project>",
              "hand-2.0-20260921.101010-1.pom", "<project>build 1</project>",
              "hand-2.0-20260921.101010-2.pom", "<project>build 2</project>"));

      MavenArtifactControllerIT.this
          .versionDetail("hand", "2.0-SNAPSHOT")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.pomFile").value("<project>build 2</project>"));
    }

    @Test
    @DisplayName("a snapshot whose POM is gone answers 404 like a release does")
    void answers404WithoutAnyPom() throws Exception {
      MavenArtifactControllerIT.this.seedSnapshotWithoutMetadata(
          "nopom", "2.0-SNAPSHOT", Map.of("nopom-2.0-SNAPSHOT.jar", "jar bytes"));

      MavenArtifactControllerIT.this
          .versionDetail("nopom", "2.0-SNAPSHOT")
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("a snapshot whose version-level maven-metadata.xml cannot be parsed (RPS-1421)")
  class SnapshotWithMalformedMetadata {

    @Test
    @DisplayName("the detail falls back to the stored POM instead of answering 400")
    void showsTheStoredPom() throws Exception {
      MavenArtifactControllerIT.this.seedSnapshotWithoutMetadata(
          "garbled",
          "2.0-SNAPSHOT",
          Map.of(
              "maven-metadata.xml", "<metadata><versioning>",
              "garbled-2.0-20260921.101010-1.pom", "<project>build 1</project>",
              "garbled-2.0-20260921.101010-2.pom", "<project>build 2</project>"));

      MavenArtifactControllerIT.this
          .versionDetail("garbled", "2.0-SNAPSHOT")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.msgId").value("artifactVersionFetched"))
          .andExpect(jsonPath("$.data.pomFile").value("<project>build 2</project>"));
    }

    @Test
    @DisplayName("without a stored POM it answers 404 like absent metadata, not 400")
    void answers404WithoutAnyPom() throws Exception {
      MavenArtifactControllerIT.this.seedSnapshotWithoutMetadata(
          "garbledjar",
          "2.0-SNAPSHOT",
          Map.of(
              "maven-metadata.xml", "not xml at all",
              "garbledjar-2.0-SNAPSHOT.jar", "jar bytes"));

      MavenArtifactControllerIT.this
          .versionDetail("garbledjar", "2.0-SNAPSHOT")
          .andExpect(status().isNotFound());
    }
  }
}
