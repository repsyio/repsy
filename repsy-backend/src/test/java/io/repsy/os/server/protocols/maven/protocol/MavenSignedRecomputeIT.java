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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.generated.model.RepoSettingsForm;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.services.SignedRecomputeService;
import io.repsy.os.server.protocols.maven.shared.artifact.services.VersionSignatureService;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
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
 * RPS-1334: a change of the key sources (a public key registered or deleted, the key-server lookup
 * toggled) starts the same recomputation, for a repo that verifies every signature and for no
 * other.
 *
 * <p>RPS-1316: turning {@code pgpVerifyAllSignaturesEnabled} on or off recomputes {@code signed} of
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
  @Autowired private KeyStoreService keyStoreService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;
  @Autowired private VersionSignatureService versionSignatureService;

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

  /** Turns the key-server lookup off, so a key that is not registered is refused without a call. */
  private void putKeyServerLookupOff(final Repo repo, final User admin) throws Exception {
    final var status =
        this.mockMvc
            .perform(
                put("/api/repos/" + repo.getName() + "/settings")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pgpKeyServerLookupEnabled\":false}"))
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
  @DisplayName("turning verify-all on verifies the .asc files that were stored while it was off")
  void aToggleOnVerifiesTheStoredSignatures() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.registerPublicKey(repo, admin);
    this.putKeyServerLookupOff(repo, admin);
    final var stranger = PgpTestKeys.generate();

    // Uploaded while only the POM's signature is verified: every other .asc is stored as sent.
    // 1.0: an honest publisher, everything signed by the registered key.
    this.uploadPom(repo, admin, "1.0");
    this.uploadPomSignature(repo, admin, "1.0");
    this.uploadJar(repo, admin, "1.0");
    this.uploadJarSignature(repo, admin, "1.0");
    // 2.0: the jar's signature is the one of other bytes.
    this.uploadPom(repo, admin, "2.0");
    this.uploadPomSignature(repo, admin, "2.0");
    this.uploadJar(repo, admin, "2.0");
    this.uploadOk(
        repo, admin, dir("2.0") + "lib-2.0.jar.asc", sign("not the jar of 2.0".getBytes(UTF_8)));
    // 3.0: the jar's signature is by a key the repo does not know.
    this.uploadPom(repo, admin, "3.0");
    this.uploadPomSignature(repo, admin, "3.0");
    this.uploadJar(repo, admin, "3.0");
    this.uploadOk(
        repo,
        admin,
        dir("3.0") + "lib-3.0.jar.asc",
        stranger.detachedSignature(jar("3.0")).getBytes(UTF_8));
    // 4.0: a jar with no signature at all.
    this.uploadPom(repo, admin, "4.0");
    this.uploadPomSignature(repo, admin, "4.0");
    this.uploadJar(repo, admin, "4.0");
    assertThat(this.signedByVersion(repo))
        .isEqualTo(Map.of("1.0", true, "2.0", true, "3.0", true, "4.0", true));

    this.putVerifyAll(repo, admin, true);

    // Only the honest publisher's version stays signed.
    this.awaitSigned(repo, Map.of("1.0", true, "2.0", false, "3.0", false, "4.0", false));
    assertThat(this.signedFileNames(repo, "1.0"))
        .containsExactlyInAnyOrder("lib-1.0.pom", "lib-1.0.jar");
    assertThat(this.signedFileNames(repo, "2.0")).containsExactly("lib-2.0.pom");
    assertThat(this.signedFileNames(repo, "3.0")).containsExactly("lib-3.0.pom");

    // The two rules still tell them apart on the way back.
    this.putVerifyAll(repo, admin, false);

    this.awaitSigned(repo, Map.of("1.0", true, "2.0", true, "3.0", true, "4.0", true));
  }

  @Test
  @DisplayName("a signature that was recorded for bytes that were replaced while off is dropped")
  void aToggleOnForgetsARecordOfReplacedBytes() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.registerPublicKey(repo, admin);
    this.putKeyServerLookupOff(repo, admin);
    this.putVerifyAll(repo, admin, true);
    this.uploadPom(repo, admin, "1.0");
    this.uploadJar(repo, admin, "1.0");
    this.uploadPomSignature(repo, admin, "1.0");
    this.uploadJarSignature(repo, admin, "1.0");
    assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0", true));
    this.putVerifyAll(repo, admin, false);
    // The jar is stored again with the flag off: nothing looks at the record of the old bytes.
    this.uploadOk(repo, admin, dir("1.0") + "lib-1.0.jar", "another jar".getBytes(UTF_8));
    this.uploadJarSignature(repo, admin, "1.0");
    assertThat(this.signedFileNames(repo, "1.0")).contains("lib-1.0.jar");

    this.putVerifyAll(repo, admin, true);

    this.awaitSigned(repo, Map.of("1.0", false));
    assertThat(this.signedFileNames(repo, "1.0")).containsExactly("lib-1.0.pom");
  }

  @Test
  @DisplayName(
      "a snapshot is recomputed by its newest build, and a legacy one by its stored POM signature")
  void aToggleRecomputesASnapshot() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.registerPublicKey(repo, admin);
    this.putKeyServerLookupOff(repo, admin);
    final var dir = "com/acme/lib/1.0-SNAPSHOT/";
    final var build = dir + "lib-1.0-20260921.101010-1";
    final var pom = pom("1.0-SNAPSHOT");
    final var jar = jar("1.0-SNAPSHOT");

    // Off: the POM's signature is verified, the jar's is stored as sent (and is a good one).
    this.uploadOk(repo, admin, build + ".pom", pom);
    this.uploadOk(repo, admin, build + ".pom.asc", sign(pom));
    this.uploadOk(repo, admin, build + ".jar", jar);
    this.uploadOk(repo, admin, build + ".jar.asc", sign(jar));
    assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0-SNAPSHOT", true));
    // A second build whose jar has no signature: the newest build is the one that counts.
    final var build2 = dir + "lib-1.0-20260921.101010-2";
    this.uploadOk(repo, admin, build2 + ".pom", pom);
    this.uploadOk(repo, admin, build2 + ".pom.asc", sign(pom));
    this.uploadOk(repo, admin, build2 + ".jar", jar);
    assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0-SNAPSHOT", true));

    this.putVerifyAll(repo, admin, true);

    this.awaitSigned(repo, Map.of("1.0-SNAPSHOT", false));

    // The jar of the newest build gets its signature while every signature is verified.
    this.uploadOk(repo, admin, build2 + ".jar.asc", sign(jar));
    assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0-SNAPSHOT", true));

    // A snapshot signed before RPS-1188 has no signature row (V0023 backfilled releases only).
    this.jdbcTemplate.update(
        """
        delete from maven_version_signature where artifact_version_id in
          (select v.id from maven_artifact_version v join maven_artifact a on a.id = v.artifact_id
            where a.repo_id = ?)""",
        repo.getId());
    this.jdbcTemplate.update(
        """
        update maven_artifact_version set signed = false where artifact_id in
          (select id from maven_artifact where repo_id = ?)""",
        repo.getId());

    this.putVerifyAll(repo, admin, false);

    this.awaitSigned(repo, Map.of("1.0-SNAPSHOT", true));
    assertThat(this.signedFileNames(repo, "1.0-SNAPSHOT"))
        .containsExactlyInAnyOrder(
            "lib-1.0-20260921.101010-1.pom", "lib-1.0-20260921.101010-2.pom");
  }

  @Test
  @DisplayName("the setting read under the version's lock is the committed one, not a cached one")
  void theSettingIsReadFromTheDatabaseUnderTheLock() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.uploadPom(repo, admin, "1.0");
    final var versionId =
        this.jdbcTemplate.queryForObject(
            """
            select v.id from maven_artifact_version v join maven_artifact a on a.id = v.artifact_id
              where a.repo_id = ?""",
            UUID.class,
            repo.getId());

    final var answers =
        new TransactionTemplate(this.transactionManager)
            .execute(
                status -> {
                  final var version =
                      this.artifactVersionRepository.findById(versionId).orElseThrow();
                  // The repo row is in the persistence context now, with the setting off.
                  final var cached =
                      version.getArtifact().getRepo().isPgpVerifyAllSignaturesEnabled();
                  this.jdbcTemplate.update(
                      "update repo set pgp_verify_all_signatures_enabled = true where id = ?",
                      repo.getId());

                  return List.of(
                      cached,
                      version.getArtifact().getRepo().isPgpVerifyAllSignaturesEnabled(),
                      this.versionSignatureService.lockAndIsVerifyAll(version));
                });

    // The entity still says off (it is what a request that began before the toggle sees), the
    // answer under the lock is the toggle's.
    assertThat(answers).containsExactly(false, false, true);
  }

  /** The names of the files whose signature is recorded as verified, of one version. */
  private List<String> signedFileNames(final Repo repo, final String version) {
    return this.jdbcTemplate.queryForList(
        """
        select s.file_name from maven_version_signature s
          join maven_artifact_version v on v.id = s.artifact_version_id
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? and v.version_name = ?""",
        String.class,
        repo.getId(),
        version);
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

  private UUID registerPublicKeyAndGetId(final Repo repo, final User admin) throws Exception {
    this.registerPublicKey(repo, admin);

    return this.jdbcTemplate.queryForObject(
        "select id from pgp_public_key where repo_id = ?", UUID.class, repo.getId());
  }

  private void deletePublicKey(final Repo repo, final User admin, final UUID keyId)
      throws Exception {
    final var status =
        this.mockMvc
            .perform(
                delete("/api/mvn/key-stores/" + repo.getName() + "/public-keys/" + keyId)
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin)))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("delete public key").isEqualTo(200);
  }

  private void putKeyServerLookup(final Repo repo, final User admin, final boolean enabled)
      throws Exception {
    final var status =
        this.mockMvc
            .perform(
                put("/api/repos/" + repo.getName() + "/settings")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pgpKeyServerLookupEnabled\":" + enabled + "}"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("PUT settings").isEqualTo(200);
  }

  /** The versions were uploaded while the setting was off, with the signatures of one key. */
  private void uploadSignedVersion(final Repo repo, final User admin, final String version)
      throws Exception {
    this.uploadPom(repo, admin, version);
    this.uploadPomSignature(repo, admin, version);
    this.uploadJar(repo, admin, version);
    this.uploadJarSignature(repo, admin, version);
  }

  @Test
  @DisplayName("registering the key of a stored signature signs the version, on a verify-all repo")
  void registeringAKeySignsTheVersionsItVerifies() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var keyId = this.registerPublicKeyAndGetId(repo, admin);
    this.putKeyServerLookupOff(repo, admin);
    // Stored while the setting is off: the jar's signature is stored as sent.
    this.uploadSignedVersion(repo, admin, "1.0");
    // The key goes, still with the setting off: nothing is recomputed for such a repo.
    this.deletePublicKey(repo, admin, keyId);
    this.putVerifyAll(repo, admin, true);
    // The toggle finds no key for the jar's signature, so the version does not count as signed.
    this.awaitSigned(repo, Map.of("1.0", false));
    clearInvocations(this.signedRecomputeService);

    this.registerPublicKey(repo, admin);

    this.awaitSigned(repo, Map.of("1.0", true));
    assertThat(this.signedFileNames(repo, "1.0"))
        .containsExactlyInAnyOrder("lib-1.0.pom", "lib-1.0.jar");
  }

  @Test
  @DisplayName("deleting a key recomputes the repo and does not unsign what it verified before")
  void deletingAKeyRecomputesButDoesNotUnsign() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var keyId = this.registerPublicKeyAndGetId(repo, admin);
    this.putKeyServerLookupOff(repo, admin);
    this.uploadSignedVersion(repo, admin, "1.0");
    this.putVerifyAll(repo, admin, true);
    this.awaitSigned(repo, Map.of("1.0", true));
    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .recomputeRepo(repo.getId());
    clearInvocations(this.signedRecomputeService);

    this.deletePublicKey(repo, admin, keyId);

    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .recomputeRepo(repo.getId());
    // The signatures were verified and recorded; the key being gone is not a reason to forget them.
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(this.signedByVersion(repo)).isEqualTo(Map.of("1.0", true)));
    assertThat(this.signedFileNames(repo, "1.0"))
        .containsExactlyInAnyOrder("lib-1.0.pom", "lib-1.0.jar");
  }

  @Test
  @DisplayName("toggling the key-server lookup recomputes a verify-all repo, and only that")
  void togglingTheLookupRecomputesAVerifyAllRepo() throws Exception {
    final var flagOn = this.mavenRepo();
    final var flagOff = this.mavenRepo();
    final var admin = this.admin();
    this.putVerifyAll(flagOn, admin, true);
    clearInvocations(this.signedRecomputeService);

    this.putKeyServerLookup(flagOn, admin, false);
    this.putKeyServerLookup(flagOff, admin, false);

    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .recomputeRepo(flagOn.getId());
    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .onKeySourcesChanged(
            new io.repsy.os.shared.repo.events.PgpKeySourcesChangedEvent(flagOff.getId()));
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> verify(this.signedRecomputeService, never()).recomputeRepo(flagOff.getId()));
  }

  @Test
  @DisplayName(
      "registering a key on a repo that does not verify every signature recomputes nothing")
  void registeringAKeyOnAFlagOffRepoRecomputesNothing() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();

    this.registerPublicKey(repo, admin);

    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .onKeySourcesChanged(any());
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> verify(this.signedRecomputeService, never()).recomputeRepo(any()));
  }

  @Test
  @DisplayName("a key registration that rolls back recomputes nothing")
  void aRolledBackKeyChangeRecomputesNothing() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    this.putVerifyAll(repo, admin, true);
    verify(this.signedRecomputeService, timeout(RECOMPUTE_TIMEOUT.toMillis()))
        .recomputeRepo(repo.getId());
    clearInvocations(this.signedRecomputeService);

    new TransactionTemplate(this.transactionManager)
        .executeWithoutResult(
            status -> {
              this.keyStoreService.createPublicKey(
                  this.repoTxService.getRepo(repo.getId()),
                  io.repsy.os.generated.model.PgpPublicKeyForm.builder()
                      .armoredKey(KEYS.armoredPublicKey())
                      .build());
              status.setRollbackOnly();
            });

    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> verify(this.signedRecomputeService, never()).onKeySourcesChanged(any()));
  }
}
