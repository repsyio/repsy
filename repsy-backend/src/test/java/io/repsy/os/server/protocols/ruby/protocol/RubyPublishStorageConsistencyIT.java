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
package io.repsy.os.server.protocols.ruby.protocol;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1060: a Ruby gem push leaves storage and the database in agreement, whichever half of it
 * fails.
 *
 * <p>The version row is written first and the .gem file second, inside one transaction. So a push
 * whose row cannot be written never touches the file, and a push whose file cannot be written
 * leaves no row (and, for a new version, no file) behind.
 *
 * <p>Runs without a test transaction, unlike {@link RubyGemProtocolIT}: a failed row write aborts a
 * PostgreSQL transaction, so inside a test transaction the state afterwards could not be read, and
 * a race between two pushes needs both to commit. It deletes the repos and users it commits.
 *
 * <p>{@link UsageUpdateService} is mocked: the mock records whether a push reported any usage.
 *
 * <p>Replacing a version whose file write fails part-way is not covered beyond keeping its row: the
 * file system strategy truncates the file in place, so its old bytes cannot be restored.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Ruby push keeps storage and the database in agreement (RPS-1060)")
class RubyPublishStorageConsistencyIT extends AbstractIntegrationTest {

  /** A description the database refuses through {@link #rejectDescriptionAtTheDatabase()}. */
  private static final String REJECTED_DESCRIPTION = "reject-me";

  private static final String REJECT_DESCRIPTION_CONSTRAINT = "ch_ruby_gem_version__it_rejected";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private RubyStorageService rubyStorageService;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the gems with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.jdbcTemplate.execute(
        "alter table ruby_gem_version drop constraint if exists " + REJECT_DESCRIPTION_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row with {@link #REJECTED_DESCRIPTION}: a check violation,
   * which is a {@code DataIntegrityViolationException} but not the unique index on (gem, version,
   * platform).
   */
  private void rejectDescriptionAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table ruby_gem_version add constraint "
            + REJECT_DESCRIPTION_CONSTRAINT
            + " check (description is distinct from '"
            + REJECTED_DESCRIPTION
            + "')");
  }

  private Repo rubyRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("ruby-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.RUBY, false, null);
    this.createdRepoIds.add(created.getId());
    this.rubyStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("ruby-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniqueGemName() {
    return "consistency-" + randomTag();
  }

  private MockHttpServletResponse push(final Repo repo, final byte[] gem, final String token)
      throws Exception {
    final var request =
        post(PUBLISH_PATH, repo.getName())
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(gem);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static Path gemFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve("gems").resolve(name).resolve(name + "-" + version + ".gem");
  }

  private int storedVersionCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from ruby_gem_version v
              join ruby_gem g on g.id = v.gem_id
            where g.repo_id = ? and g.name = ?
            """,
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private int storedGemCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from ruby_gem where repo_id = ? and name = ?",
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private String storedChecksum(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select v.checksum from ruby_gem_version v
          join ruby_gem g on g.id = v.gem_id
        where g.repo_id = ? and g.name = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  private String storedVersionsChecksum(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForObject(
        "select versions_checksum from ruby_gem where repo_id = ? and name = ?",
        String.class,
        repo.getId(),
        name);
  }

  /** Writes the file like the real service, then fails, as a storage that dies mid-push would. */
  private void failAfterWritingFile() {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.rubyStorageService)
        .writeGem(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a push records the row, the versions checksum and the file together")
  void aPushStoresRowAndFile() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    final var gem = gem(name, "1.0.0");

    final var response = this.push(repo, gem, this.adminToken());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(gemFile(repo, name, "1.0.0")).hasBinaryContent(gem);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionsChecksum(repo, name)).isNotBlank();
  }

  @Test
  @DisplayName("a row the database rejects leaves no file behind")
  void rejectedRowLeavesNoFile() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    this.rejectDescriptionAtTheDatabase();

    final var response =
        this.push(repo, gem(name, "1.0.0", "ruby", REJECTED_DESCRIPTION), this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error, not a 409")
        .isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedGemCount(repo, name)).isZero();
    assertThat(gemFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a row the database rejects leaves the file and row of the version it would replace")
  void rejectedOverrideKeepsTheExistingVersion() throws Exception {
    final var repo = this.rubyRepo(true);
    final var name = uniqueGemName();
    final var token = this.adminToken();
    final var original = gem(name, "1.0.0", "ruby", "original");

    assertThat(this.push(repo, original, token).getStatus()).isEqualTo(200);
    final var originalChecksum = this.storedChecksum(repo, name, "1.0.0");
    this.rejectDescriptionAtTheDatabase();

    final var response = this.push(repo, gem(name, "1.0.0", "ruby", REJECTED_DESCRIPTION), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(gemFile(repo, name, "1.0.0")).hasBinaryContent(original);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedChecksum(repo, name, "1.0.0")).isEqualTo(originalChecksum);
  }

  @Test
  @DisplayName("a push that loses the race for a version does not replace the winner's file")
  void loserOfTheRaceKeepsTheWinnersFile() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    final var token = this.adminToken();
    // The gem exists before the race, so both pushes race for the version row, not for the gem.
    assertThat(this.push(repo, gem(name, "1.0.0"), token).getStatus()).isEqualTo(200);
    final var winnerGem = gem(name, "1.0.1", "ruby", "winner");
    final var loserGem = gem(name, "1.0.1", "ruby", "loser");

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
        .when(this.rubyStorageService)
        .writeGem(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, winnerGem, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser = executor.submit(() -> this.push(repo, loserGem, token));
      // Long enough for the loser to reach its row insert and wait on the winner's row. If it is
      // slower, it is turned away by the existing-version check instead: the assertions hold both
      // ways.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(409);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    // Once for the winner, and once for the push that seeded the gem.
    verify(this.rubyStorageService, times(2)).writeGem(any(), any(), any(), any(), any(), any());
    assertThat(gemFile(repo, name, "1.0.1")).hasBinaryContent(winnerGem);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(this.storedChecksum(repo, name, "1.0.1"))
        .as("the row describes the winner's gem, the one that is stored")
        .isEqualTo(sha256Hex(winnerGem));
  }

  @Test
  @DisplayName(
      "two concurrent first pushes of a new gem name with different versions both succeed"
          + " (RPS-1125)")
  void concurrentFirstPushesOfANewGemShareOneGemRow() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    final var token = this.adminToken();

    // Holds the first push inside the storage write, so its gem row is uncommitted when the
    // second push reaches its own insert of that gem and has to wait for it.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.rubyStorageService)
        .writeGem(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(() -> this.push(repo, gem(name, "1.0.0", "ruby", "a"), token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(() -> this.push(repo, gem(name, "2.0.0", "ruby", "b"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedGemCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "two concurrent first pushes of a new gem name with the same version: the loser answers"
          + " 409, not 500 (RPS-1125)")
  void concurrentFirstPushesOfANewGemWithTheSameVersionOneLoses() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    final var token = this.adminToken();

    // Holds the winner inside the storage write, so its gem and version rows are uncommitted when
    // the loser reaches its own insert of the gem and has to wait for it.
    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.rubyStorageService)
        .writeGem(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.push(repo, gem(name, "1.0.0", "ruby", "winner"), token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(() -> this.push(repo, gem(name, "1.0.0", "ruby", "loser"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus())
          .as("a first push of a new gem name never answers 500 on this race (RPS-1125)")
          .isEqualTo(409);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedGemCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a plain storage I/O failure surfaces as a server error, not invalidGemFile (RPS-1126)")
  void storageIoFailureSurfacesAsServerErrorNotInvalidGemFile() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    doAnswer(
            invocation -> {
              throw new IOException("disk full");
            })
        .when(this.rubyStorageService)
        .writeGem(any(), any(), any(), any(), any(), any());

    final var response = this.push(repo, gem(name, "1.0.0"), this.adminToken());

    assertThat(response.getStatus())
        .as("a storage I/O failure is a server error, not the client's bad gem file")
        .isEqualTo(500);
    assertThat(response.getContentAsString()).doesNotContain("invalidGemFile");
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedGemCount(repo, name)).isZero();
    assertThat(gemFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write rolls back the row and removes the file of a new version")
  void failedFileWriteRollsBackANewVersion() throws Exception {
    final var repo = this.rubyRepo(false);
    final var name = uniqueGemName();
    this.failAfterWritingFile();

    final var response = this.push(repo, gem(name, "1.0.0"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedGemCount(repo, name)).isZero();
    assertThat(gemFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write keeps the row of the version being replaced")
  void failedFileWriteKeepsTheReplacedVersion() throws Exception {
    final var repo = this.rubyRepo(true);
    final var name = uniqueGemName();
    final var token = this.adminToken();
    assertThat(this.push(repo, gem(name, "1.0.0", "ruby", "original"), token).getStatus())
        .isEqualTo(200);
    final var originalChecksum = this.storedChecksum(repo, name, "1.0.0");
    this.failAfterWritingFile();

    final var response = this.push(repo, gem(name, "1.0.0", "ruby", "second"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedChecksum(repo, name, "1.0.0")).isEqualTo(originalChecksum);
    assertThat(gemFile(repo, name, "1.0.0")).exists();
  }

  private static String sha256Hex(final byte[] bytes) throws IOException {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(bytes))) {
      return upload.sha256Hex();
    }
  }
}
