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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.server.protocols.maven.shared.artifact.services.SignedRecomputeService;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1316: turning {@code pgpVerifyAllSignaturesEnabled} on or off recomputes {@code signed} of
 * every version of the repo in the background, by the rule of the setting it has then, and a change
 * that is rolled back starts nothing.
 *
 * <p>The versions are uploaded under one setting and read after the other one has been PUT through
 * the API. {@code signed} of a version whose POM signature verified and whose jar has none is what
 * tells the two rules apart: a repo that verifies only the POM's signature calls it signed, one
 * that verifies every signature does not. Runs without a test transaction, like {@link
 * MavenDeferredSignatureIT}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven signed is recomputed when verify-all is toggled (RPS-1316)")
class MavenSignedRecomputeIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();
  private static final Duration RECOMPUTE_TIMEOUT = Duration.ofSeconds(20);

  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private SignedRecomputeService signedRecomputeService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
    org.mockito.Mockito.reset(this.signedRecomputeService);
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-recompute");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("mvn-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private void uploadOk(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    final var status =
        this.mockMvc
            .perform(
                put("/{repo}/{path}", repo.getName(), path)
                    .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(body)
                    .with(protocolPort()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("PUT %s", path).isEqualTo(200);
  }

  private void registerPublicKey(final Repo repo, final User admin) throws Exception {
    final var body =
        this.objectMapper.writeValueAsString(Map.of("armoredKey", KEYS.armoredPublicKey()));

    final var status =
        this.mockMvc
            .perform(
                post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("register public key").isEqualTo(200);
  }

  /** PUTs the setting through the API, as the panel does. */
  private void putVerifyAll(final Repo repo, final User admin, final boolean enabled)
      throws Exception {
    final var status =
        this.mockMvc
            .perform(
                put("/api/repos/" + repo.getName() + "/settings")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pgpVerifyAllSignaturesEnabled\":" + enabled + "}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("PUT settings").isEqualTo(200);
  }

  private static byte[] pom(final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(version)
        .getBytes(UTF_8);
  }

  private static byte[] jar(final String version) {
    return ("jar " + version).getBytes(UTF_8);
  }

  private static byte[] sign(final byte[] file) {
    return KEYS.detachedSignature(file).getBytes(UTF_8);
  }

  private static String dir(final String version) {
    return "com/acme/lib/" + version + "/";
  }

  private void uploadPom(final Repo repo, final User admin, final String version) throws Exception {
    this.uploadOk(repo, admin, dir(version) + "lib-" + version + ".pom", pom(version));
  }

  private void uploadPomSignature(final Repo repo, final User admin, final String version)
      throws Exception {
    this.uploadOk(repo, admin, dir(version) + "lib-" + version + ".pom.asc", sign(pom(version)));
  }

  private void uploadJar(final Repo repo, final User admin, final String version) throws Exception {
    this.uploadOk(repo, admin, dir(version) + "lib-" + version + ".jar", jar(version));
  }

  private void uploadJarSignature(final Repo repo, final User admin, final String version)
      throws Exception {
    this.uploadOk(repo, admin, dir(version) + "lib-" + version + ".jar.asc", sign(jar(version)));
  }

  /** {@code signed} of every version of the repo, by version name. */
  private Map<String, Boolean> signedByVersion(final Repo repo) {
    final var signed = new TreeMap<String, Boolean>();

    this.jdbcTemplate.query(
        """
        select v.version_name, v.signed from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
         where a.repo_id = ?""",
        rs -> {
          signed.put(rs.getString(1), rs.getBoolean(2));
        },
        repo.getId());

    return signed;
  }

  private void awaitSigned(final Repo repo, final Map<String, Boolean> expected) {
    await()
        .atMost(RECOMPUTE_TIMEOUT)
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(this.signedByVersion(repo)).isEqualTo(expected));
  }

  @Test
  @DisplayName("turning verify-all on and off again recomputes every version of the repo")
  void aToggleRecomputesEveryVersion() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.registerPublicKey(repo, admin);

    // Uploaded while only the POM's signature is verified.
    // 1.0: POM signed, jar not: signed now, not once every signature counts.
    this.uploadPom(repo, admin, "1.0");
    this.uploadPomSignature(repo, admin, "1.0");
    this.uploadJar(repo, admin, "1.0");
    // 2.0: a signed POM alone: signed by either rule.
    this.uploadPom(repo, admin, "2.0");
    this.uploadPomSignature(repo, admin, "2.0");
    // 3.0: nothing signed: unsigned by either rule.
    this.uploadPom(repo, admin, "3.0");
    this.uploadJar(repo, admin, "3.0");
    assertThat(this.signedByVersion(repo))
        .isEqualTo(Map.of("1.0", true, "2.0", true, "3.0", false));

    this.putVerifyAll(repo, admin, true);

    this.awaitSigned(repo, Map.of("1.0", false, "2.0", true, "3.0", false));

    // Uploaded while every signature is verified.
    // 4.0: POM signed, jar not: unsigned now, signed once only the POM's signature counts.
    this.uploadPom(repo, admin, "4.0");
    this.uploadPomSignature(repo, admin, "4.0");
    this.uploadJar(repo, admin, "4.0");
    // 5.0: everything signed: signed by either rule.
    this.uploadPom(repo, admin, "5.0");
    this.uploadJar(repo, admin, "5.0");
    this.uploadPomSignature(repo, admin, "5.0");
    this.uploadJarSignature(repo, admin, "5.0");
    assertThat(this.signedByVersion(repo))
        .isEqualTo(Map.of("1.0", false, "2.0", true, "3.0", false, "4.0", false, "5.0", true));

    this.putVerifyAll(repo, admin, false);

    this.awaitSigned(
        repo, Map.of("1.0", true, "2.0", true, "3.0", false, "4.0", true, "5.0", true));
  }

  @Test
  @DisplayName("a settings change that rolls back recomputes nothing")
  void aRolledBackToggleRecomputesNothing() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.registerPublicKey(repo, admin);
    this.uploadPom(repo, admin, "1.0");
    this.uploadPomSignature(repo, admin, "1.0");
    this.uploadJar(repo, admin, "1.0");

    new TransactionTemplate(this.transactionManager)
        .executeWithoutResult(
            status -> {
              this.repoTxService.updateSettings(
                  repo.getId(),
                  RepoSettingsForm.builder().pgpVerifyAllSignaturesEnabled(true).build());
              status.setRollbackOnly();
            });

    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              verify(this.signedRecomputeService, never()).onToggled(any());
              assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0", true));
            });
    assertThat(this.repoRepository.findByName(repo.getName()).orElseThrow())
        .extracting(Repo::isPgpVerifyAllSignaturesEnabled)
        .isEqualTo(false);
  }
}
