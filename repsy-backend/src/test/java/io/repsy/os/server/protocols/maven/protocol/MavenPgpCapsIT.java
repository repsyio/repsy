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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.config.async.SignedRecomputeExecutorConfig;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.services.MavenPgpCaps;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1796: the abuse caps of a Maven repo's PGP data (20 registered public keys, 500 parked
 * signatures, 64 KiB per signature file). The cap-th request is accepted, the one after it is
 * refused with 400, and the cap holds when the requests arrive at the same time. Counterpart of the
 * Cloud's {@code MavenPgpCapsIT}; runs without a test transaction so the racers really commit.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven PGP abuse caps (RPS-1796)")
class MavenPgpCapsIT extends AbstractIntegrationTest {

  private static final PgpTestKeys SIGNER = PgpTestKeys.generate();
  private static final String DIR = "com/acme/caps/1.0/";

  @Autowired private ObjectMapper objectMapper;
  @Autowired private MavenPgpCaps caps;
  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  @Qualifier(SignedRecomputeExecutorConfig.BEAN_NAME)
  @Autowired
  private ThreadPoolTaskExecutor signedRecomputeExecutor;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Registering a key starts an async recompute that locks version rows; wait for it to be idle
    // before the fixtures are deleted (RPS-1655).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(20))
        .untilAsserted(
            () -> {
              final var pool = this.signedRecomputeExecutor.getThreadPoolExecutor();
              assertThat(pool.getActiveCount() + pool.getQueue().size()).isZero();
            });
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo verifyAllRepo() {
    final var name = uniqueRepoName("mvn-caps");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(true);
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("mvn-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  // ---- registered public keys ----

  private ResultActions registerKey(final Repo repo, final User admin, final PgpTestKeys keys)
      throws Exception {
    final var body =
        this.objectMapper.writeValueAsString(Map.of("armoredKey", keys.armoredPublicKey()));

    return this.mockMvc.perform(
        post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
            .with(apiPort())
            .header(AUTHORIZATION, this.bearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private int keyStatus(final Repo repo, final User admin, final PgpTestKeys keys)
      throws Exception {
    return this.registerKey(repo, admin, keys).andReturn().getResponse().getStatus();
  }

  private int keyCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from pgp_public_key where repo_id = ?", Integer.class, repo.getId());
  }

  @Test
  @DisplayName("the cap-th public key is registered, the next one is refused with 400")
  void publicKeyCapIsExactlyTheLimit() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxPublicKeysPerRepo();

    assertThat(cap).isEqualTo(20);

    for (int i = 0; i < cap; i++) {
      assertThat(this.keyStatus(repo, admin, PgpTestKeys.generate()))
          .as("key %s", i + 1)
          .isEqualTo(200);
    }

    final var refused = this.registerKey(repo, admin, PgpTestKeys.generate()).andReturn();

    assertThat(refused.getResponse().getStatus()).isEqualTo(400);
    assertThat(
            this.objectMapper
                .readTree(refused.getResponse().getContentAsString())
                .path("msgId")
                .asText())
        .isEqualTo("pgpPublicKeyLimitReached");
    assertThat(this.keyCount(repo)).isEqualTo(cap);
  }

  @Test
  @DisplayName("registering the same key again at the cap says it exists, and other repos are free")
  void duplicateAndOtherReposAreNotAffected() throws Exception {
    final var admin = this.admin();
    final var full = this.verifyAllRepo();
    final var other = this.verifyAllRepo();
    final var first = PgpTestKeys.generate();

    assertThat(this.keyStatus(full, admin, first)).isEqualTo(200);

    for (int i = 1; i < this.caps.getMaxPublicKeysPerRepo(); i++) {
      assertThat(this.keyStatus(full, admin, PgpTestKeys.generate())).isEqualTo(200);
    }

    assertThat(this.keyStatus(full, admin, first)).isEqualTo(409);
    assertThat(this.keyStatus(other, admin, first)).isEqualTo(200);
  }

  @Test
  @DisplayName("registrations sent at the same time never go over the cap")
  void concurrentKeyRegistrationsHoldTheCap() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxPublicKeysPerRepo();

    for (int i = 0; i < cap - 2; i++) {
      assertThat(this.keyStatus(repo, admin, PgpTestKeys.generate())).isEqualTo(200);
    }

    final List<PgpTestKeys> racers = new ArrayList<>();

    for (int i = 0; i < 6; i++) {
      racers.add(PgpTestKeys.generate());
    }

    final var statuses = this.concurrently(racers, keys -> this.keyStatus(repo, admin, keys));

    assertThat(statuses).filteredOn(s -> s == 200).hasSize(2);
    assertThat(statuses).filteredOn(s -> s == 400).hasSize(4);
    assertThat(this.keyCount(repo)).isEqualTo(cap);
  }

  // ---- parked signatures ----

  private ResultActions upload(
      final Repo repo, final User admin, final String path, final byte[] body) throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body)
            .with(protocolPort()));
  }

  private String uploadOutcome(
      final Repo repo, final User admin, final String path, final byte[] body) throws Exception {
    final var response = this.upload(repo, admin, path, body).andReturn().getResponse();

    if (response.getStatus() == 200) {
      return "200";
    }

    return response.getStatus()
        + " "
        + this.objectMapper.readTree(response.getContentAsString()).path("msgId").asText();
  }

  private static byte[] signatureOf(final String name) {
    return SIGNER.detachedSignature(name.getBytes(UTF_8)).getBytes(UTF_8);
  }

  /** The same signature, padded with an armor {@code Comment} header to exactly {@code size}. */
  private static byte[] padded(final byte[] signature, final int size) {
    final var armored = new String(signature, UTF_8);
    final var begin = "-----BEGIN PGP SIGNATURE-----\n";
    final var at = armored.indexOf(begin) + begin.length();
    final var prefix = "Comment: ";
    final var fill = size - signature.length - prefix.length() - 1;

    assertThat(fill).isPositive();

    return (armored.substring(0, at) + prefix + "x".repeat(fill) + "\n" + armored.substring(at))
        .getBytes(UTF_8);
  }

  private void seedPending(final Repo repo, final int count) {
    final var armored = new String(signatureOf("seed"), UTF_8);
    final var rows = new ArrayList<Object[]>();

    for (int i = 0; i < count; i++) {
      rows.add(
          new Object[] {
            UUID.randomUUID(),
            repo.getId(),
            DIR + "seed-" + i + ".jar",
            armored,
            "0123456789ABCDEF",
            Timestamp.from(Instant.now())
          });
    }

    this.jdbcTemplate.batchUpdate(
        """
        insert into maven_pending_signature
          (id, repo_id, signed_file_path, armored_signature, key_id, created_at)
          values (?, ?, ?, ?, ?, ?)""",
        rows);
  }

  private int pendingCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_pending_signature where repo_id = ?",
        Integer.class,
        repo.getId());
  }

  @Test
  @DisplayName("the cap-th parked signature is accepted, the next one is refused with 400")
  void pendingCapIsExactlyTheLimit() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxPendingSignaturesPerRepo();

    assertThat(cap).isEqualTo(500);
    this.seedPending(repo, cap - 1);

    assertThat(this.uploadOutcome(repo, admin, DIR + "caps-1.0-a.jar.asc", signatureOf("a")))
        .isEqualTo("200");
    assertThat(this.pendingCount(repo)).isEqualTo(cap);

    assertThat(this.uploadOutcome(repo, admin, DIR + "caps-1.0-b.jar.asc", signatureOf("b")))
        .isEqualTo("400 pendingSignatureLimitReached");
    assertThat(this.pendingCount(repo)).isEqualTo(cap);

    // Re-sending the signature of a file that is already parked replaces it, even at the cap.
    assertThat(this.uploadOutcome(repo, admin, DIR + "caps-1.0-a.jar.asc", signatureOf("a2")))
        .isEqualTo("200");
    assertThat(this.pendingCount(repo)).isEqualTo(cap);
  }

  @Test
  @DisplayName("signatures parked at the same time never go over the cap")
  void concurrentParkingHoldsTheCap() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxPendingSignaturesPerRepo();

    this.seedPending(repo, cap - 2);

    // Five racers: each holds a connection for its request and one for the REQUIRES_NEW park, and
    // the default Hikari pool has 10.
    final var names = IntStream.range(0, 5).mapToObj(i -> "c" + i).toList();
    final var outcomes =
        this.concurrently(
            names,
            name ->
                this.uploadOutcome(
                    repo, admin, DIR + "caps-1.0-" + name + ".jar.asc", signatureOf(name)));

    assertThat(outcomes).filteredOn("200"::equals).hasSize(2);
    assertThat(outcomes).filteredOn("400 pendingSignatureLimitReached"::equals).hasSize(3);
    assertThat(this.pendingCount(repo)).isEqualTo(cap);
  }

  // ---- size of one signature ----

  @Test
  @DisplayName("a signature of exactly 64 KiB is accepted, one byte more is refused with 400")
  void signatureSizeCapIsExactlyTheLimit() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var limit = (int) MavenPgpCaps.MAX_SIGNATURE_BYTES;

    assertThat(limit).isEqualTo(65_536);

    final var base = signatureOf("sized");

    assertThat(this.uploadOutcome(repo, admin, DIR + "caps-1.0-sized.jar.asc", padded(base, limit)))
        .isEqualTo("200");
    assertThat(
            this.uploadOutcome(
                repo, admin, DIR + "caps-1.0-sized.jar.asc", padded(base, limit + 1)))
        .isEqualTo("400 mavenSignatureTooLarge");
  }

  // ---- total bytes of pending signatures ----

  private long pendingBytes(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select coalesce(sum(length(\"ARMORED_SIGNATURE\")), 0) from \"MAVEN_PENDING_SIGNATURE\" where \"REPO_ID\" = ?",
        Long.class,
        repo.getId());
  }

  @Test
  @DisplayName("the cap-th byte of total pending is accepted, one byte more is refused with 400")
  void bytesCapIsExactlyTheLimit() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxBytesPerRepo();

    // Test with small cap: seed with just enough to approach it (RPS-1817)
    final var baseSize = 500L;
    final var count = Math.max(1, (cap - 1000) / baseSize);
    final var base = signatureOf("seed");

    for (int i = 0; i < count; i++) {
      final var padded =
          (baseSize < base.length)
              ? base
              : padded(base, (int) baseSize);

      this.jdbcTemplate.update(
          """
          insert into "MAVEN_PENDING_SIGNATURE"
            ("UUID", "REPO_ID", "SIGNED_FILE_PATH", "ARMORED_SIGNATURE", "KEY_ID", "CREATED_AT")
            values (?, ?, ?, ?, ?, ?)""",
          UUID.randomUUID(),
          repo.getId(),
          DIR + "seed-" + i + ".jar",
          new String(padded, UTF_8),
          "0123456789ABCDEF",
          Timestamp.from(Instant.now()));
    }

    final long remainingBytes = cap - this.pendingBytes(repo);
    assertThat(remainingBytes).isGreaterThan(100);

    // Add exactly remainingBytes worth of signature
    final var fillSig = padded(base, (int) remainingBytes);
    assertThat(this.uploadOutcome(repo, admin, DIR + "bytescap-1.0-fill.jar.asc", fillSig))
        .isEqualTo("200");
    assertThat(this.pendingBytes(repo)).isEqualTo(cap);

    // One more byte is refused
    final var overfillSig = padded(base, (int) remainingBytes + 1);
    assertThat(this.uploadOutcome(repo, admin, DIR + "bytescap-1.0-overfill.jar.asc", overfillSig))
        .isEqualTo("400 pendingSignatureBytesLimitReached");
    assertThat(this.pendingBytes(repo)).isEqualTo(cap);

    // Replacing a signature with a smaller one is allowed, even at the cap
    final var smaller =
        new String(base, UTF_8)
            .substring(0, Math.min(100, new String(base, UTF_8).length()))
            .getBytes(UTF_8);
    assertThat(this.uploadOutcome(repo, admin, DIR + "bytescap-1.0-fill.jar.asc", smaller))
        .isEqualTo("200");
    assertThat(this.pendingBytes(repo)).isLessThan(cap);
  }

  @Test
  @DisplayName("signatures sent at the same time never make total bytes go over the cap")
  void concurrentBytesCapHolds() throws Exception {
    final var admin = this.admin();
    final var repo = this.verifyAllRepo();
    final var cap = this.caps.getMaxBytesPerRepo();

    // Seed with a small amount, leaving room for concurrent requests (RPS-1817)
    final var baseSize = 500L;
    final var count = Math.max(1, (cap - 5000) / baseSize);
    final var base = signatureOf("seed");

    for (int i = 0; i < count; i++) {
      final var padded =
          (baseSize < base.length)
              ? base
              : padded(base, (int) baseSize);

      this.jdbcTemplate.update(
          """
          insert into "MAVEN_PENDING_SIGNATURE"
            ("UUID", "REPO_ID", "SIGNED_FILE_PATH", "ARMORED_SIGNATURE", "KEY_ID", "CREATED_AT")
            values (?, ?, ?, ?, ?, ?)""",
          UUID.randomUUID(),
          repo.getId(),
          DIR + "seed-" + i + ".jar",
          new String(padded, UTF_8),
          "0123456789ABCDEF",
          Timestamp.from(Instant.now()));
    }

    final var remainingBytes = cap - this.pendingBytes(repo);
    assertThat(remainingBytes).isGreaterThan(0);

    // Concurrent requests: some will fit, some will be refused
    final var names = IntStream.range(0, 12).mapToObj(i -> "byte-" + i).toList();
    final var sigSize = (int) (remainingBytes / 6); // Each is 1/6 of remaining
    final var sig = padded(base, (int) sigSize);

    final var outcomes =
        this.concurrently(
            names,
            name ->
                this.uploadOutcome(
                    repo, admin, DIR + "caps-1.0-" + name + ".jar.asc", sig));

    // Some requests fit, some are refused
    assertThat(outcomes).filteredOn("200"::equals).hasSizeGreaterThan(0);
    assertThat(outcomes)
        .filteredOn(s -> s.startsWith("400 pendingSignatureBytesLimitReached"))
        .hasSizeGreaterThan(0);
    assertThat(this.pendingBytes(repo)).isLessThanOrEqualTo(cap);
  }

  // ---- helpers ----

  private <T, R> List<R> concurrently(final List<T> inputs, final ThrowingFunction<T, R> action)
      throws Exception {
    final var pool = Executors.newFixedThreadPool(inputs.size());
    final var start = new CountDownLatch(1);

    try {
      final List<Future<R>> futures = new ArrayList<>();

      for (final var input : inputs) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();

                  return action.apply(input);
                }));
      }

      start.countDown();

      final List<R> results = new ArrayList<>();

      for (final var future : futures) {
        results.add(future.get(120, TimeUnit.SECONDS));
      }

      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface ThrowingFunction<T, R> {
    R apply(T input) throws Exception;
  }
}
