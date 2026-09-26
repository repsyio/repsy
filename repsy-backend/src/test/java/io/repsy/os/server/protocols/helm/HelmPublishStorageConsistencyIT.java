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

import static io.repsy.os.server.protocols.helm.HelmChartFixtures.UPLOAD_PATH;
import static io.repsy.os.server.protocols.helm.HelmChartFixtures.chart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1124: a classic Helm chart upload leaves storage and the database in agreement, whichever
 * half of it fails.
 *
 * <p>The version row is written and flushed first and the {@code .tgz} second, inside one
 * transaction. So an upload whose row cannot be written never touches the file, an upload that
 * loses the race for a version cannot replace the winner's file, and an upload whose file cannot be
 * written leaves no row (and, for a new version, no file) behind.
 *
 * <p>Runs without a test transaction, unlike {@link HelmPushMetadataLengthIT}: a failed row write
 * aborts a PostgreSQL transaction, so inside a test transaction the state afterwards could not be
 * read, and a race between two uploads needs both to commit. It deletes the repos and users it
 * commits.
 *
 * <p>{@link UsageUpdateService} is mocked: the mock records whether an upload reported any usage.
 *
 * <p>Replacing a version whose file write fails part-way is not covered beyond keeping its row: the
 * file system strategy truncates the file in place, so its old bytes cannot be restored.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Helm chart upload keeps storage and the database in agreement (RPS-1124)")
class HelmPublishStorageConsistencyIT extends AbstractIntegrationTest {

  /** An {@code appVersion} the database refuses through {@link #rejectAppVersionAtTheDatabase}. */
  private static final String REJECTED_APP_VERSION = "reject-me";

  private static final String REJECT_APP_VERSION_CONSTRAINT = "ch_helm_chart_version__it_rejected";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private HelmStorageService helmStorageService;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the charts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.jdbcTemplate.execute(
        "alter table helm_chart_version drop constraint if exists "
            + REJECT_APP_VERSION_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row with {@link #REJECTED_APP_VERSION}: a check violation,
   * which is a {@code DataIntegrityViolationException} but not the unique index on (chart,
   * version).
   */
  private void rejectAppVersionAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table helm_chart_version add constraint "
            + REJECT_APP_VERSION_CONSTRAINT
            + " check (app_version is distinct from '"
            + REJECTED_APP_VERSION
            + "')");
  }

  private Repo helmRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("helm-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.HELM, false, null);
    this.createdRepoIds.add(created.getId());
    this.helmStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
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

  private static String uniqueChartName() {
    return "consistency-" + randomTag();
  }

  private MockHttpServletResponse push(final Repo repo, final byte[] chart, final String token)
      throws Exception {
    final var request =
        multipart(UPLOAD_PATH, repo.getName())
            .part(new MockPart("chart", "chart.tgz", chart))
            .header(AUTHORIZATION, token);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
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

  private int storedChartCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from helm_chart where repo_id = ? and name = ?",
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

  /** Writes the file like the real service, then fails, as a storage that dies mid-upload would. */
  private void failAfterWritingFile() {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());
  }

  @Test
  @DisplayName("an upload records the row and the file together")
  void anUploadStoresRowAndFile() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    final var chart = chart(name, "1.0.0");

    final var response = this.push(repo, chart, this.adminToken());

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(chartFile(repo, name, "1.0.0")).hasBinaryContent(chart);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDigest(repo, name, "1.0.0"))
        .isEqualTo(HelmChartFixtures.digest("SHA-256", chart));
  }

  @Test
  @DisplayName("a row the database rejects leaves no file behind")
  void rejectedRowLeavesNoFile() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    this.rejectAppVersionAtTheDatabase();

    final var response =
        this.push(repo, chart(name, "1.0.0", REJECTED_APP_VERSION, null), this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error, not a 409")
        .isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedChartCount(repo, name)).isZero();
    assertThat(chartFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a row the database rejects leaves the file and row of the version it would replace")
  void rejectedOverrideKeepsTheExistingVersion() throws Exception {
    final var repo = this.helmRepo(true);
    final var name = uniqueChartName();
    final var token = this.adminToken();
    final var original = chart(name, "1.0.0", "original", null);

    assertThat(this.push(repo, original, token).getStatus()).isEqualTo(201);
    final var originalDigest = this.storedDigest(repo, name, "1.0.0");
    this.rejectAppVersionAtTheDatabase();

    final var response = this.push(repo, chart(name, "1.0.0", REJECTED_APP_VERSION, null), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(chartFile(repo, name, "1.0.0")).hasBinaryContent(original);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDigest(repo, name, "1.0.0")).isEqualTo(originalDigest);
  }

  @Test
  @DisplayName("an upload that loses the race for a version does not replace the winner's file")
  void loserOfTheRaceKeepsTheWinnersFile() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    final var token = this.adminToken();
    // The chart exists before the race, so both uploads race for the version row, not the chart.
    assertThat(this.push(repo, chart(name, "1.0.0"), token).getStatus()).isEqualTo(201);
    final var winnerChart = chart(name, "1.0.1", "winner", null);
    final var loserChart = chart(name, "1.0.1", "loser", null);

    // Holds the winner inside the storage write, which is inside its transaction, so the loser
    // starts while the winner's row is uncommitted and the version is not yet visible to it.
    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, winnerChart, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser = executor.submit(() -> this.push(repo, loserChart, token));
      // Long enough for the loser to reach its row insert and wait on the winner's row. If it is
      // slower, it is turned away by the existing-version check instead: the assertions hold both
      // ways.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(409);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    // Once for the winner, and once for the upload that seeded the chart.
    verify(this.helmStorageService, times(2)).saveChart(any(), any(), any());
    assertThat(chartFile(repo, name, "1.0.1")).hasBinaryContent(winnerChart);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(this.storedDigest(repo, name, "1.0.1"))
        .as("the row describes the winner's chart, the one that is stored")
        .isEqualTo(HelmChartFixtures.digest("SHA-256", winnerChart));
  }

  @Test
  @DisplayName(
      "two concurrent first uploads of a new chart name with different versions both succeed")
  void concurrentFirstUploadsOfANewChartShareOneChartRow() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    final var token = this.adminToken();

    // Holds the first upload inside the storage write, so its chart row is uncommitted when the
    // second upload reaches its own insert of that chart and has to wait for it.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(() -> this.push(repo, chart(name, "1.0.0", "a", null), token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(() -> this.push(repo, chart(name, "2.0.0", "b", null), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedChartCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "two concurrent first uploads of a new chart name with the same version: the loser answers"
          + " 409 and does not replace the winner's file")
  void concurrentFirstUploadsOfANewChartWithTheSameVersionOneLoses() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    final var token = this.adminToken();
    final var winnerChart = chart(name, "1.0.0", "winner", null);

    // Holds the winner inside the storage write, so its chart and version rows are uncommitted
    // when the loser reaches its own insert of the chart and has to wait for it.
    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, winnerChart, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(() -> this.push(repo, chart(name, "1.0.0", "loser", null), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus())
          .as("a first upload of a new chart name never answers 500 on this race")
          .isEqualTo(409);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    verify(this.helmStorageService, times(1)).saveChart(any(), any(), any());
    assertThat(chartFile(repo, name, "1.0.0")).hasBinaryContent(winnerChart);
    assertThat(this.storedChartCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDigest(repo, name, "1.0.0"))
        .isEqualTo(HelmChartFixtures.digest("SHA-256", winnerChart));
  }

  @Test
  @DisplayName("a storage I/O failure is a server error and leaves no row or file behind")
  void storageIoFailureLeavesNothingBehind() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    doAnswer(
            invocation -> {
              throw new UncheckedIOException(new IOException("disk full"));
            })
        .when(this.helmStorageService)
        .saveChart(any(), any(), any());

    final var response = this.push(repo, chart(name, "1.0.0"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedChartCount(repo, name)).isZero();
    assertThat(chartFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write rolls back the row and removes the file of a new version")
  void failedFileWriteRollsBackANewVersion() throws Exception {
    final var repo = this.helmRepo(false);
    final var name = uniqueChartName();
    this.failAfterWritingFile();

    final var response = this.push(repo, chart(name, "1.0.0"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedChartCount(repo, name)).isZero();
    assertThat(chartFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write keeps the row of the version being replaced")
  void failedFileWriteKeepsTheReplacedVersion() throws Exception {
    final var repo = this.helmRepo(true);
    final var name = uniqueChartName();
    final var token = this.adminToken();
    assertThat(this.push(repo, chart(name, "1.0.0", "original", null), token).getStatus())
        .isEqualTo(201);
    final var originalDigest = this.storedDigest(repo, name, "1.0.0");
    this.failAfterWritingFile();

    final var response = this.push(repo, chart(name, "1.0.0", "second", null), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDigest(repo, name, "1.0.0")).isEqualTo(originalDigest);
    assertThat(chartFile(repo, name, "1.0.0")).exists();
  }
}
