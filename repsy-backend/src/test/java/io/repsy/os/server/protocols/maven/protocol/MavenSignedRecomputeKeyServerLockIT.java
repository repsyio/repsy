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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.config.async.SignedRecomputeExecutorConfig;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.support.StubKeyServers;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * RPS-1469: the toggle-on recompute ({@code SignedRecomputeService}) verifies a signature that is
 * stored but was never checked, and a key that is not registered is looked up on a key server. That
 * lookup must not run under the version's row lock: a slow or unreachable key server would then
 * turn a lock normally held for a few milliseconds into one held for as long as the lookup takes,
 * blocking a concurrent upload to the same version for that long.
 *
 * <p>The key server is a stub {@link RestClient} (no real network) that answers "not found" after a
 * configurable delay, standing in for the "1-5 s latency" slow-server simulation the ticket asks
 * for. The version's row lock is probed directly, with the same statement {@code
 * ArtifactVersionRepository#lockForSignedUpdate} uses, from a JDBC connection of its own: that wait
 * is exactly what a concurrent upload to the same version would see, without an upload's own
 * network calls confusing the measurement.
 *
 * <p>Before the fix this test fails: the probe waits for close to the whole configured delay,
 * because the recompute's transaction takes the row lock before it asks the key server and only
 * releases it once the lookup (and the rest of the recompute) is done.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName(
    "Maven signed recompute does not hold the version lock across a key-server lookup (RPS-1469)")
class MavenSignedRecomputeKeyServerLockIT extends AbstractIntegrationTest {

  private static final PgpTestKeys OUTSIDER = PgpTestKeys.generate();
  private static final Duration RECOMPUTE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration LOOKUP_DELAY = Duration.ofSeconds(2);

  /** How long every answer of the stub key server is delayed by, reset before each test. */
  private static final AtomicLong DELAY_MILLIS = new AtomicLong();

  /** How many lookups the stub key server has received, reset before each test. */
  private static final AtomicInteger KEY_SERVER_REQUESTS = new AtomicInteger();

  @TestBean(name = "pgpVerifierRestClient", methodName = "slowKeyServer")
  private RestClient keyServer;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  @Autowired
  @Qualifier("osStorageStrategyMaven")
  private StorageStrategy storageStrategy;

  @Qualifier(SignedRecomputeExecutorConfig.BEAN_NAME)
  @Autowired
  private ThreadPoolTaskExecutor signedRecomputeExecutor;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** Answers every lookup "not found", after {@link #DELAY_MILLIS}: no real network is used. */
  @SuppressWarnings("unused")
  static RestClient slowKeyServer() {
    return StubKeyServers.answering(
        uri -> {
          KEY_SERVER_REQUESTS.incrementAndGet();

          try {
            Thread.sleep(DELAY_MILLIS.get());
          } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
          }

          return StubKeyServers.notFound();
        });
  }

  @BeforeEach
  void resetServer() {
    DELAY_MILLIS.set(0);
    KEY_SERVER_REQUESTS.set(0);
  }

  @AfterEach
  void cleanUp() {
    this.awaitRecomputeExecutorIdle();
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  @Test
  @DisplayName(
      "a concurrent probe of the version's row lock stays fast while the recompute is waiting on a"
          + " slow key server")
  void lockProbeStaysFastDuringASlowLookup() throws Exception {
    final var repo = this.mavenRepo();
    final var admin = this.admin();
    final var versionId = this.writeUnverifiedSignedVersion(repo, "1.0");

    DELAY_MILLIS.set(LOOKUP_DELAY.toMillis());

    this.putVerifyAll(repo, admin, true);
    this.awaitFirstKeyServerRequest();

    final var probeMs = this.probeRowLock(versionId);

    assertThat(probeMs)
        .as(
            "the row lock probe must not wait for the key-server lookup (delay=%s ms)",
            LOOKUP_DELAY.toMillis())
        .isLessThan(LOOKUP_DELAY.toMillis() / 2);

    // The recompute is still allowed to finish normally, key server unreachable or not.
    this.awaitRecomputeExecutorIdle();
    assertThat(KEY_SERVER_REQUESTS.get()).as("the stub key server was actually asked").isPositive();
  }

  /**
   * The wait for the version's row lock, without going through the app at all: the same statement
   * {@code ArtifactVersionRepository#lockForSignedUpdate} uses, from a connection of its own, so it
   * waits exactly as long as whatever holds that row's lock (an upload or the recompute) takes.
   */
  private long probeRowLock(final UUID versionId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement =
            connection.prepareStatement(
                "update maven_artifact_version set signed = signed where id = ?")) {
      statement.setObject(1, versionId);
      final var start = System.nanoTime();
      statement.executeUpdate();

      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
  }

  private void awaitFirstKeyServerRequest() {
    await()
        .atMost(RECOMPUTE_TIMEOUT)
        .pollInterval(Duration.ofMillis(10))
        .untilAsserted(() -> assertThat(KEY_SERVER_REQUESTS.get()).isPositive());
  }

  private void awaitRecomputeExecutorIdle() {
    await()
        .atMost(RECOMPUTE_TIMEOUT)
        .pollInterval(Duration.ofMillis(20))
        .untilAsserted(
            () -> {
              final var pool = this.signedRecomputeExecutor.getThreadPoolExecutor();
              assertThat(pool.getActiveCount() + pool.getQueue().size()).isZero();
            });
  }

  private Repo mavenRepo() {
    final var name = uniqueRepoName("mvn-rc-lock");
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
            uniqueUsername("mvn-rc-lock"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  /**
   * Writes a version straight through storage and the database (no upload, so nothing is verified
   * synchronously): a POM and a jar, each with a {@code .asc} signed by a key the repo does not
   * know, so the recompute must ask the key server for both.
   *
   * @return the id of the version row that was written
   */
  private UUID writeUnverifiedSignedVersion(final Repo repo, final String version) {
    final var dir = "com/acme/lib/" + version + "/";
    final var stem = "lib-" + version;
    final var pom =
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
        </project>
        """
            .formatted(version)
            .getBytes(UTF_8);
    final var jar = ("jar " + version).getBytes(UTF_8);

    this.write(repo, dir + stem + ".pom", pom);
    this.write(repo, dir + stem + ".pom.asc", OUTSIDER.detachedSignature(pom).getBytes(UTF_8));
    this.write(repo, dir + stem + ".jar", jar);
    this.write(repo, dir + stem + ".jar.asc", OUTSIDER.detachedSignature(jar).getBytes(UTF_8));

    final var artifactId = UUID.randomUUID();
    this.jdbcTemplate.update(
        "insert into maven_artifact (id, repo_id, group_name, artifact_name, plugin, created_at,"
            + " last_updated_at) values (?, ?, 'com.acme', 'lib', false, now(), now())",
        artifactId,
        repo.getId());

    final var versionId = UUID.randomUUID();
    this.jdbcTemplate.update(
        "insert into maven_artifact_version (id, artifact_id, type, version_name, packaging,"
            + " has_documents, has_sources, has_modules, signed, created_at, last_updated_at) values"
            + " (?, ?, 'RELEASE', ?, 'jar', false, false, false, false, now(), now())",
        versionId,
        artifactId,
        version);

    return versionId;
  }

  private void write(final Repo repo, final String path, final byte[] bytes) {
    this.storageStrategy.write(
        repo.getName(), StoragePath.of(repo.getId(), path), new ByteArrayInputStream(bytes));
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
}
