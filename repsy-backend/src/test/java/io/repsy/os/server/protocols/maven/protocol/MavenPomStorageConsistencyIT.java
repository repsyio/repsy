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
package io.repsy.os.server.protocols.maven.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1058: a Maven POM is parsed before it is stored, so one that is rejected leaves neither a
 * file in the repo nor a usage report behind.
 *
 * <p>It used to be written first and parsed second: the parse then threw a 400, the database
 * transaction rolled back, and the file stayed in the repo, served by path, while the usage counter
 * (which is fed only after both steps) never counted it.
 *
 * <p>Runs without a test transaction, unlike {@link MavenPomUploadIT}: registering an accepted POM
 * inserts the artifact row in its own transaction, which cannot see a repo row that a test
 * transaction has not committed. It deletes the repos and users it commits.
 *
 * <p>{@link UsageUpdateService} is mocked, as in the other protocol ITs: the mock records whether
 * an upload reported any usage.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven POM is validated before it is stored (RPS-1058)")
class MavenPomStorageConsistencyIT extends AbstractIntegrationTest {

  private static final String POM_PATH = "com/example/lib/1.0/lib-1.0.pom";
  private static final String MALFORMED_POM =
      "<project><modelVersion>4.0.0</modelVersion><groupId>com.example<artifactId>";

  private static final String VALID_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
        <name>first upload</name>
      </project>
      """;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the artifacts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("pom-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("maven-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private MockHttpServletResponse uploadPom(final Repo repo, final String pom, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{path}", repo.getName(), POM_PATH)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_XML)
                .content(pom)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private static Path pomFile(final Repo repo) {
    return storageDirOf(repo).resolve(POM_PATH);
  }

  @Test
  @DisplayName("a malformed POM is not stored, not served and not counted")
  void malformedPomLeavesNothingBehind() throws Exception {
    final var repo = this.mavenRepo(false);
    final var token = this.adminToken();

    final var response = this.uploadPom(repo, MALFORMED_POM, token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(pomFile(repo)).doesNotExist();
    assertThat(pomFile(repo).getParent()).doesNotExist();
    assertThat(
            this.mockMvc
                .perform(
                    get("/{repo}/{path}", repo.getName(), POM_PATH)
                        .header(AUTHORIZATION, token)
                        .with(protocolPort()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a malformed POM does not block the valid one uploaded after it")
  void validPomFollowsAMalformedOne() throws Exception {
    final var repo = this.mavenRepo(false);
    final var token = this.adminToken();
    assertThat(this.uploadPom(repo, MALFORMED_POM, token).getStatus()).isEqualTo(400);

    final var response = this.uploadPom(repo, VALID_POM, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(Files.readString(pomFile(repo))).isEqualTo(VALID_POM);
  }

  @Test
  @DisplayName("a valid POM is stored byte for byte and its size is reported as usage")
  void validPomIsStoredAndCounted() throws Exception {
    final var repo = this.mavenRepo(false);

    final var response = this.uploadPom(repo, VALID_POM, this.adminToken());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(Files.readString(pomFile(repo))).isEqualTo(VALID_POM);
    verify(this.usageUpdateService)
        .updateUsage(
            new UsageChangedInfo(
                repo.getId(),
                BaseUsages.ofDisk(VALID_POM.getBytes(StandardCharsets.UTF_8).length)));
  }

  @Test
  @DisplayName("a malformed POM deployed over a stored one leaves the stored one in place")
  void malformedRedeployKeepsTheStoredPom() throws Exception {
    final var repo = this.mavenRepo(true);
    final var token = this.adminToken();
    assertThat(this.uploadPom(repo, VALID_POM, token).getStatus()).isEqualTo(200);

    final var response = this.uploadPom(repo, MALFORMED_POM, token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(Files.readString(pomFile(repo))).isEqualTo(VALID_POM);
  }
}
