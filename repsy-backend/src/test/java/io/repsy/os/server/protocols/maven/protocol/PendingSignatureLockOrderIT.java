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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.services.PendingSignatureService;
import io.repsy.os.server.protocols.maven.shared.artifact.services.VersionSignatureService;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1352: the lock order of {@link PendingSignatureService} and the version row lock of {@link
 * VersionSignatureService}, forced in the two interleavings that could deadlock if the order were
 * not one.
 *
 * <p>The order the code keeps: a parked signature's row comes first, the version's row lock second
 * ({@code claim} then {@code lockAndIsVerifyAll}; {@code reconcileFile} and {@code
 * reconcileDirectory} take the pending row in a transaction of their own and touch the version only
 * after it, if at all). A request that holds the version's row lock never asks for a pending row
 * that another request holds while that one asks for the version: {@code park} and {@code discard}
 * do not touch the version, and the checks of parked signatures run in transactions of their own
 * that the calling transaction waits for, so what the caller holds when it calls in decides whether
 * they can wait for each other.
 *
 * <p>Both interleavings are forced with hooks on the two services (the latch idiom of {@code
 * DockerImageDeleteRaceIT}) and must end with every request answered 200 and a complete deploy: a
 * deadlock shows as a timeout, not as a database error, since one of the parties is a transaction
 * waiting for another one of the same request, which the database cannot see.
 *
 * <p>Runs without a test transaction since the requests have to commit and meet in the database; it
 * deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Lock order of the pending signatures and the version row (RPS-1352)")
class PendingSignatureLockOrderIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();
  private static final long TIMEOUT_SECONDS = 60;

  private static final String DIR = "com/acme/lib/1.0/";
  private static final String POM = DIR + "lib-1.0.pom";
  private static final String JAR = DIR + "lib-1.0.jar";
  private static final String SOURCES = DIR + "lib-1.0-sources.jar";
  private static final List<String> SIX =
      List.of(POM, JAR, SOURCES, POM + ".asc", JAR + ".asc", SOURCES + ".asc");

  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private PendingSignatureService pendingSignatureService;
  @MockitoSpyBean private VersionSignatureService versionSignatureService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<ExecutorService> pools = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() throws InterruptedException {
    org.mockito.Mockito.reset(this.pendingSignatureService, this.versionSignatureService);
    this.stopPools();
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  /** Interrupts requests still waiting on a latch (a failed test) so they release their locks. */
  private void stopPools() throws InterruptedException {
    for (final var pool : this.pools) {
      pool.shutdownNow();
      pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    this.pools.clear();
  }

  private record Fixture(Repo repo, User admin, Map<String, byte[]> bodies) {}

  /** A verify-all repo that allows overrides, with the signer's key registered. */
  private Fixture fixture() throws Exception {
    final var name = uniqueRepoName("mvn-lockorder");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(true);
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);
    final var repo = this.repoRepository.findByName(name).orElseThrow();

    final var userInfo =
        this.userTxService.create(
            uniqueUsername("mvn-lockorder"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());
    final var admin = this.userRepository.findById(userInfo.getId()).orElseThrow();

    final var key =
        this.objectMapper.writeValueAsString(Map.of("armoredKey", KEYS.armoredPublicKey()));
    final var registered =
        this.mockMvc
            .perform(
                post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(key))
            .andReturn();
    assertThat(registered.getResponse().getStatus()).as("register public key").isEqualTo(200);

    return new Fixture(repo, admin, bodiesOf(repo));
  }

  private static Map<String, byte[]> bodiesOf(final Repo repo) {
    final var pom =
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>1.0</version>
        </project>
        """
            .getBytes(UTF_8);
    final var jar = ("jar of " + repo.getName()).getBytes(UTF_8);
    final var sources = ("sources of " + repo.getName()).getBytes(UTF_8);

    return Map.of(
        POM,
        pom,
        JAR,
        jar,
        SOURCES,
        sources,
        POM + ".asc",
        sign(pom),
        JAR + ".asc",
        sign(jar),
        SOURCES + ".asc",
        sign(sources));
  }

  private static byte[] sign(final byte[] file) {
    return KEYS.detachedSignature(file).getBytes(UTF_8);
  }

  private int status(final Fixture f, final String path) throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{path}", f.repo().getName(), path)
                .header(AUTHORIZATION, this.protocolBearerTokenFor(f.admin()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(f.bodies().get(path))
                .with(protocolPort()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private void uploadOk(final Fixture f, final String path) throws Exception {
    assertThat(this.status(f, path)).as("PUT %s", path).isEqualTo(200);
  }

  private Future<Integer> uploadOnAnotherThread(
      final ExecutorService pool, final Fixture f, final String path) {
    return pool.submit(() -> this.status(f, path));
  }

  private ExecutorService pool(final int threads) {
    final var pool = Executors.newFixedThreadPool(threads);
    this.pools.add(pool);

    return pool;
  }

  private int pendingCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_pending_signature where repo_id = ?",
        Integer.class,
        repo.getId());
  }

  private List<String> verifiedFiles(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select s.file_name from maven_version_signature s
          join maven_artifact_version v on v.id = s.artifact_version_id
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? order by s.file_name""",
        String.class,
        repo.getId());
  }

  private List<Boolean> signedOf(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select v.signed from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? and a.artifact_name = 'lib' and v.version_name = '1.0'""",
        Boolean.class,
        repo.getId());
  }

  /** The number of backends that wait for a row lock right now. */
  private int lockWaiters() {
    final var waiting =
        this.jdbcTemplate.queryForObject(
            "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);

    return waiting == null ? 0 : waiting;
  }

  /** Whether another transaction holds the row lock of the version right now. */
  private boolean versionRowIsLocked(final Repo repo) {
    try {
      this.jdbcTemplate.queryForList(
          """
          select v.id from maven_artifact_version v
            join maven_artifact a on a.id = v.artifact_id
            where a.repo_id = ? for update nowait""",
          repo.getId());

      return false;
    } catch (final CannotAcquireLockException e) {
      return true;
    }
  }

  /** Waits until at least {@code count} backends are blocked on a row lock. */
  private void awaitLockWaiters(final int count) throws InterruptedException {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

    while (System.nanoTime() < deadline) {
      if (this.lockWaiters() >= count) {
        return;
      }

      Thread.sleep(20);
    }

    throw new IllegalStateException("fewer than " + count + " requests ever waited for a lock");
  }

  private static void await(final CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("latch released").isTrue();
  }

  private void assertDeployComplete(final Fixture f) throws Exception {
    for (final var path : SIX) {
      assertThat(Files.readAllBytes(storageDirOf(f.repo()).resolve(path)))
          .as("stored %s", path)
          .isEqualTo(f.bodies().get(path));
    }

    assertThat(this.pendingCount(f.repo())).as("parked signatures left").isZero();
    assertThat(this.verifiedFiles(f.repo()))
        .containsExactlyInAnyOrder("lib-1.0-sources.jar", "lib-1.0.jar", "lib-1.0.pom");
    assertThat(this.signedOf(f.repo())).containsExactly(true);
  }

  /**
   * A signature parked for a file that is stored by now: what a signature request leaves for a
   * moment when the file it signs lands right behind it.
   */
  private void park(final Fixture f, final String path, final byte[] signature) {
    this.pendingSignatureService.park(f.repo().getId(), path, signature);
    assertThat(this.pendingCount(f.repo())).as("the parked row").isEqualTo(1);
  }

  /** The POM is uploaded again and stops right before it checks the parked signatures. */
  private CountDownLatch holdPomBeforeReconcile(final CountDownLatch mayReconcile) {
    final var before = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              before.countDown();
              await(mayReconcile);

              return invocation.callRealMethod();
            })
        .when(this.pendingSignatureService)
        .reconcileDirectory(any(), any());

    return before;
  }

  @Test
  @DisplayName(
      "a signature that holds its parked row and waits for the version, and the POM's"
          + " registration that waits for the parked row, both finish")
  void aSignatureHoldingItsRowAndThePomRegistrationBothFinish() throws Exception {
    final var f = this.fixture();

    for (final var path : SIX) {
      this.uploadOk(f, path);
    }

    this.park(f, JAR, f.bodies().get(JAR + ".asc"));

    // The POM is uploaded again: it stops right before it checks the parked signatures of its
    // directory, holding whatever its own transaction holds by then (its version, if the version
    // row was already written).
    final var pomMayReconcile = new CountDownLatch(1);
    final var pomBeforeReconcile = this.holdPomBeforeReconcile(pomMayReconcile);

    // The signature of the jar is uploaded again, its file being stored: it stores the signature,
    // claims the parked row and stops right before it takes the version's lock.
    final var signatureHoldsItsRow = new CountDownLatch(1);
    final var signatureMayLockTheVersion = new CountDownLatch(1);
    final var first = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (first.getAndSet(false)) {
                signatureHoldsItsRow.countDown();
                await(signatureMayLockTheVersion);
              }

              return invocation.callRealMethod();
            })
        .when(this.versionSignatureService)
        .lockAndIsVerifyAll(any());

    final var pool = this.pool(2);
    final var pom = this.uploadOnAnotherThread(pool, f, POM);
    await(pomBeforeReconcile);
    // This is what keeps the two from waiting for each other: the POM's transaction has not written
    // the version's row yet, or a signature that holds a parked row and asks for the version would
    // wait for a transaction that waits for the parked row.
    assertThat(this.versionRowIsLocked(f.repo()))
        .as("the version row while the POM's registration checks the parked signatures")
        .isFalse();
    final var signature = this.uploadOnAnotherThread(pool, f, JAR + ".asc");
    await(signatureHoldsItsRow);

    // The POM's check of the directory now waits for the row the signature holds ...
    pomMayReconcile.countDown();
    this.awaitLockWaiters(1);
    // ... and the signature asks for the version. Neither may end up waiting for the other.
    signatureMayLockTheVersion.countDown();

    assertThat(signature.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(pom.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200);
    this.assertDeployComplete(f);
  }

  @Test
  @DisplayName(
      "a file whose parked signature does not verify holds the row and then asks for the version,"
          + " while the POM's registration waits for the row: both finish")
  void aFileWithABadParkedSignatureAndThePomRegistrationBothFinish() throws Exception {
    final var f = this.fixture();

    this.uploadOk(f, POM);
    this.uploadOk(f, POM + ".asc");
    this.park(f, JAR, sign("some other jar".getBytes(UTF_8)));

    final var pomMayReconcile = new CountDownLatch(1);
    final var pomBeforeReconcile = this.holdPomBeforeReconcile(pomMayReconcile);

    // The jar arrives: its check holds the parked row, finds the signature bad, drops the row and
    // stops right before it forgets the recorded signature and locks the version.
    final var fileHoldsTheRow = new CountDownLatch(1);
    final var fileMayLockTheVersion = new CountDownLatch(1);
    final var first = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (first.getAndSet(false)) {
                fileHoldsTheRow.countDown();
                await(fileMayLockTheVersion);
              }

              return invocation.callRealMethod();
            })
        .when(this.versionSignatureService)
        .forget(any(), any());

    final var pool = this.pool(2);
    final var pom = this.uploadOnAnotherThread(pool, f, POM);
    await(pomBeforeReconcile);
    assertThat(this.versionRowIsLocked(f.repo())).as("the version row").isFalse();
    final var file = this.uploadOnAnotherThread(pool, f, JAR);
    await(fileHoldsTheRow);

    pomMayReconcile.countDown();
    this.awaitLockWaiters(1);
    fileMayLockTheVersion.countDown();

    assertThat(file.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the jar with a bad signature")
        .isEqualTo(422);
    assertThat(pom.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(this.pendingCount(f.repo())).as("parked signatures left").isZero();
    assertThat(storageDirOf(f.repo()).resolve(JAR)).as("the jar was taken back").doesNotExist();
    assertThat(this.verifiedFiles(f.repo())).containsExactly("lib-1.0.pom");
  }
}
