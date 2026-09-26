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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1443: {@code curl -X PUT --data-binary @file} declares {@code
 * application/x-www-form-urlencoded}, and Spring's form content filter used to consume the body
 * before the upload handler saw it, so the file was stored with no byte in it and answered 200. The
 * body of a {@code PUT} is the artifact whatever the client declares, and a request that has no
 * byte in it is refused with a 400 instead of being stored empty.
 *
 * <p>Runs without a test transaction like {@link MavenUploadSizeLimitIT}: it commits a repo and an
 * admin and deletes them again.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven PUT body (RPS-1443)")
class MavenPutBodyIT extends AbstractIntegrationTest {

  private static final String JAR_PATH = "com/acme/lib/1.0/lib-1.0.jar";
  private static final String FORM = "application/x-www-form-urlencoded";

  /** Looks like form data, and is not text at all. */
  private static final byte[] BINARY =
      new byte[] {'P', 'K', 3, 4, 0, 1, 2, (byte) 200, (byte) 255, '%', '4', '1', '=', '&'};

  private static final byte[] FORM_LOOKING = "a=b&c=d%20e".getBytes(StandardCharsets.UTF_8);

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-put");
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
      final Repo repo, final User admin, final String contentType, final byte[] body)
      throws Exception {
    final var request =
        put("/{repo}/{path}", repo.getName(), JAR_PATH)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .content(body)
            .with(protocolPort());

    return this.mockMvc.perform(contentType == null ? request : request.contentType(contentType));
  }

  private static Path stored(final Repo repo) {
    return storageDirOf(repo).resolve(JAR_PATH);
  }

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(
      strings = {
        "application/octet-stream",
        FORM,
        "application/x-www-form-urlencoded;charset=UTF-8"
      })
  @DisplayName("stores the whole body of a PUT whatever content type the client declares")
  void storesTheWholeBody(final String contentType) throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    for (final var body : List.of(BINARY, FORM_LOOKING)) {
      this.upload(repo, admin, contentType, body).andExpect(status().isOk());

      assertThat(stored(repo)).hasBinaryContent(body);
    }
  }

  @Test
  @DisplayName("stores the whole body of a PUT that declares no content type at all")
  void storesTheWholeBodyWithoutAContentType() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    this.upload(repo, admin, null, BINARY).andExpect(status().isOk());

    assertThat(stored(repo)).hasBinaryContent(BINARY);
  }

  @ParameterizedTest(name = "Content-Type {0}")
  @ValueSource(strings = {"application/octet-stream", FORM})
  @DisplayName("refuses a PUT with no byte in its body with a 400, storing nothing")
  void refusesAnEmptyBody(final String contentType) throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    expectError(
        this.upload(repo, admin, contentType, new byte[0]),
        HttpStatus.BAD_REQUEST,
        "mavenUploadBodyEmpty",
        "mavenUploadBodyEmpty",
        "The request body is empty: an artifact must have at least one byte.");

    assertThat(Files.exists(stored(repo))).isFalse();
    verifyNoInteractions(this.usageUpdateService);
  }
}
