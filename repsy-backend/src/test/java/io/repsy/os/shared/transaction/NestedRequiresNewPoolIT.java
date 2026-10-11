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
package io.repsy.os.shared.transaction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.zaxxer.hikari.HikariDataSource;
import io.repsy.libs.testsupport.pgp.PgpTestKeys;
import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-2176: a request that holds a pooled connection must not need a second one for work that runs
 * in a transaction of its own ({@code REQUIRES_NEW}). With as many concurrent requests as the pool
 * has connections each of them holds one and waits for another, and the pool deadlocks until
 * Hikari's connection timeout (RPS-2173 was the signature parking path).
 *
 * <p>Every test borrows all connections but one, performs ONE request and expects it to succeed:
 * deterministic, no timing involved. A request that holds its connection while it asks for a second
 * waits for the connection timeout, lowered to two seconds here, and fails with 500.
 *
 * <p>Runs without a test transaction: the transaction of the request must be the outer one, and the
 * rows it writes are committed and deleted afterwards.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("nested REQUIRES_NEW work with one free pooled connection (RPS-2176)")
class NestedRequiresNewPoolIT extends AbstractIT {

  private static final String PASSWORD = VALID_PASSWORD;
  private static final String POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.acme</groupId>
        <artifactId>pool</artifactId>
        <version>1.0</version>
      </project>
      """;

  @Autowired private DataSource dataSource;
  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdRepoIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /** Runs {@code request} with every pooled connection but one borrowed. */
  private <T> T withOneFreeConnection(final Callable<T> request) throws Exception {
    final var hikari = this.dataSource.unwrap(HikariDataSource.class);
    final var mxBean = hikari.getHikariConfigMXBean();
    final var originalTimeout = mxBean.getConnectionTimeout();
    final var borrowed = new ArrayList<Connection>();

    try {
      mxBean.setConnectionTimeout(2_000);

      for (int i = 0; i < mxBean.getMaximumPoolSize() - 1; i++) {
        borrowed.add(hikari.getConnection());
      }

      return request.call();
    } finally {
      for (final var connection : borrowed) {
        connection.close();
      }

      mxBean.setConnectionTimeout(originalTimeout);
    }
  }

  // ---- password hash upgrade (UserTxService.upgradePasswordHash) ----

  private User weakUser() {
    final var hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);
    final var info = this.userTxService.create(uniqueUsername("pool"), UserRole.ADMIN, hash);
    this.createdUserIds.add(info.getId());

    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private void assertUpgraded(final User before) {
    final var after = this.userRepository.findById(before.getId()).orElseThrow();

    assertThat(after.getHash()).isNotEqualTo(before.getHash());
  }

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.withOneFreeConnection(
        () -> this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse());
  }

  @Test
  @DisplayName("panel login with an outdated hash")
  void panelLoginUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.withOneFreeConnection(
            () ->
                this.perform(
                        post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"username\":\"%s\",\"password\":\"%s\"}"
                                    .formatted(user.getUsername(), PASSWORD)))
                    .andReturn()
                    .getResponse());

    assertThat(response.getStatus()).isEqualTo(200);
    this.assertUpgraded(user);
  }

  @Test
  @DisplayName("HTTP Basic on the API port with an outdated hash")
  void apiBasicUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.withOneFreeConnection(
            () ->
                this.perform(
                        get("/api/repos/no-such-repo/settings")
                            .header(AUTHORIZATION, basicAuth(user.getUsername(), PASSWORD)))
                    .andReturn()
                    .getResponse());

    assertThat(response.getStatus()).isEqualTo(404);
    this.assertUpgraded(user);
  }

  @Test
  @DisplayName("Maven wire read with an outdated hash")
  void mavenBasicUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.protocol(
            get("/maven/com/example/lib/1.0/lib-1.0.pom")
                .header(AUTHORIZATION, basicAuth(user.getUsername(), PASSWORD)));

    assertThat(response.getStatus()).isEqualTo(404);
    this.assertUpgraded(user);
  }

  @Test
  @DisplayName("Docker token endpoint with an outdated hash")
  void dockerTokenUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.protocol(
            get("/v2/token")
                .param("scope", "repository:docker/image:pull")
                .header(AUTHORIZATION, basicAuth(user.getUsername(), PASSWORD)));

    assertThat(response.getStatus()).isEqualTo(200);
    this.assertUpgraded(user);
  }

  @Test
  @DisplayName("npm login with an outdated hash")
  void npmLoginUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.protocol(
            put("/npm/-/user/org.couchdb.user:{name}", user.getUsername())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"%s\",\"password\":\"%s\"}"
                        .formatted(user.getUsername(), PASSWORD)));

    assertThat(response.getStatus()).isEqualTo(201);
    this.assertUpgraded(user);
  }

  @Test
  @DisplayName("Cargo request with an outdated hash")
  void cargoUpgrade() throws Exception {
    final var user = this.weakUser();

    final var response =
        this.protocol(
            get("/cargo/me").header(AUTHORIZATION, basicAuth(user.getUsername(), PASSWORD)));

    assertThat(response.getStatus()).isEqualTo(200);
    this.assertUpgraded(user);
  }

  // ---- Maven deployment (ArtifactUpsertHelper, PendingSignatureService) ----

  private Repo mavenRepo(final boolean verifyAll) {
    final var name = uniqueRepoName("mvn-pool");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(verifyAll);
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var info =
        this.userTxService.create(
            uniqueUsername("pool-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());

    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private MockHttpServletResponse upload(
      final Repo repo, final User admin, final String path, final String body, final boolean tight)
      throws Exception {
    final Callable<MockHttpServletResponse> request =
        () ->
            this.mockMvc
                .perform(
                    put("/{repo}/{path}", repo.getName(), path)
                        .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(body.getBytes(UTF_8))
                        .with(protocolPort()))
                .andReturn()
                .getResponse();

    return tight ? this.withOneFreeConnection(request) : request.call();
  }

  private int versionCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id where a.repo_id = ?""",
        Integer.class,
        repo.getId());
  }

  @Test
  @DisplayName("POM upload that inserts the artifact and its version")
  void pomUploadInsertsRows() throws Exception {
    final var admin = this.admin();
    final var repo = this.mavenRepo(false);

    final var response = this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, true);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.versionCount(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("POM upload on a repo that verifies every signature")
  void pomUploadOnVerifyAllRepo() throws Exception {
    final var admin = this.admin();
    final var repo = this.mavenRepo(true);

    final var response = this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, true);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.versionCount(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("jar upload into a registered version of a repo that verifies every signature")
  void jarUploadOnVerifyAllRepo() throws Exception {
    final var admin = this.admin();
    final var repo = this.mavenRepo(true);

    assertThat(this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, false).getStatus())
        .isEqualTo(200);

    final var response = this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.jar", "jar", true);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("POM uploaded again into the registered version")
  void pomUploadAgain() throws Exception {
    final var admin = this.admin();
    final var repo = this.mavenRepo(false);

    assertThat(this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, false).getStatus())
        .isEqualTo(200);

    final var response = this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, true);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.versionCount(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("signature upload for a stored file of a registered version")
  void signatureUploadVerifiesAndRecords() throws Exception {
    final var admin = this.admin();
    final var repo = this.mavenRepo(true);
    final var signer = PgpTestKeys.generate();
    final var key =
        this.mockMvc
            .perform(
                post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        new ObjectMapper()
                            .writeValueAsString(Map.of("armoredKey", signer.armoredPublicKey()))))
            .andReturn()
            .getResponse();

    assertThat(key.getStatus()).isEqualTo(201);
    assertThat(this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.pom", POM, false).getStatus())
        .isEqualTo(200);
    assertThat(this.upload(repo, admin, "com/acme/pool/1.0/pool-1.0.jar", "jar", false).getStatus())
        .isEqualTo(200);

    final var response =
        this.upload(
            repo,
            admin,
            "com/acme/pool/1.0/pool-1.0.jar.asc",
            signer.detachedSignature("jar".getBytes(UTF_8)),
            true);

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
