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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1449: a measurement, not a regression test. It seeds a large Maven repo, turns "verify all
 * signatures" on and off and times the recompute ({@code SignedRecomputeService}), records the
 * latency of uploads to the same repo while the recompute runs, and fires many toggles for
 * different repos at once to see the {@code AbortPolicy} of {@code SignedRecomputeExecutorConfig}
 * reject some. It only runs with {@code -Drps1449.perf=true}; without it the class is skipped, so
 * {@code mvn verify} is unchanged. Run it with, for example:
 *
 * <pre>
 * mvn -o verify -pl repsy-backend -am -Dit.test=MavenSignedRecomputePerfIT \
 *   -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Drps1449.perf=true -Drps1449.versions=2000,5000 -Drps1449.out=/tmp/rps1449.out
 * </pre>
 *
 * <p>Properties (all optional): {@code rps1449.versions} (versions to seed, a comma separated list
 * of sizes, default 2000), {@code rps1449.artifacts} (spread over how many artifacts, default 100),
 * {@code rps1449.reps} (how many times the scenario is repeated on a freshly seeded repo, default
 * 3), {@code rps1449.window} (seconds of each baseline window, default 10), {@code rps1449.jar-kib}
 * (size of the jar, a third of it for the sources jar, default 64), {@code rps1449.toggles} (repos
 * toggled at once, default 60), {@code rps1449.exec-versions} (versions of the repo that keeps the
 * executor busy, default 2000), {@code rps1449.scenario} ({@code recompute}, {@code executor} or
 * {@code both}) and {@code rps1449.out} (a file that the result lines are appended to).
 *
 * <p>Each seeded version is the shape of a typical release: a POM, a jar and a sources jar, each
 * with a {@code .asc} that verifies against the registered key and a {@code .sha1}, all written
 * straight through the storage layer and the database (no upload), so nothing is verified yet when
 * the toggle is turned on: the recompute has the most to do (three verifications and three recorded
 * signatures per version).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "rps1449.perf", matches = "true")
@DisplayName("Maven signed recompute on a large repo (RPS-1449, measurement)")
class MavenSignedRecomputePerfIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();

  private static final List<Integer> VERSION_SIZES =
      Stream.of(System.getProperty("rps1449.versions", "2000").split(","))
          .map(String::trim)
          .map(Integer::valueOf)
          .toList();
  private static final int ARTIFACTS = Integer.getInteger("rps1449.artifacts", 100);
  private static final int REPS = Integer.getInteger("rps1449.reps", 3);
  private static final int WINDOW_SECONDS = Integer.getInteger("rps1449.window", 10);
  private static final int JAR_KIB = Integer.getInteger("rps1449.jar-kib", 64);
  private static final int TOGGLES = Integer.getInteger("rps1449.toggles", 60);
  private static final int EXEC_VERSIONS = Integer.getInteger("rps1449.exec-versions", 2000);
  private static final String SCENARIO = System.getProperty("rps1449.scenario", "both");
  private static final String OUT = System.getProperty("rps1449.out");

  private static final String GROUP = "com.acme";
  private static final Duration RECOMPUTE_TIMEOUT = Duration.ofMinutes(30);

  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private SignedRecomputeService signedRecomputeService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("osStorageStrategyMaven")
  private StorageStrategy storageStrategy;

  private final List<Repo> createdRepos = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** The number of versions the repo under measurement was seeded with. */
  private volatile int seeded;

  private final AtomicInteger newVersionCounter = new AtomicInteger();

  /** When each {@code recomputeRepo} call started and ended, by repo id, in nanoTime. */
  private final Map<UUID, long[]> runs = new ConcurrentHashMap<>();

  // The same bytes for every seeded version: the recompute reads and verifies whatever is stored.
  private final byte[] pomBytes = pom(GROUP, "seed", "1.0").getBytes(UTF_8);
  private final byte[] jarBytes = filler(JAR_KIB * 1024, 1);
  private final byte[] sourcesBytes = filler(Math.max(1, JAR_KIB * 1024 / 3), 2);
  private final byte[] pomSig = KEYS.detachedSignature(this.pomBytes).getBytes(UTF_8);
  private final byte[] jarSig = KEYS.detachedSignature(this.jarBytes).getBytes(UTF_8);
  private final byte[] sourcesSig = KEYS.detachedSignature(this.sourcesBytes).getBytes(UTF_8);
  private final byte[] sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709".getBytes(UTF_8);

  @AfterEach
  void cleanUp() throws IOException {
    for (final var repo : this.createdRepos) {
      this.jdbcTemplate.update("delete from repo where id = ?", repo.getId());
      this.deleteStorage(repo.getId());
    }
    this.createdRepos.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
    Mockito.reset(this.signedRecomputeService);
  }

  // ------------------------------------------------------------------------------------------
  // the recompute of one large repo, with and without concurrent uploads
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName("time the toggle-on recompute and the uploads that overlap it")
  void recomputeOfALargeRepo() throws Exception {
    if (!"recompute".equals(SCENARIO) && !"both".equals(SCENARIO)) {
      return;
    }

    final var admin = this.admin();
    this.installRunRecorder();

    for (final var versions : VERSION_SIZES) {
      for (var rep = 1; rep <= REPS; rep++) {
        this.measureOneRepo(admin, versions, rep);
      }
    }
  }

  private void measureOneRepo(final User admin, final int versions, final int rep)
      throws Exception {
    this.seeded = versions;
    final var repo = this.mavenRepo("perf");
    final var seedStart = System.nanoTime();
    this.seed(repo, versions, ARTIFACTS);
    final var seedMs = millis(System.nanoTime() - seedStart);
    this.registerPublicKey(repo, admin);
    this.putSettings(repo, admin, "{\"pgpKeyServerLookupEnabled\":false}");
    final var tag = "rep=" + rep + " versions=" + versions;
    this.out("RPS1449 seeded " + tag + " artifacts=" + ARTIFACTS + " seedMs=" + seedMs);

    // 1. verify-all is off: what uploads cost with nothing running (the "before" baseline)
    final var before = this.uploadWindow(repo, admin, WINDOW_SECONDS, null);
    this.report(tag + " window=before(off)", before.samples);

    // 2. toggle on alone: the recompute with nothing else going on
    final var on = this.toggleAndWait(repo, admin, true);
    this.out(
        "RPS1449 recompute "
            + tag
            + " alone toggle=on requestMs="
            + on.requestMs
            + " queueWaitMs="
            + on.queueWaitMs
            + " runMs="
            + on.runMs
            + " totalVersions="
            + this.versionCount(repo)
            + " signed="
            + this.signedCount(repo));

    // 3. toggle off alone, then on again, this time with uploads to the same repo
    final var off = this.toggleAndWait(repo, admin, false);
    this.out(
        "RPS1449 recompute "
            + tag
            + " alone toggle=off requestMs="
            + off.requestMs
            + " runMs="
            + off.runMs);
    // the signature rows are recorded now: forget them so that the second recompute has the same
    // work to do as the first one (verifying and recording every file)
    this.jdbcTemplate.update(
        "delete from maven_version_signature where artifact_version_id in (select v.id from"
            + " maven_artifact_version v join maven_artifact a on a.id = v.artifact_id where"
            + " a.repo_id = ?)",
        repo.getId());
    this.jdbcTemplate.update(
        "update maven_artifact_version set signed = false where artifact_id in (select id from"
            + " maven_artifact where repo_id = ?)",
        repo.getId());

    final var during =
        this.uploadWindow(repo, admin, 0, () -> this.toggleAndWait(repo, admin, true));
    this.out(
        "RPS1449 recompute "
            + tag
            + " withUploads toggle=on requestMs="
            + during.toggle.requestMs
            + " queueWaitMs="
            + during.toggle.queueWaitMs
            + " runMs="
            + during.toggle.runMs
            + " totalVersions="
            + this.versionCount(repo)
            + " lockWaitSamples="
            + during.lockWaitSamples
            + "/"
            + during.samplesTaken
            + " maxSessionsWaiting="
            + during.maxWaiting
            + " maxLockWaitMs="
            + during.maxLockWaitMs);
    this.report(tag + " window=during(recompute)", during.samples);

    // 4. verify-all is on and nothing runs: the "after" baseline
    final var after = this.uploadWindow(repo, admin, WINDOW_SECONDS, null);
    this.report(tag + " window=after(on)", after.samples);

    this.dropRepo(repo);
  }

  // ------------------------------------------------------------------------------------------
  // many toggles at once, for different repos
  // ------------------------------------------------------------------------------------------

  @Test
  @DisplayName("fire toggles for many repos at once while a big recompute holds the only thread")
  void manyTogglesAtOnce() throws Exception {
    if (!"executor".equals(SCENARIO) && !"both".equals(SCENARIO)) {
      return;
    }

    final var admin = this.admin();
    this.installRunRecorder();

    final var big = this.mavenRepo("perf-big");
    this.seed(big, EXEC_VERSIONS, ARTIFACTS);
    this.registerPublicKey(big, admin);
    this.putSettings(big, admin, "{\"pgpKeyServerLookupEnabled\":false}");

    final var small = new ArrayList<Repo>();
    for (var i = 0; i < TOGGLES; i++) {
      final var repo = this.mavenRepo("perf-small");
      this.seed(repo, 3, 1);
      this.registerPublicKey(repo, admin);
      this.putSettings(repo, admin, "{\"pgpKeyServerLookupEnabled\":false}");
      small.add(repo);
    }

    final var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    final var logger = (Logger) LoggerFactory.getLogger(SignedRecomputeService.class);
    logger.addAppender(appender);

    try {
      final var bigToggleAt = System.nanoTime();
      this.putSettings(big, admin, "{\"pgpVerifyAllSignaturesEnabled\":true}");
      this.awaitStarted(big.getId());

      final var pool = Executors.newFixedThreadPool(TOGGLES);
      final var ready = new CountDownLatch(TOGGLES);
      final var go = new CountDownLatch(1);
      final var requestMs = new ConcurrentLinkedQueue<Long>();
      final var futures = new ArrayList<Future<?>>();

      for (final var repo : small) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  final var start = System.nanoTime();
                  this.putSettings(repo, admin, "{\"pgpVerifyAllSignaturesEnabled\":true}");
                  requestMs.add(millis(System.nanoTime() - start));
                  return null;
                }));
      }

      ready.await();
      final var toggledAt = System.nanoTime();
      go.countDown();
      for (final var future : futures) {
        future.get(5, TimeUnit.MINUTES);
      }
      pool.shutdown();
      final var allToggledMs = millis(System.nanoTime() - toggledAt);

      final var rejected =
          appender.list.stream()
              .filter(e -> e.getFormattedMessage().contains("queue of signed recomputations"))
              .count();
      final var accepted = TOGGLES - rejected;

      // wait for everything that was accepted to run
      final var deadline = Instant.now().plus(RECOMPUTE_TIMEOUT);
      while (Instant.now().isBefore(deadline)) {
        final var done = small.stream().filter(r -> this.finished(r.getId())).count();
        if (this.finished(big.getId()) && done >= accepted) {
          break;
        }
        Thread.sleep(200);
      }

      final var recomputed = small.stream().filter(r -> this.finished(r.getId())).count();
      final var stale = small.stream().filter(r -> this.signedCount(r) == 0).count();
      final var lastEnd =
          small.stream()
              .map(r -> this.runs.get(r.getId()))
              .filter(r -> r != null && r[1] != 0)
              .mapToLong(r -> r[1])
              .max()
              .orElse(0);
      final var bigRun = this.runs.get(big.getId());

      final var sortedMs = new ArrayList<>(requestMs);
      Collections.sort(sortedMs);

      this.out(
          "RPS1449 executor toggles="
              + TOGGLES
              + " bigVersions="
              + EXEC_VERSIONS
              + " accepted="
              + accepted
              + " rejected="
              + rejected
              + " recomputed="
              + recomputed
              + " smallReposLeftWithNoSignedVersion="
              + stale
              + " allTogglesReturnedMs="
              + allToggledMs
              + " toggleRequestMs(p50/max)="
              + percentile(sortedMs, 50)
              + "/"
              + percentile(sortedMs, 100)
              + " bigRunMs="
              + millis(bigRun[1] - bigRun[0])
              + " lastSmallDoneAfterBigToggleMs="
              + millis(lastEnd - bigToggleAt));
    } finally {
      logger.detachAppender(appender);
    }
  }

  // ------------------------------------------------------------------------------------------
  // measuring
  // ------------------------------------------------------------------------------------------

  /** How long a request took, in microseconds. */
  private record Sample(String kind, long micros, int status) {}

  private record Toggle(long requestMs, long queueWaitMs, long runMs) {}

  private static final class Window {
    private final List<Sample> samples = new ArrayList<>();
    private volatile Toggle toggle;
    private volatile int lockWaitSamples;
    private volatile int samplesTaken;
    private volatile int maxWaiting;
    private volatile long maxLockWaitMs;
  }

  /** Wraps {@code recomputeRepo}, so that when a run started and ended is known. */
  private void installRunRecorder() {
    this.runs.clear();
    Mockito.doAnswer(
            invocation -> {
              final var id = (UUID) invocation.getArgument(0);
              final var times = new long[] {System.nanoTime(), 0};
              this.runs.put(id, times);
              try {
                return invocation.callRealMethod();
              } finally {
                times[1] = System.nanoTime();
              }
            })
        .when(this.signedRecomputeService)
        .recomputeRepo(any());
  }

  private boolean finished(final UUID repoId) {
    final var times = this.runs.get(repoId);

    return times != null && times[1] != 0;
  }

  private void awaitStarted(final UUID repoId) throws InterruptedException {
    final var deadline = Instant.now().plusSeconds(60);
    while (!this.runs.containsKey(repoId) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertThat(this.runs).containsKey(repoId);
  }

  /** PUTs the setting and waits until the recompute that it queued has ended. */
  private Toggle toggleAndWait(final Repo repo, final User admin, final boolean on)
      throws Exception {
    this.runs.remove(repo.getId());
    final var start = System.nanoTime();
    this.putSettings(repo, admin, "{\"pgpVerifyAllSignaturesEnabled\":" + on + "}");
    final var requestMs = millis(System.nanoTime() - start);

    final var deadline = Instant.now().plus(RECOMPUTE_TIMEOUT);
    while (!this.finished(repo.getId())) {
      assertThat(Instant.now()).as("recompute finished").isBefore(deadline);
      Thread.sleep(20);
    }
    final var times = this.runs.get(repo.getId());

    return new Toggle(requestMs, millis(times[0] - start), millis(times[1] - times[0]));
  }

  /**
   * Runs the uploaders against the repo: for {@code seconds}, or, with a {@code during} action,
   * until the action returns.
   */
  private Window uploadWindow(
      final Repo repo, final User admin, final int seconds, final Callable<Toggle> during)
      throws Exception {

    final var window = new Window();
    final var samples = new ConcurrentLinkedQueue<Sample>();
    final var stop = new AtomicBoolean();
    final var token = this.protocolBearerTokenFor(admin);
    final var pool = Executors.newFixedThreadPool(6);
    final var sampler = this.startLockSampler(window, stop);

    for (var i = 0; i < 2; i++) {
      pool.submit(() -> this.uploadNewVersions(repo, token, stop, samples));
      pool.submit(() -> this.repushSignatures(repo, token, stop, samples));
    }

    pool.submit(() -> this.probeLocks(repo, stop, samples));
    pool.submit(() -> this.probeFrontier(repo, stop, samples));

    try {
      if (during != null) {
        window.toggle = during.call();
      } else {
        Thread.sleep(seconds * 1000L);
      }
    } finally {
      stop.set(true);
      pool.shutdown();
      pool.awaitTermination(2, TimeUnit.MINUTES);
      sampler.join();
    }

    window.samples.addAll(samples);

    return window;
  }

  /** PUTs the four files of a new version, at a slow pace: a build that publishes now and then. */
  private void uploadNewVersions(
      final Repo repo,
      final String token,
      final AtomicBoolean stop,
      final ConcurrentLinkedQueue<Sample> samples) {

    while (!stop.get()) {
      final var n = this.newVersionCounter.incrementAndGet();
      final var artifact = "lib" + (n % ARTIFACTS);
      final var version = "9.0." + n;
      final var dir = GROUP.replace('.', '/') + "/" + artifact + "/" + version + "/";
      final var stem = artifact + "-" + version;
      final var pom = pom(GROUP, artifact, version).getBytes(UTF_8);
      final var jar = ("jar " + stem).getBytes(UTF_8);
      final var pomSignature = KEYS.detachedSignature(pom).getBytes(UTF_8);
      final var jarSignature = KEYS.detachedSignature(jar).getBytes(UTF_8);

      this.timedPut(samples, "new-file", repo, token, dir + stem + ".pom", pom);
      this.timedPut(samples, "new-file", repo, token, dir + stem + ".jar", jar);
      this.timedPut(samples, "new-asc", repo, token, dir + stem + ".pom.asc", pomSignature);
      this.timedPut(samples, "new-asc", repo, token, dir + stem + ".jar.asc", jarSignature);

      sleep(400);
    }
  }

  /** Sends the signature of a random existing version again: it takes that version's row lock. */
  private void repushSignatures(
      final Repo repo,
      final String token,
      final AtomicBoolean stop,
      final ConcurrentLinkedQueue<Sample> samples) {

    final var random = new Random();
    final var perArtifact = Math.max(1, this.seeded / ARTIFACTS);

    while (!stop.get()) {
      final var artifact = "lib" + random.nextInt(ARTIFACTS);
      final var version = "1.0." + random.nextInt(perArtifact);
      final var dir = GROUP.replace('.', '/') + "/" + artifact + "/" + version + "/";
      final var stem = artifact + "-" + version;

      this.timedPut(samples, "repush-asc", repo, token, dir + stem + ".pom.asc", this.pomSig);

      sleep(50);
    }
  }

  /**
   * The wait for the row lock of a random version, without the rest of an upload: an update that
   * changes nothing (the statement of {@code lockForSignedUpdate}) in a connection of its own, so
   * it waits exactly as long as the recompute or an upload holds that version's row.
   */
  private void probeLocks(
      final Repo repo, final AtomicBoolean stop, final ConcurrentLinkedQueue<Sample> samples) {

    final var ids =
        this.jdbcTemplate.queryForList(
            "select v.id from maven_artifact_version v join maven_artifact a on a.id ="
                + " v.artifact_id where a.repo_id = ? and v.version_name like '1.0.%'",
            UUID.class, repo.getId());
    final var random = new Random();

    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement =
            connection.prepareStatement(
                "update maven_artifact_version set signed = signed where id = ?")) {
      while (!stop.get()) {
        statement.setObject(1, ids.get(random.nextInt(ids.size())));
        final var start = System.nanoTime();
        statement.executeUpdate();
        samples.add(new Sample("lock-probe", micros(System.nanoTime() - start), 200));

        sleep(5);
      }
    } catch (final Exception e) {
      this.out("RPS1449 lock probe failed: " + e);
    }
  }

  /**
   * The same probe, aimed at the version the recompute is at: the recompute walks the versions by
   * id and sets {@code signed} of each one when it is done, so the first version of that order that
   * is not signed yet is the one it holds the lock of, or is about to. That is the worst case of an
   * upload to the repo, and it is what a random probe hits once in a thousand tries. Without a
   * recompute going on this is a probe of a row nobody holds (before the toggle every version is
   * unsigned) or it stops (afterwards every version is signed).
   */
  private void probeFrontier(
      final Repo repo, final AtomicBoolean stop, final ConcurrentLinkedQueue<Sample> samples) {

    final var ids =
        this.jdbcTemplate.queryForList(
            "select v.id from maven_artifact_version v join maven_artifact a on a.id ="
                + " v.artifact_id where a.repo_id = ? and v.version_name like '1.0.%' order by"
                + " v.id",
            UUID.class, repo.getId());
    var next = 0;

    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var isSigned =
            connection.prepareStatement("select signed from maven_artifact_version where id = ?");
        var statement =
            connection.prepareStatement(
                "update maven_artifact_version set signed = signed where id = ?")) {
      while (!stop.get() && next < ids.size()) {
        isSigned.setObject(1, ids.get(next));
        try (var rs = isSigned.executeQuery()) {
          if (rs.next() && rs.getBoolean(1)) {
            next++;
            continue;
          }
        }

        statement.setObject(1, ids.get(next));
        final var start = System.nanoTime();
        statement.executeUpdate();
        samples.add(new Sample("lock-probe-at-recompute", micros(System.nanoTime() - start), 200));

        sleep(2);
      }
    } catch (final Exception e) {
      this.out("RPS1449 frontier probe failed: " + e);
    }
  }

  private void timedPut(
      final ConcurrentLinkedQueue<Sample> samples,
      final String kind,
      final Repo repo,
      final String token,
      final String path,
      final byte[] body) {

    try {
      final var start = System.nanoTime();
      final var status =
          this.mockMvc
              .perform(
                  put("/{repo}/{path}", repo.getName(), path)
                      .header(AUTHORIZATION, token)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM)
                      .content(body)
                      .with(protocolPort()))
              .andReturn()
              .getResponse()
              .getStatus();

      samples.add(new Sample(kind, micros(System.nanoTime() - start), status));
    } catch (final Exception e) {
      samples.add(new Sample(kind, -1, -1));
    }
  }

  /** Every 20 ms: how many sessions of the database wait for a lock, and for how long. */
  private Thread startLockSampler(final Window window, final AtomicBoolean stop) {

    final var thread =
        new Thread(
            () -> {
              try (var connection =
                      DriverManager.getConnection(
                          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                  var statement =
                      connection.prepareStatement(
                          "select count(*), coalesce(max(extract(epoch from (now() -"
                              + " state_change)) * 1000), 0) from pg_stat_activity where"
                              + " wait_event_type = 'Lock' and datname = current_database()")) {
                while (!stop.get()) {
                  try (var rs = statement.executeQuery()) {
                    rs.next();
                    window.samplesTaken++;
                    if (rs.getInt(1) > 0) {
                      window.lockWaitSamples++;
                      window.maxWaiting = Math.max(window.maxWaiting, rs.getInt(1));
                      window.maxLockWaitMs = Math.max(window.maxLockWaitMs, rs.getLong(2));
                    }
                  }
                  sleep(20);
                }
              } catch (final Exception e) {
                this.out("RPS1449 lock sampler failed: " + e);
              }
            },
            "lock-sampler");
    thread.start();

    return thread;
  }

  private void report(final String tag, final List<Sample> samples) {
    final var byKind = new TreeMap<String, List<Long>>();
    var failures = 0;

    for (final var sample : samples) {
      if (sample.status != 200) {
        failures++;
      }
      byKind.computeIfAbsent(sample.kind, k -> new ArrayList<>()).add(sample.micros);
    }

    for (final var entry : byKind.entrySet()) {
      final var values = entry.getValue();
      Collections.sort(values);
      this.out(
          "RPS1449 uploads "
              + tag
              + " kind="
              + entry.getKey()
              + " n="
              + values.size()
              + " p50="
              + ms(percentile(values, 50))
              + " p95="
              + ms(percentile(values, 95))
              + " p99="
              + ms(percentile(values, 99))
              + " max="
              + ms(percentile(values, 100)));
    }
    this.out("RPS1449 uploads " + tag + " non200=" + failures);
  }

  private static long percentile(final List<Long> sorted, final int p) {
    if (sorted.isEmpty()) {
      return -1;
    }
    final var index = (int) Math.min(sorted.size() - 1, Math.ceil(p / 100.0 * sorted.size()) - 1);

    return sorted.get(Math.max(0, index));
  }

  private static long micros(final long nanos) {
    return TimeUnit.NANOSECONDS.toMicros(nanos);
  }

  private static String ms(final long micros) {
    return String.format(java.util.Locale.ROOT, "%.2f", micros / 1000.0);
  }

  private static long millis(final long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private synchronized void out(final String line) {
    System.out.println(line);
    if (OUT != null) {
      try {
        Files.writeString(
            Path.of(OUT),
            line + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (final IOException e) {
        System.out.println("RPS1449 cannot write " + OUT + ": " + e);
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // seeding
  // ------------------------------------------------------------------------------------------

  private Repo mavenRepo(final String prefix) {
    final var name = uniqueRepoName(prefix);
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    final var repo = this.repoRepository.findByName(name).orElseThrow();
    this.createdRepos.add(repo);
    this.mavenStorageService.createRepo(created.getId());

    repo.setAllowOverride(true);
    this.repoRepository.saveAndFlush(repo);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  /** Deletes a measured repo and its files at once, so that the disk is not filled by the reps. */
  private void dropRepo(final Repo repo) throws IOException {
    this.jdbcTemplate.update("delete from repo where id = ?", repo.getId());
    this.deleteStorage(repo.getId());
    this.createdRepos.remove(repo);
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("perf-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  /** Rows through JDBC and files through the storage layer: {@code versions} released versions. */
  private void seed(final Repo repo, final int versions, final int artifacts) {
    final var perArtifact = Math.max(1, versions / artifacts);
    final var artifactIds = new UUID[artifacts];

    for (var a = 0; a < artifacts; a++) {
      artifactIds[a] = UUID.randomUUID();
    }

    this.jdbcTemplate.batchUpdate(
        "insert into maven_artifact (id, repo_id, group_name, artifact_name, plugin, created_at,"
            + " last_updated_at) values (?, ?, ?, ?, false, now(), now())",
        IntStream.range(0, artifacts)
            .mapToObj(a -> new Object[] {artifactIds[a], repo.getId(), GROUP, "lib" + a})
            .toList(),
        artifacts,
        (ps, row) -> {
          ps.setObject(1, row[0]);
          ps.setObject(2, row[1]);
          ps.setString(3, (String) row[2]);
          ps.setString(4, (String) row[3]);
        });

    final var rows = new ArrayList<Object[]>();
    for (var a = 0; a < artifacts; a++) {
      for (var v = 0; v < perArtifact; v++) {
        rows.add(new Object[] {UUID.randomUUID(), artifactIds[a], "1.0." + v});
      }
    }

    this.jdbcTemplate.batchUpdate(
        "insert into maven_artifact_version (id, artifact_id, type, version_name, packaging,"
            + " has_documents, has_sources, has_modules, signed, created_at, last_updated_at)"
            + " values (?, ?, 'RELEASE', ?, 'jar', false, true, false, false, now(), now())",
        rows,
        1000,
        (ps, row) -> {
          ps.setObject(1, row[0]);
          ps.setObject(2, row[1]);
          ps.setString(3, (String) row[2]);
        });

    final var pool = Executors.newFixedThreadPool(8);
    final var futures = new ArrayList<Future<?>>();

    for (var a = 0; a < artifacts; a++) {
      final var artifact = "lib" + a;
      futures.add(
          pool.submit(
              () -> {
                for (var v = 0; v < perArtifact; v++) {
                  this.writeVersionFiles(repo, artifact, "1.0." + v);
                }
              }));
    }

    try {
      for (final var future : futures) {
        future.get();
      }
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    } finally {
      pool.shutdown();
    }
  }

  private void writeVersionFiles(final Repo repo, final String artifact, final String version) {
    final var dir = GROUP.replace('.', '/') + "/" + artifact + "/" + version + "/";
    final var stem = artifact + "-" + version;

    this.write(repo, dir + stem + ".pom", this.pomBytes);
    this.write(repo, dir + stem + ".pom.asc", this.pomSig);
    this.write(repo, dir + stem + ".pom.sha1", this.sha1);
    this.write(repo, dir + stem + ".jar", this.jarBytes);
    this.write(repo, dir + stem + ".jar.asc", this.jarSig);
    this.write(repo, dir + stem + ".jar.sha1", this.sha1);
    this.write(repo, dir + stem + "-sources.jar", this.sourcesBytes);
    this.write(repo, dir + stem + "-sources.jar.asc", this.sourcesSig);
    this.write(repo, dir + stem + "-sources.jar.sha1", this.sha1);
  }

  private void write(final Repo repo, final String path, final byte[] bytes) {
    this.storageStrategy.write(
        repo.getName(), StoragePath.of(repo.getId(), path), new ByteArrayInputStream(bytes));
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

  private void putSettings(final Repo repo, final User admin, final String json) throws Exception {
    final var status =
        this.mockMvc
            .perform(
                put("/api/repos/" + repo.getName() + "/settings")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).as("PUT settings %s", json).isEqualTo(200);
  }

  private long versionCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_artifact_version v join maven_artifact a on a.id ="
            + " v.artifact_id where a.repo_id = ?",
        Long.class,
        repo.getId());
  }

  private long signedCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_artifact_version v join maven_artifact a on a.id ="
            + " v.artifact_id where a.repo_id = ? and v.signed",
        Long.class,
        repo.getId());
  }

  private void deleteStorage(final UUID repoId) throws IOException {
    try (Stream<Path> found = Files.walk(STORAGE_ROOT, 3)) {
      final var dirs =
          found
              .filter(p -> p.getFileName() != null)
              .filter(p -> p.getFileName().toString().equals(repoId.toString()))
              .toList();

      for (final var dir : dirs) {
        try (Stream<Path> all = Files.walk(dir)) {
          all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
      }
    }
  }

  private static String pom(final String group, final String artifact, final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>%s</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(group, artifact, version);
  }

  private static byte[] filler(final int size, final int seed) {
    final var bytes = new byte[size];
    new Random(seed).nextBytes(bytes);

    return bytes;
  }
}
