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
package io.repsy.os.server.protocols.helm;

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_CONFIG_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_LAYER_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.OCI_MANIFEST_TYPE;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartRepository;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RPS-1342: Helm OCI pushes that lose a race on a row.
 *
 * <p>Helm does not share Docker's save path: its manifest push handler ({@code
 * AbstractHelmOciManifestPushProtocolMethodHandler}) has its own retry, which repeated a write only
 * for a unique-index race ({@code DataIntegrityViolationException}). Two pushes that override the
 * same tag both read its {@code helm_oci_manifest} row at the same {@code @Version}, and the loser
 * failed the version check at commit with an {@code ObjectOptimisticLockingFailureException}, which
 * RPS-1325 answers with a 503 straight away. The handler now repeats that write too (the facade is
 * the {@code @Transactional} proxy, so the retry has to run outside it), and answers 503 only when
 * the row stays contended.
 *
 * <p>The chart lock of RPS-1273 serialises the chart and its version row, not the manifest row, and
 * it does not cover a version row that is changed or deleted by a request that does not take it
 * (the panel's delete), so the same retry covers the chart step.
 *
 * <p>The same class pins the panel side of RPS-1325 end to end: {@code DELETE
 * /api/helm/charts/{repo}/{chart}/{version}} deletes a row that carries a {@code @Version}, and
 * when another request has changed the row since it was read the panel answers 409 {@code
 * concurrentModification} (no {@code Retry-After}: the client re-reads and repeats), where RPS-1325
 * pinned that mapping with a stub controller only. It is one class so that it shares one Spring
 * context (and one connection pool) with the OCI tests.
 *
 * <p>RPS-1365: a push takes the chart row first and then the version and manifest rows, so a delete
 * of that chart (the panel's, one version or all of them, or the classic protocol's) must take the
 * chart first too, or the two wait on each other and the database fails one of them. The tests
 * below hold both requests where the old order would deadlock and expect neither to fail.
 *
 * <p>RPS-1366: the manifest file is the last write of a push's transaction, so a failure of the
 * commit itself leaves a file no row describes. The tests below fail the commit with a
 * synchronization that throws before it, and expect the file to be removed (a new manifest) or put
 * back (a replaced one).
 *
 * <p>The races are forced, not hoped for: right after a row is read, another connection bumps its
 * {@code version_lock}, as a concurrent request would. Runs without a test transaction since the
 * pushes have to commit; it deletes the repos and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Helm writes that lose a race on a row (RPS-1342)")
@Import(HelmConcurrentModificationIT.ReadHook.class)
// This class needs a Spring context of its own (the hook below), and every cached context keeps a
// connection pool open on the one PostgreSQL that all ITs share: a small pool keeps the total
// under its max_connections. A request and the bump of its row hold two connections at once.
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=6")
class HelmConcurrentModificationIT extends AbstractIntegrationTest {

  private static final String OCTET_STREAM = "application/octet-stream";
  private static final long TIMEOUT_SECONDS = 30;

  /** Called with the method name after every call of the four repositories the pushes read. */
  private static final AtomicReference<Consumer<String>> AFTER_READ = new AtomicReference<>();

  private static final String MANIFEST_READ =
      "HelmOciManifestRepository.findByRepoIdAndNameAndReference";
  private static final String VERSION_READ = "HelmChartVersionRepository.findByChartAndVersion";
  private static final String BLOB_READ = "HelmOciBlobRepository.findByRepoIdAndDigest";
  private static final String CHART_LOCK = "HelmChartRepository.findWithLockByRepoIdAndName";
  private static final String VERSIONS_OF_CHART = "HelmChartVersionRepository.findAllByChart";
  private static final String VERSIONS_DELETED = "HelmChartVersionRepository.deleteAllByChart";

  /** Which request of a race the current thread runs, so that the hook can tell them apart. */
  private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

  private static final long HOLD_SECONDS = 2;

  private static final String BUMP_MANIFEST =
      "update helm_oci_manifest set version_lock = version_lock + 1 where repo_id = ?";
  private static final String BUMP_VERSION =
      """
      update helm_chart_version set version_lock = version_lock + 1
      where chart_id in (select id from helm_chart where repo_id = ?)
      """;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @MockitoSpyBean private HelmStorageService helmStorageService;

  /**
   * Wraps the manifest, chart, chart version and blob repositories in a proxy that calls through
   * and then runs {@link #AFTER_READ}: a Spring Data repository cannot be a
   * {@code @MockitoSpyBean}.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class ReadHook {

    @Bean
    static BeanPostProcessor helmReadHookProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
          final var type = hookedType(bean);
          if (type == null) {
            return bean;
          }

          return Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, args) -> {
                final Object result;
                try {
                  result = method.invoke(bean, args);
                } catch (final InvocationTargetException e) {
                  throw e.getCause();
                }

                final var hook = AFTER_READ.get();
                if (hook != null) {
                  hook.accept(type.getSimpleName() + "." + method.getName());
                }

                return result;
              });
        }

        private static Class<?> hookedType(final Object bean) {
          if (bean instanceof HelmOciManifestRepository) {
            return HelmOciManifestRepository.class;
          }
          if (bean instanceof HelmChartVersionRepository) {
            return HelmChartVersionRepository.class;
          }
          if (bean instanceof HelmChartRepository) {
            return HelmChartRepository.class;
          }
          if (bean instanceof HelmOciBlobRepository) {
            return HelmOciBlobRepository.class;
          }
          return null;
        }
      };
    }
  }

  /** How many rows the current test has bumped, the proof that its race was forced. */
  private AtomicInteger bumps = new AtomicInteger();

  /**
   * How many times the current test's push has run its chart, manifest and file write: each run
   * reads the chart's version row once, and only once.
   */
  private final AtomicInteger runs = new AtomicInteger();

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    AFTER_READ.set(null);
    ROLE.remove();
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo overridableHelmRepo() {
    final var name = uniqueRepoName("helm-oci-race");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var info =
        this.userTxService.create(uniqueUsername("helm-race"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());

    return this.userRepository.findById(info.getId()).orElseThrow();
  }

  private String protocolToken() {
    return this.protocolBearerTokenFor(this.admin());
  }

  /** Uploads {@code content} as a blob of the chart's name and returns the final response. */
  private MockHttpServletResponse pushBlob(
      final Repo repo, final String name, final byte[] content, final String token)
      throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{name}/blobs/uploads/", repo.getName(), name)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).as("blob upload start").isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), name, uploadId)
                .param("digest", digest("SHA-256", content))
                .contentType(OCTET_STREAM)
                .content(content)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Uploads {@code chartBytes} as the layer, then puts a manifest for it under {@code tag}. */
  private MockHttpServletResponse pushOci(
      final Repo repo,
      final String name,
      final String tag,
      final byte[] chartBytes,
      final String token)
      throws Exception {
    assertThat(this.pushBlob(repo, name, chartBytes, token).getStatus())
        .as("chart layer upload")
        .isEqualTo(201);

    final var config = "{}".getBytes(StandardCharsets.UTF_8);
    final var manifest =
        ("{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\","
                + "\"digest\":\"%s\",\"size\":%d}]}")
            .formatted(
                OCI_MANIFEST_TYPE,
                OCI_CONFIG_TYPE,
                digest("SHA-256", config),
                config.length,
                OCI_LAYER_TYPE,
                digest("SHA-256", chartBytes),
                chartBytes.length);

    return this.mockMvc
        .perform(
            put("/v2/{repo}/{name}/manifests/{reference}", repo.getName(), name, tag)
                .contentType(OCI_MANIFEST_TYPE)
                .content(manifest)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** The digest of the manifest the tag points at: it changes with the chart layer pushed. */
  private String manifestDigest(final Repo repo, final String name, final String tag) {
    return this.jdbcTemplate.queryForObject(
        "select digest from helm_oci_manifest where repo_id = ? and name = ? and reference = ?",
        String.class,
        repo.getId(),
        name,
        tag);
  }

  private int rows(final String table, final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where repo_id = ?", Integer.class, repo.getId());

    return count == null ? 0 : count;
  }

  /**
   * Bumps {@code version_lock} of the rows a push has just read, on another connection, on every
   * read of {@code method} from the {@code afterReads}-th one, {@code times} times in all.
   */
  private void bumpAfterRead(
      final String method,
      final String sql,
      final Repo repo,
      final int afterReads,
      final int times) {
    final var reads = new AtomicInteger();
    final var bumps = new AtomicInteger();
    this.bumps = bumps;

    this.runs.set(0);

    AFTER_READ.set(
        called -> {
          if (called.equals(VERSION_READ)) {
            this.runs.incrementAndGet();
          }
          if (called.equals(method)
              && reads.incrementAndGet() >= afterReads
              && bumps.get() < times) {
            bumps.incrementAndGet();
            this.jdbcTemplate.update(sql, repo.getId());
          }
        });
  }

  @Test
  @DisplayName("an override that loses the manifest's version race is repeated and gets its 201")
  void manifestVersionRaceIsRetried() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-manifest";
    final var token = this.protocolToken();
    assertThat(
            this.pushOci(repo, name, "1.0.0", chart(name, "1.0.0", "original", null), token)
                .getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var seeded = this.manifestDigest(repo, name, "1.0.0");
    final var override = chart(name, "1.0.0", "override", null);
    // The push reads the manifest twice before it writes it: to see whether it exists, and to
    // update it. The bump lands after the second read, so the update fails at commit once.
    this.bumpAfterRead(MANIFEST_READ, BUMP_MANIFEST, repo, 2, 1);

    final var response = this.pushOci(repo, name, "1.0.0", override, token);

    assertThat(response.getStatus())
        .as("answered %s", response.getContentAsString())
        .isEqualTo(201);
    assertThat(this.bumps).as("the race was forced").hasValue(1);
    assertThat(this.manifestDigest(repo, name, "1.0.0"))
        .as("the tag points at the manifest of the override")
        .isNotEqualTo(seeded);
  }

  @Test
  @DisplayName("an override that loses the manifest's version race on every run is answered 503")
  void manifestVersionRaceThatNeverEndsAnswersARetryableRegistryError() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-manifest-503";
    final var token = this.protocolToken();
    final var original = chart(name, "1.0.0", "original", null);
    assertThat(this.pushOci(repo, name, "1.0.0", original, token).getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var seeded = this.manifestDigest(repo, name, "1.0.0");
    final var override = chart(name, "1.0.0", "override", null);
    this.bumpAfterRead(MANIFEST_READ, BUMP_MANIFEST, repo, 2, Integer.MAX_VALUE);

    final var response = this.pushOci(repo, name, "1.0.0", override, token);

    assertThat(response.getStatus())
        .as("answered %s", response.getContentAsString())
        .isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString())
        .as("the registry error format, not the panel envelope")
        .contains("\"errors\"", "concurrentModification")
        .doesNotContain("\"msgId\"");
    assertThat(this.runs).as("three runs of the chart, manifest and file write").hasValue(3);
    assertThat(this.bumps).as("the race was forced on every run").hasValueGreaterThanOrEqualTo(3);
    assertThat(this.manifestDigest(repo, name, "1.0.0"))
        .as("the tag still points at the manifest it had")
        .isEqualTo(seeded);

    AFTER_READ.set(null);

    assertThat(this.pushOci(repo, name, "1.0.0", override, token).getStatus())
        .as("the same push repeated without contention")
        .isEqualTo(201);
    assertThat(this.manifestDigest(repo, name, "1.0.0")).isNotEqualTo(seeded);
  }

  @Test
  @DisplayName("an override that loses the chart version's version race is repeated too")
  void chartVersionRaceIsRetried() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-chart";
    final var token = this.protocolToken();
    assertThat(
            this.pushOci(repo, name, "1.0.0", chart(name, "1.0.0", "original", null), token)
                .getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var override = chart(name, "1.0.0", "override", null);
    this.bumpAfterRead(VERSION_READ, BUMP_VERSION, repo, 1, 1);

    final var response = this.pushOci(repo, name, "1.0.0", override, token);

    assertThat(response.getStatus())
        .as("answered %s", response.getContentAsString())
        .isEqualTo(201);
    assertThat(this.bumps).as("the race was forced").hasValue(1);
    assertThat(
            this.jdbcTemplate.queryForObject(
                """
                select v.digest from helm_chart_version v
                  join helm_chart c on c.id = v.chart_id
                where c.repo_id = ? and c.name = ?
                """,
                String.class,
                repo.getId(),
                name))
        .as("the version row describes the override")
        .isEqualTo(digest("SHA-256", override));
  }

  /** The digest the chart's version row carries, or null when there is no such row. */
  private @Nullable String chartDigest(final Repo repo, final String name) {
    return this.jdbcTemplate
        .queryForList(
            """
            select v.digest from helm_chart_version v
              join helm_chart c on c.id = v.chart_id
            where c.repo_id = ? and c.name = ?
            """,
            String.class,
            repo.getId(),
            name)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Test
  @DisplayName("a push answered 503 leaves the chart version row as it was (RPS-1354)")
  void manifestVersionRaceThatNeverEndsLeavesTheChartRowUntouched() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-pair-503";
    final var token = this.protocolToken();
    final var original = chart(name, "1.0.0", "original", null);
    assertThat(this.pushOci(repo, name, "1.0.0", original, token).getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var seeded = this.manifestDigest(repo, name, "1.0.0");
    final var override = chart(name, "1.0.0", "override", null);
    this.bumpAfterRead(MANIFEST_READ, BUMP_MANIFEST, repo, 2, Integer.MAX_VALUE);

    assertThat(this.pushOci(repo, name, "1.0.0", override, token).getStatus()).isEqualTo(503);

    assertThat(this.manifestDigest(repo, name, "1.0.0"))
        .as("the tag still points at the manifest it had")
        .isEqualTo(seeded);
    assertThat(this.chartDigest(repo, name))
        .as("and so does the chart version row: the pair is not half applied")
        .isEqualTo(digest("SHA-256", original));

    AFTER_READ.set(null);

    assertThat(this.pushOci(repo, name, "1.0.0", override, token).getStatus())
        .as("the same push repeated without contention")
        .isEqualTo(201);
    assertThat(this.chartDigest(repo, name)).isEqualTo(digest("SHA-256", override));
    assertThat(this.manifestDigest(repo, name, "1.0.0")).isNotEqualTo(seeded);
  }

  @Test
  @DisplayName("an override whose manifest file cannot be stored changes neither row (RPS-1354)")
  void overrideWhoseManifestFileFailsChangesNoRow() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-pair-file";
    final var token = this.protocolToken();
    final var original = chart(name, "1.0.0", "original", null);
    assertThat(this.pushOci(repo, name, "1.0.0", original, token).getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var seeded = this.manifestDigest(repo, name, "1.0.0");
    final var override = chart(name, "1.0.0", "override", null);
    doThrow(new IllegalStateException("disk full"))
        .when(this.helmStorageService)
        .saveManifest(any(UUID.class), anyString(), anyString(), any(byte[].class), anyString());

    assertThat(this.pushOci(repo, name, "1.0.0", override, token).getStatus())
        .as("the push fails")
        .isGreaterThanOrEqualTo(500);

    assertThat(this.manifestDigest(repo, name, "1.0.0"))
        .as("the tag still points at the manifest it had")
        .isEqualTo(seeded);
    assertThat(this.chartDigest(repo, name))
        .as("the chart version row describes the chart it had")
        .isEqualTo(digest("SHA-256", original));
  }

  @Test
  @DisplayName("a first push whose manifest file cannot be stored leaves no row, and can be redone")
  void firstPushWhoseManifestFileFailsLeavesNothing() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-pair-first";
    final var token = this.protocolToken();
    final var content = chart(name, "1.0.0", "first", null);
    doThrow(new IllegalStateException("disk full"))
        .when(this.helmStorageService)
        .saveManifest(any(UUID.class), anyString(), anyString(), any(byte[].class), anyString());

    assertThat(this.pushOci(repo, name, "1.0.0", content, token).getStatus())
        .as("the push fails")
        .isGreaterThanOrEqualTo(500);

    assertThat(this.chartDigest(repo, name)).as("no chart version row").isNull();
    assertThat(this.rows("helm_chart", repo)).as("no chart row").isZero();
    assertThat(this.rows("helm_oci_manifest", repo)).as("no manifest row").isZero();

    Mockito.reset(this.helmStorageService);

    assertThat(this.pushOci(repo, name, "1.0.0", content, token).getStatus())
        .as("the same push repeated once the storage works")
        .isEqualTo(201);
    assertThat(this.chartDigest(repo, name)).isEqualTo(digest("SHA-256", content));
    assertThat(this.rows("helm_oci_manifest", repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("two first uploads of one blob both get 201 and leave one blob row")
  void twoFirstUploadsOfOneBlob() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-blob";
    final var token = this.protocolToken();
    final var content = chart(name, "1.0.0", "blob", null);
    final var barrier = new CyclicBarrier(2);
    final var arrivals = new AtomicInteger();
    // Both uploads find no row for the digest before either inserts it.
    AFTER_READ.set(
        called -> {
          if (called.equals(BLOB_READ) && arrivals.getAndIncrement() < 2) {
            try {
              barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (final Exception e) {
              throw new IllegalStateException("the two uploads did not meet", e);
            }
          }
        });

    final var pool = Executors.newFixedThreadPool(2);
    final List<MockHttpServletResponse> responses;
    try {
      final var one = pool.submit(() -> this.pushBlob(repo, name, content, token));
      final var two = pool.submit(() -> this.pushBlob(repo, name, content, token));
      responses =
          List.of(
              one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
              two.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertThat(responses)
        .as("the upload that lost the insert race is not refused")
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactly(201, 201);
    assertThat(this.rows("helm_oci_blob", repo)).as("one row for the one blob").isEqualTo(1);
  }

  private void pushClassicChart(final Repo repo, final String name, final String token)
      throws Exception {
    final var request =
        multipart(UPLOAD_PATH, repo.getName())
            .part(new MockPart("chart", "chart.tgz", chart(name, "1.0.0", "1", null)))
            .header(AUTHORIZATION, token);

    assertThat(
            this.mockMvc
                .perform(request.with(protocolPort()))
                .andReturn()
                .getResponse()
                .getStatus())
        .as("the seeded chart")
        .isEqualTo(201);
  }

  private int versionRows(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from helm_chart_version v
              join helm_chart c on c.id = v.chart_id
            where c.repo_id = ? and c.name = ?
            """,
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("deleting a chart version that another request updated meanwhile answers 409")
  void panelDeleteOfAConcurrentlyUpdatedVersionAnswersConflict() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-panel";
    final var admin = this.admin();
    final var panelToken = this.bearerTokenFor(admin);
    this.pushClassicChart(repo, name, this.protocolBearerTokenFor(admin));
    final var chartFile = storageDirOf(repo).resolve("charts").resolve(name + "-1.0.0.tgz");
    // The delete reads the version row once, then removes it with the @Version it read.
    this.bumpAfterRead(VERSION_READ, BUMP_VERSION, repo, 1, 1);

    final var result =
        this.mockMvc.perform(
            delete("/api/helm/charts/{repo}/{chart}/{version}", repo.getName(), name, "1.0.0")
                .header(AUTHORIZATION, panelToken)
                .with(apiPort()));

    result
        .andExpect(status().isConflict())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(jsonPath("$.msgId").value("concurrentModification"))
        .andExpect(jsonPath("$.type").value("ERROR"))
        .andExpect(
            jsonPath("$.text")
                .value(
                    "The item was changed by another request at the same time. Please try again."))
        .andExpect(jsonPath("$.errorCode").isString());
    assertThat(this.bumps).as("the race was forced").hasValue(1);
    assertThat(this.versionRows(repo, name)).as("the losing delete removed nothing").isEqualTo(1);
    assertThat(chartFile).as("and left the chart file alone").exists();

    AFTER_READ.set(null);

    this.mockMvc
        .perform(
            delete("/api/helm/charts/{repo}/{chart}/{version}", repo.getName(), name, "1.0.0")
                .header(AUTHORIZATION, panelToken)
                .with(apiPort()))
        .andExpect(status().isOk());
    assertThat(this.versionRows(repo, name))
        .as("the same request repeated without contention")
        .isZero();
    assertThat(chartFile).doesNotExist();
  }

  /** What the push and the delete of a race answered, and how often the push ran its unit. */
  private record RaceOutcome(
      MockHttpServletResponse push, MockHttpServletResponse delete, int pushRuns) {}

  private static boolean await(final CountDownLatch latch) {
    try {
      return latch.await(HOLD_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Runs an override push of the seeded chart and a delete of it against each other, holding each
   * where the other would need what it holds. The push stops right after it took the chart lock
   * until the delete holds the rows it has deleted so far (the wait of {@code deleteAfter}), and
   * the delete stops there until the push holds the chart. The delete starts once the push holds
   * the chart. When the delete takes the chart first it cannot get past its first statement while
   * the push holds the chart, so the push's wait ends by its timeout and the push commits, then the
   * delete runs; when it does not, the two are in a deadlock the database resolves by failing one.
   */
  private RaceOutcome pushAgainstDelete(
      final Repo repo,
      final String name,
      final String token,
      final String deleteAfter,
      final Callable<MockHttpServletResponse> delete)
      throws Exception {
    final var override = chart(name, "1.0.0", "override", null);
    final var pushHoldsChart = new CountDownLatch(1);
    final var deleteHoldsRows = new CountDownLatch(1);
    final var pushRuns = new AtomicInteger();

    AFTER_READ.set(
        called -> {
          if ("push".equals(ROLE.get()) && called.equals(CHART_LOCK)) {
            pushRuns.incrementAndGet();
            pushHoldsChart.countDown();
            await(deleteHoldsRows);
          } else if ("delete".equals(ROLE.get()) && called.equals(deleteAfter)) {
            deleteHoldsRows.countDown();
            await(pushHoldsChart);
          }
        });

    final var pool = Executors.newFixedThreadPool(2);
    try {
      final var push =
          pool.submit(
              () -> {
                ROLE.set("push");
                return this.pushOci(repo, name, "1.0.0", override, token);
              });
      assertThat(pushHoldsChart.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("the push reached the chart lock")
          .isTrue();
      final var deleted =
          pool.submit(
              () -> {
                ROLE.set("delete");
                return delete.call();
              });

      return new RaceOutcome(
          push.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          deleted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          pushRuns.get());
    } finally {
      pool.shutdownNow();
    }
  }

  private Repo repoWithSeededOciChart(final String name, final String token) throws Exception {
    final var repo = this.overridableHelmRepo();
    assertThat(
            this.pushOci(repo, name, "1.0.0", chart(name, "1.0.0", "original", null), token)
                .getStatus())
        .as("the seed")
        .isEqualTo(201);

    return repo;
  }

  private void assertNeitherRequestFailed(final RaceOutcome outcome) throws Exception {
    assertThat(outcome.push().getStatus())
        .as("the push answered %s", outcome.push().getContentAsString())
        .isEqualTo(201);
    assertThat(outcome.delete().getStatus())
        .as("the delete answered %s", outcome.delete().getContentAsString())
        .isBetween(200, 299);
    assertThat(outcome.pushRuns())
        .as("the push was not repeated after losing a deadlock")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a panel delete of a version and a push of the chart take their locks in one order")
  void panelVersionDeleteAndPushDoNotDeadlock() throws Exception {
    final var name = "lock-order-version";
    final var admin = this.admin();
    final var repo = this.repoWithSeededOciChart(name, this.protocolBearerTokenFor(admin));
    final var panelToken = this.bearerTokenFor(admin);

    final var outcome =
        this.pushAgainstDelete(
            repo,
            name,
            this.protocolBearerTokenFor(admin),
            VERSIONS_OF_CHART,
            () ->
                this.mockMvc
                    .perform(
                        delete(
                                "/api/helm/charts/{repo}/{chart}/{version}",
                                repo.getName(),
                                name,
                                "1.0.0")
                            .header(AUTHORIZATION, panelToken)
                            .with(apiPort()))
                    .andReturn()
                    .getResponse());

    this.assertNeitherRequestFailed(outcome);
    assertThat(this.versionRows(repo, name)).as("the delete came last and won").isZero();
    assertThat(this.rows("helm_chart", repo)).isZero();
    assertThat(this.rows("helm_oci_manifest", repo)).isZero();
  }

  @Test
  @DisplayName(
      "a panel delete of a whole chart and a push of the chart take their locks in one order")
  void panelChartDeleteAndPushDoNotDeadlock() throws Exception {
    final var name = "lock-order-chart";
    final var admin = this.admin();
    final var repo = this.repoWithSeededOciChart(name, this.protocolBearerTokenFor(admin));
    final var panelToken = this.bearerTokenFor(admin);

    final var outcome =
        this.pushAgainstDelete(
            repo,
            name,
            this.protocolBearerTokenFor(admin),
            VERSIONS_DELETED,
            () ->
                this.mockMvc
                    .perform(
                        delete("/api/helm/charts/{repo}/{chart}", repo.getName(), name)
                            .header(AUTHORIZATION, panelToken)
                            .with(apiPort()))
                    .andReturn()
                    .getResponse());

    this.assertNeitherRequestFailed(outcome);
    assertThat(this.versionRows(repo, name)).as("the delete came last and won").isZero();
    assertThat(this.rows("helm_chart", repo)).isZero();
    assertThat(this.rows("helm_oci_manifest", repo)).isZero();
  }

  @Test
  @DisplayName("a classic protocol delete of a version and a push of the chart take one lock order")
  void protocolDeleteAndPushDoNotDeadlock() throws Exception {
    final var name = "lock-order-classic";
    final var admin = this.admin();
    final var repo = this.repoWithSeededOciChart(name, this.protocolBearerTokenFor(admin));
    final var token = this.protocolBearerTokenFor(admin);

    final var outcome =
        this.pushAgainstDelete(
            repo,
            name,
            token,
            VERSIONS_OF_CHART,
            () ->
                this.mockMvc
                    .perform(
                        delete("/{repo}/api/charts/{name}/{version}", repo.getName(), name, "1.0.0")
                            .header(AUTHORIZATION, token)
                            .with(protocolPort()))
                    .andReturn()
                    .getResponse());

    this.assertNeitherRequestFailed(outcome);
    assertThat(this.versionRows(repo, name)).as("the delete came last and won").isZero();
    assertThat(this.rows("helm_chart", repo)).isZero();
    assertThat(this.rows("helm_oci_manifest", repo)).isZero();
  }

  /** The manifest file as stored, or null when there is none. */
  private byte @Nullable [] manifestFile(final Repo repo, final String name, final String tag)
      throws Exception {
    final var resource =
        this.helmStorageService.getManifest(repo.getId(), name, tag, repo.getName());
    if (resource.isEmpty() || !resource.get().exists()) {
      return null;
    }

    return resource.get().getContentAsByteArray();
  }

  /**
   * Makes the commit of the next manifest write fail: once the file is written, the transaction
   * gets a synchronization that throws before the commit, which rolls the rows back.
   */
  private void failTheCommitAfterTheManifestFile() {
    doAnswer(
            invocation -> {
              final var written = invocation.callRealMethod();
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(final boolean readOnly) {
                      throw new IllegalStateException("the commit failed");
                    }
                  });
              return written;
            })
        .when(this.helmStorageService)
        .saveManifest(any(UUID.class), anyString(), anyString(), any(byte[].class), anyString());
  }

  @Test
  @DisplayName("a first push whose commit fails leaves no manifest file (RPS-1366)")
  void firstPushWhoseCommitFailsLeavesNoFile() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "commit-first";
    final var token = this.protocolToken();
    final var content = chart(name, "1.0.0", "first", null);
    this.failTheCommitAfterTheManifestFile();

    final var response = this.pushOci(repo, name, "1.0.0", content, token);

    assertThat(response.getStatus()).as("the push fails").isGreaterThanOrEqualTo(500);
    assertThat(this.rows("helm_oci_manifest", repo)).as("no manifest row").isZero();
    assertThat(this.rows("helm_chart", repo)).as("no chart row").isZero();
    assertThat(this.manifestFile(repo, name, "1.0.0"))
        .as("and no file for a row that does not exist")
        .isNull();

    Mockito.reset(this.helmStorageService);

    assertThat(this.pushOci(repo, name, "1.0.0", content, token).getStatus())
        .as("the same push repeated once the commit works")
        .isEqualTo(201);
    assertThat(this.manifestFile(repo, name, "1.0.0")).isNotNull();
  }

  @Test
  @DisplayName("an override whose commit fails leaves the manifest file it had (RPS-1366)")
  void overrideWhoseCommitFailsKeepsTheOldFile() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "commit-override";
    final var token = this.protocolToken();
    assertThat(
            this.pushOci(repo, name, "1.0.0", chart(name, "1.0.0", "original", null), token)
                .getStatus())
        .as("the seed")
        .isEqualTo(201);
    final var seeded = this.manifestFile(repo, name, "1.0.0");
    assertThat(seeded).isNotNull();
    this.failTheCommitAfterTheManifestFile();

    final var response =
        this.pushOci(repo, name, "1.0.0", chart(name, "1.0.0", "override", null), token);

    assertThat(response.getStatus()).as("the push fails").isGreaterThanOrEqualTo(500);
    assertThat(this.manifestFile(repo, name, "1.0.0"))
        .as("the file is what the surviving row describes, not the bytes of the failed push")
        .isEqualTo(seeded);
    assertThat(this.manifestDigest(repo, name, "1.0.0"))
        .as("and the row still describes the file")
        .isEqualTo(digest("SHA-256", seeded));
  }
}
