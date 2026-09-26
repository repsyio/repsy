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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1121: {@code maven-metadata.xml}, a POM and a POM signature ({@code .pom.asc}) are each
 * capped before they are read whole into memory (metadata, signature) or spooled to disk (POM), so
 * a client cannot exhaust the instance's heap, or fill its disk, with an oversized upload. An
 * oversized one is refused with a fixed 400 msgId that names the limit, before anything is stored,
 * and {@link UsageUpdateService} never sees it.
 *
 * <p>Runs without a test transaction, like {@link MavenPomGroupIdIT}: the repo is created through
 * {@link RepoTxService} in its own transaction, so the request thread has to see it committed. It
 * deletes the repos and users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven upload size limits (RPS-1121)")
class MavenUploadSizeLimitIT extends AbstractIntegrationTest {

  private static final String METADATA_PATH = "com/acme/lib/maven-metadata.xml";
  private static final String POM_PATH = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String POM_SIGNATURE_PATH = POM_PATH + ".asc";

  private static final long MEBIBYTE = 1024L * 1024L;
  private static final long KIBIBYTE = 1024L;
  private static final long MAX_METADATA_BYTES = 10 * MEBIBYTE;
  private static final long MAX_POM_BYTES = 10 * MEBIBYTE;
  private static final long MAX_POM_SIGNATURE_BYTES = 64 * KIBIBYTE;

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
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private static String filler(final long bytes) {
    return "a".repeat(Math.toIntExact(bytes));
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-limit");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("mvn-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private ResultActions upload(
      final Repo repo, final User admin, final String path, final String body) throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body)
            .with(protocolPort()));
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private int artifactCount(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from maven_artifact where repo_id = ?", Integer.class, repo.getId());
    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("refuses a maven-metadata.xml over the size limit, storing and registering nothing")
  void refusesAnOversizedMetadataFile() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var oversized = filler(MAX_METADATA_BYTES + 1);

    expectError(
        this.upload(repo, admin, METADATA_PATH, oversized),
        HttpStatus.BAD_REQUEST,
        "mavenMetadataTooLarge",
        "mavenMetadataTooLarge",
        "The maven-metadata.xml file is larger than 10 MiB.");

    assertThat(stored(repo, METADATA_PATH)).doesNotExist();
    assertThat(this.artifactCount(repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("refuses a POM over the size limit, storing and registering nothing")
  void refusesAnOversizedPom() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var oversized = filler(MAX_POM_BYTES + 1);

    expectError(
        this.upload(repo, admin, POM_PATH, oversized),
        HttpStatus.BAD_REQUEST,
        "pomFileTooLarge",
        "pomFileTooLarge",
        "The POM file is larger than 10 MiB.");

    assertThat(stored(repo, POM_PATH)).doesNotExist();
    assertThat(stored(repo, POM_PATH).getParent()).doesNotExist();
    assertThat(this.artifactCount(repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("refuses a POM signature over the size limit before verifying or storing it")
  void refusesAnOversizedPomSignature() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var oversized = filler(MAX_POM_SIGNATURE_BYTES + 1);

    expectError(
        this.upload(repo, admin, POM_SIGNATURE_PATH, oversized),
        HttpStatus.BAD_REQUEST,
        "mavenSignatureTooLarge",
        "mavenSignatureTooLarge",
        "The signature file is larger than 64 KiB.");

    assertThat(stored(repo, POM_SIGNATURE_PATH)).doesNotExist();
    assertThat(this.artifactCount(repo)).isZero();
    verifyNoInteractions(this.usageUpdateService);
  }
}
