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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1199: a POM whose registration fails after it was stored (the database is down, the repo was
 * deleted in between) is taken back out of the repo when it was new, and the usage counter never
 * disagrees with the repo.
 *
 * <p>Before, the file stayed in the repo and the usage was never reported (it was set only after
 * the registration succeeded), and with {@code allowOverride} off the client that got the error
 * could not send the POM again either: the file made the retry an override, refused as {@code
 * artifactOverrideIsProhibited}. A redeploy cannot be taken back (storing over the file already
 * replaced the previous content), so it stays and is charged.
 *
 * <p>The failure is forced on {@code createOrUpdateArtifact}, the one call that runs after the
 * store. Runs without a test transaction, like {@link MavenPomStorageConsistencyIT}, and deletes
 * the repos and users it commits. {@link UsageUpdateService} is mocked to see what a request
 * reported.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName(
    "Maven registration that fails after the store keeps repo and usage in step (RPS-1199)")
class MavenPostStoreFailureIT extends AbstractIntegrationTest {

  private static final String POM_PATH = "com/example/lib/1.0/lib-1.0.pom";

  private static final String FIRST_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
        <name>first upload</name>
      </project>
      """;

  private static final String REDEPLOYED_POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>lib</artifactId>
        <version>1.0</version>
        <name>second upload, which is longer than the first</name>
        <description>and carries a description too</description>
      </project>
      """;

  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private ArtifactServiceImpl artifactService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    Mockito.reset(this.artifactService);
    // Every table that references a repo cascades on delete, so this takes the artifacts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("post-store");
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

  private static long sizeOf(final String pom) {
    return pom.getBytes(StandardCharsets.UTF_8).length;
  }

  /** The next {@code createOrUpdateArtifact} fails the way a lost database connection does. */
  private void registrationFailsOnce() {
    doThrow(new DataAccessResourceFailureException("connection to the database was lost"))
        .doCallRealMethod()
        .when(this.artifactService)
        .createOrUpdateArtifact(any(), any(), any());
  }

  private long reportedDiskUsage(final Repo repo) {
    final var reported = ArgumentCaptor.forClass(UsageChangedInfo.class);

    verify(this.usageUpdateService, atLeastOnce()).updateUsage(reported.capture());

    return reported.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getId()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  @Test
  @DisplayName("a new POM whose registration fails is taken back, not counted, and can be resent")
  void newPomWhoseRegistrationFailsIsTakenBack() throws Exception {
    final var repo = this.mavenRepo(false);
    final var token = this.adminToken();
    this.registrationFailsOnce();

    final var failed = this.uploadPom(repo, FIRST_POM, token);

    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    assertThat(pomFile(repo)).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);

    // allowOverride is off: a POM left behind would make this retry an override (403).
    final var resent = this.uploadPom(repo, FIRST_POM, token);

    assertThat(resent.getStatus()).isEqualTo(200);
    assertThat(Files.readString(pomFile(repo))).isEqualTo(FIRST_POM);
    assertThat(this.reportedDiskUsage(repo)).isEqualTo(sizeOf(FIRST_POM));
  }

  @Test
  @DisplayName("a redeployed POM whose registration fails stays, and the counter matches its size")
  void redeployedPomWhoseRegistrationFailsIsChargedNotRemoved() throws Exception {
    final var repo = this.mavenRepo(true);
    final var token = this.adminToken();
    assertThat(this.uploadPom(repo, FIRST_POM, token).getStatus()).isEqualTo(200);
    this.registrationFailsOnce();

    final var failed = this.uploadPom(repo, REDEPLOYED_POM, token);

    assertThat(failed.getStatus()).isGreaterThanOrEqualTo(500);
    // The previous content is gone once it is overwritten, so the file is kept, never removed.
    assertThat(Files.readString(pomFile(repo))).isEqualTo(REDEPLOYED_POM);
    assertThat(this.reportedDiskUsage(repo)).isEqualTo(sizeOf(REDEPLOYED_POM));
  }
}
