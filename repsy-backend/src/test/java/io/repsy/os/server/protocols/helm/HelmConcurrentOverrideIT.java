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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.chart.repositories.HelmChartVersionRepository;
import io.repsy.os.server.protocols.helm.shared.chart.services.HelmChartService;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RPS-1273: two concurrent pushes that both override one chart version take turns, and the later
 * one wins, instead of the loser failing on the version row's optimistic {@code @Version} check
 * with a 500.
 *
 * <p>Both the classic upload and the OCI manifest push reach the version row through {@code
 * HelmChartService}, which locks the chart row first. Each test holds the first push open inside
 * its transaction, waits until the database shows the second push blocked on a lock (no fixed
 * sleep), and then lets the first one finish.
 *
 * <p>Runs without a test transaction, since both pushes have to commit. It deletes the repos and
 * users it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Concurrent overriding pushes of one Helm chart version take turns (RPS-1273)")
class HelmConcurrentOverrideIT extends AbstractIntegrationTest {

  private static final long TIMEOUT_SECONDS = 30;
  private static final String OCTET_STREAM = "application/octet-stream";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private HelmStorageService helmStorageService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private HelmChartService helmChartService;
  @Autowired private HelmChartVersionRepository helmChartVersionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo overridableHelmRepo() {
    final var name = uniqueRepoName("helm-race");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("helm-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private MockHttpServletResponse push(final Repo repo, final byte[] chart, final String token)
      throws Exception {
    final var request =
        multipart(UPLOAD_PATH, repo.getName())
            .part(new MockPart("chart", "chart.tgz", chart))
            .header(AUTHORIZATION, token);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /** Uploads {@code chartBytes} as the layer, then puts a manifest for it under {@code tag}. */
  private MockHttpServletResponse pushOci(
      final Repo repo,
      final String name,
      final String tag,
      final byte[] chartBytes,
      final String token)
      throws Exception {
    final var layerDigest = digest("SHA-256", chartBytes);
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
    final var upload =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{name}/blobs/uploads/{id}", repo.getName(), name, uploadId)
                    .param("digest", layerDigest)
                    .contentType(OCTET_STREAM)
                    .content(chartBytes)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(upload.getStatus()).as("chart layer upload").isEqualTo(201);

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
                layerDigest,
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

  private static Path chartFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve("charts").resolve(name + "-" + version + ".tgz");
  }

  private int storedVersionCount(final Repo repo, final String name) {
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

  private String storedDigest(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select v.digest from helm_chart_version v
          join helm_chart c on c.id = v.chart_id
        where c.repo_id = ? and c.name = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  private long storedSize(final Repo repo, final String name, final String version) {
    final var size =
        this.jdbcTemplate.queryForObject(
            """
            select v.size from helm_chart_version v
              join helm_chart c on c.id = v.chart_id
            where c.repo_id = ? and c.name = ? and v.version = ?
            """,
            Long.class,
            repo.getId(),
            name,
            version);

    return size == null ? 0 : size;
  }

  /**
   * Waits until the second push is either blocked on a database lock held by the first, or done.
   * Both are the states the assertions after it are written for: a push that is done here got its
   * answer without waiting, which the assertions then judge.
   */
  private void awaitBlockedOrDone(final Future<?> second) throws Exception {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

    while (!second.isDone() && System.nanoTime() < deadline) {
      final var blocked =
          this.jdbcTemplate.queryForObject(
              """
              select count(*) from pg_stat_activity
              where datname = current_database()
                and wait_event_type = 'Lock'
                and query ilike '%helm_chart%'
              """,
              Integer.class);
      if (blocked != null && blocked > 0) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(20);
    }
  }

  @Test
  @DisplayName("two concurrent classic overrides both succeed; the later one's file and row win")
  void concurrentClassicOverridesTakeTurns() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-" + randomTag();
    final var token = this.adminToken();
    assertThat(this.push(repo, chart(name, "1.0.0", "original", null), token).getStatus())
        .isEqualTo(201);
    final var first = chart(name, "1.0.0", "first", null);
    final var second = chart(name, "1.0.0", "second", null);

    // Holds the first override inside the storage write, which is inside its transaction.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var firstPush = executor.submit(() -> this.push(repo, first, token));
      assertThat(firstWriting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

      final var secondPush = executor.submit(() -> this.push(repo, second, token));
      this.awaitBlockedOrDone(secondPush);
      releaseFirst.countDown();

      assertThat(firstPush.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(secondPush.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus())
          .as("the second override waits for the first and then wins, it does not answer 500")
          .isEqualTo(201);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    // The upload that seeded the version, and one per override.
    verify(this.helmStorageService, times(3)).saveChart(any(), any(), any());
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(chartFile(repo, name, "1.0.0")).hasBinaryContent(second);
    assertThat(this.storedDigest(repo, name, "1.0.0"))
        .as("the row describes the chart that is stored")
        .isEqualTo(digest("SHA-256", second));
    assertThat(this.storedSize(repo, name, "1.0.0")).isEqualTo(second.length);
  }

  @Test
  @DisplayName("an OCI override that meets another override of the version waits and then wins")
  void concurrentOciOverrideWaitsForTheOtherOverride() throws Exception {
    final var repo = this.overridableHelmRepo();
    final var name = "race-oci-" + randomTag();
    final var token = this.adminToken();
    final var original = chart(name, "1.0.0", "original", null);
    assertThat(this.pushOci(repo, name, "1.0.0", original, token).getStatus()).isEqualTo(201);

    final var first = chart(name, "1.0.0", "first", null);
    final var second = chart(name, "1.0.0", "second", null);
    final var firstForm =
        HelmChartForm.builder()
            .name(name)
            .version("1.0.0")
            .appVersion("first")
            .digest(digest("SHA-256", first))
            .size(first.length)
            .build();

    // An override that has updated the version row and not committed, held open by this test's own
    // transaction: the OCI push below reaches the same row while it is uncommitted.
    final var firstUpdated = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var firstOverride =
          executor.submit(
              () ->
                  new TransactionTemplate(this.transactionManager)
                      .execute(
                          status -> {
                            this.helmChartService.findOrCreate(firstForm, repo.getId());
                            // The update is sent, so its row lock is held, not just pending.
                            this.helmChartVersionRepository.flush();
                            firstUpdated.countDown();
                            return this.await(releaseFirst);
                          }));
      assertThat(firstUpdated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

      final var secondPush =
          executor.submit(() -> this.pushOci(repo, name, "1.0.0", second, token));
      this.awaitBlockedOrDone(secondPush);
      releaseFirst.countDown();

      assertThat(firstOverride.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(secondPush.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus())
          .as("the OCI override waits for the other override and then wins, not a 500")
          .isEqualTo(201);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDigest(repo, name, "1.0.0")).isEqualTo(digest("SHA-256", second));
    assertThat(this.storedSize(repo, name, "1.0.0")).isEqualTo(second.length);
  }

  private boolean await(final CountDownLatch latch) {
    try {
      return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
