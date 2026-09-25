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
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciBlobRepository;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>The races are forced, not hoped for: right after a row is read, another connection bumps its
 * {@code version_lock}, as a concurrent request would. Runs without a test transaction since the
 * pushes have to commit; it deletes the repos and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Helm OCI pushes that lose a race on a row (RPS-1342)")
@Import(HelmOciConcurrentPushIT.ReadHook.class)
class HelmOciConcurrentPushIT extends AbstractIntegrationTest {

  private static final String OCTET_STREAM = "application/octet-stream";
  private static final long TIMEOUT_SECONDS = 30;

  /** Called with the method name after every call of the three repositories the pushes read. */
  private static final AtomicReference<Consumer<String>> AFTER_READ = new AtomicReference<>();

  private static final String MANIFEST_READ =
      "HelmOciManifestRepository.findByRepoIdAndNameAndReference";
  private static final String VERSION_READ = "HelmChartVersionRepository.findByChartAndVersion";
  private static final String BLOB_READ = "HelmOciBlobRepository.findByRepoIdAndDigest";

  private static final String BUMP_MANIFEST =
      "update helm_oci_manifest set version_lock = version_lock + 1 where repo_id = ?";
  private static final String BUMP_VERSION =
      """
      update helm_chart_version set version_lock = version_lock + 1
      where chart_id in (select id from helm_chart where repo_id = ?)
      """;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmStorageService helmStorageService;

  /**
   * Wraps the manifest, chart version and blob repositories in a proxy that calls through and then
   * runs {@link #AFTER_READ}: a Spring Data repository cannot be a {@code @MockitoSpyBean}.
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

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    AFTER_READ.set(null);
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

  private String protocolToken() {
    final var info =
        this.userTxService.create(
            uniqueUsername("helm-oci"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(info.getId());

    return this.protocolBearerTokenFor(this.userRepository.findById(info.getId()).orElseThrow());
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

    AFTER_READ.set(
        called -> {
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
    assertThat(this.bumps).as("three runs of the save").hasValue(3);
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
}
