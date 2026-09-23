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
package io.repsy.os.server.protocols.pypi.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1124: a PyPI upload leaves storage and the database in agreement, whichever half of it fails.
 *
 * <p>The package and release rows are written first and the archive (with its digest) second,
 * inside one transaction that holds the package's row lock. So an upload whose rows cannot be
 * written never touches storage, an upload whose archive cannot be written leaves no row (and, for
 * a file that was not there before, no file) behind, and two uploads of one package take turns
 * instead of racing on the file.
 *
 * <p>Runs without a test transaction, unlike the other PyPI upload ITs: a failed row write aborts a
 * PostgreSQL transaction, so inside a test transaction the state afterwards could not be read, and
 * a race between two uploads needs both to commit. It deletes the repos it commits. Uploads go
 * through {@link PypiProtocolFacadeImpl#uploadPackage}, the same call the wire handler makes.
 *
 * <p>Replacing a file whose write fails part-way is not covered beyond keeping its row: the file
 * system strategy truncates the file in place, so its old bytes cannot be restored.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PyPI upload keeps storage and the database in agreement (RPS-1124)")
class PypiPublishStorageConsistencyIT extends AbstractIntegrationTest {

  /** A summary the database refuses through {@link #rejectSummaryAtTheDatabase()}. */
  private static final String REJECTED_SUMMARY = "reject-me";

  private static final String REJECT_SUMMARY_CONSTRAINT = "ch_pypi_release__it_rejected";

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private PypiStorageService pypiStorageService;

  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.jdbcTemplate.execute(
        "alter table pypi_release drop constraint if exists " + REJECT_SUMMARY_CONSTRAINT);
  }

  /**
   * Makes the database refuse a release row with {@link #REJECTED_SUMMARY}: a check violation,
   * which is a {@code DataIntegrityViolationException} but not a unique index.
   */
  private void rejectSummaryAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table pypi_release add constraint "
            + REJECT_SUMMARY_CONSTRAINT
            + " check (summary is distinct from '"
            + REJECTED_SUMMARY
            + "')");
  }

  private RepoInfo pypiRepo(final boolean allowOverride) {
    final var repoInfo =
        this.repoTxService.createRepo(uniqueRepoName("pypi-cons"), RepoType.PYPI, false, null);
    this.createdRepoIds.add(repoInfo.getId());
    this.pypiStorageService.createRepo(repoInfo.getStorageKey());
    repoInfo.setAllowOverride(allowOverride);

    return repoInfo;
  }

  private static String uniquePackageName() {
    return "consistency-" + randomTag();
  }

  private static byte[] content(final String marker) {
    return marker.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256Hex(final byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String wheel(final String packageName, final String version) {
    return packageName.replace('-', '_') + "-" + version + "-py3-none-any.whl";
  }

  private static String sdist(final String packageName, final String version) {
    return packageName + "-" + version + ".tar.gz";
  }

  /** Uploads one file and returns the context, whose {@code usages} property a success sets. */
  private ProtocolContext upload(
      final RepoInfo repo,
      final String packageName,
      final String version,
      final String filename,
      final byte[] content,
      final String summary)
      throws Exception {

    final var parameters = new HashMap<String, Object>();
    parameters.put("name", packageName);
    parameters.put("version", version);
    parameters.put("summary", summary);
    parameters.put("requires_python", ">=3.9");
    parameters.put("sha256_digest", sha256Hex(content));

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repo)
            .build());
    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));

    return context;
  }

  private Path archive(final RepoInfo repo, final String packageName, final String filename) {
    final Repo entity = this.repoRepository.findByName(repo.getName()).orElseThrow();

    return storageDirOf(entity).resolve(packageName).resolve(filename);
  }

  private static Path digestOf(final Path archive) {
    return archive.resolveSibling(archive.getFileName() + ".sha256");
  }

  private int releaseCount(final RepoInfo repo, final String packageName) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from pypi_release r
              join pypi_package p on p.id = r.package_id
            where p.repo_id = ? and p.normalized_name = ?
            """,
            Integer.class,
            repo.getId(),
            packageName);

    return count == null ? 0 : count;
  }

  private int packageCount(final RepoInfo repo, final String packageName) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from pypi_package where repo_id = ? and normalized_name = ?",
            Integer.class,
            repo.getId(),
            packageName);

    return count == null ? 0 : count;
  }

  private String storedSummary(
      final RepoInfo repo, final String packageName, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select r.summary from pypi_release r
          join pypi_package p on p.id = r.package_id
        where p.repo_id = ? and p.normalized_name = ? and r.version = ?
        """,
        String.class,
        repo.getId(),
        packageName,
        version);
  }

  /** Writes the archive like the real service, then fails, as a storage that dies mid-upload. */
  private void failAfterWritingArchive() throws Exception {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.pypiStorageService)
        .writePackageArchive(any(), any(), any(), any());
  }

  /** Holds the first archive write, which is inside its transaction, until the returned latch. */
  private CountDownLatch holdFirstArchiveWrite(final CountDownLatch firstWriting) throws Exception {
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.pypiStorageService)
        .writePackageArchive(any(), any(), any(), any());

    return releaseFirst;
  }

  private static <T> T resultOf(final Future<T> future) throws Exception {
    return future.get(30, TimeUnit.SECONDS);
  }

  private static Throwable failureOf(final Future<?> future) throws Exception {
    try {
      future.get(30, TimeUnit.SECONDS);
    } catch (final ExecutionException e) {
      return e.getCause();
    }

    return null;
  }

  @Test
  @DisplayName("an upload records the rows, the archive and its digest together")
  void anUploadStoresRowsAndFiles() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    final var bytes = content("wheel");

    final var context = this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), bytes, "first");

    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).hasBinaryContent(bytes);
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0"))))
        .hasContent(sha256Hex(bytes));
    assertThat(this.releaseCount(repo, name)).isOne();
    assertThat(this.storedSummary(repo, name, "1.0.0")).isEqualTo("first");
    assertThat(context.<BaseUsages>getProperty("usages")).isNotNull();
  }

  @Test
  @DisplayName("rows the database rejects leave no archive behind, and no usage to report")
  void rejectedRowsLeaveNoFile() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    this.rejectSummaryAtTheDatabase();

    assertThatThrownBy(
            () ->
                this.upload(
                    repo, name, "1.0.0", wheel(name, "1.0.0"), content("wheel"), REJECTED_SUMMARY))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.releaseCount(repo, name)).isZero();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0")))).doesNotExist();
    verify(this.pypiStorageService, never()).writePackageArchive(any(), any(), any(), any());
  }

  @Test
  @DisplayName("rows the database rejects leave the archive and row of the file they would replace")
  void rejectedOverrideKeepsTheExistingFile() throws Exception {
    final var repo = this.pypiRepo(true);
    final var name = uniquePackageName();
    final var original = content("original");
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), original, "original");
    this.rejectSummaryAtTheDatabase();

    assertThatThrownBy(
            () ->
                this.upload(
                    repo,
                    name,
                    "1.0.0",
                    wheel(name, "1.0.0"),
                    content("replacement"),
                    REJECTED_SUMMARY))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).hasBinaryContent(original);
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0"))))
        .hasContent(sha256Hex(original));
    assertThat(this.storedSummary(repo, name, "1.0.0")).isEqualTo("original");
  }

  @Test
  @DisplayName("a rejected second file of a release leaves the release and its first file alone")
  void rejectedSecondFileLeavesNoFile() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    final var first = content("sdist");
    this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), first, "first");
    this.rejectSummaryAtTheDatabase();

    assertThatThrownBy(
            () ->
                this.upload(
                    repo, name, "1.0.0", wheel(name, "1.0.0"), content("wheel"), REJECTED_SUMMARY))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).hasBinaryContent(first);
    assertThat(this.releaseCount(repo, name)).isOne();
    assertThat(this.storedSummary(repo, name, "1.0.0")).isEqualTo("first");
  }

  @Test
  @DisplayName("a failed archive write rolls back the rows and removes the files of a new file")
  void failedWriteRollsBackANewFile() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    this.failAfterWritingArchive();

    assertThatThrownBy(
            () ->
                this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("wheel"), "summary"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.releaseCount(repo, name)).isZero();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0")))).doesNotExist();
  }

  @Test
  @DisplayName("a failed archive write keeps the row of the file being replaced")
  void failedWriteKeepsTheReplacedFile() throws Exception {
    final var repo = this.pypiRepo(true);
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("original"), "original");
    this.failAfterWritingArchive();

    assertThatThrownBy(
            () ->
                this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("second"), "second"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(this.releaseCount(repo, name)).isOne();
    assertThat(this.storedSummary(repo, name, "1.0.0")).isEqualTo("original");
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).exists();
  }

  @Test
  @DisplayName("a failed write of a second file removes only that file, and keeps the release")
  void failedWriteOfASecondFileKeepsTheRelease() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    final var first = content("sdist");
    this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), first, "first");
    this.failAfterWritingArchive();

    assertThatThrownBy(
            () ->
                this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("wheel"), "second"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0")))).doesNotExist();
    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).hasBinaryContent(first);
    assertThat(this.releaseCount(repo, name)).isOne();
    assertThat(this.storedSummary(repo, name, "1.0.0")).isEqualTo("first");
  }

  /**
   * Two uploads of the same file to a repo that does not allow overrides both pass the existence
   * check before either has written, so only the package row lock keeps the loser from replacing
   * the winner's file.
   */
  private void assertLoserOfARaceForTheSameFileKeepsTheWinnersFile(
      final RepoInfo repo, final String name, final String version) throws Exception {

    final var filename = wheel(name, version);
    final var winnerBytes = content("winner");
    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = this.holdFirstArchiveWrite(winnerWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.upload(repo, name, version, filename, winnerBytes, "winner"));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(
              () -> this.upload(repo, name, version, filename, content("loser"), "loser"));
      // Long enough for the loser to reach the package row and wait on the winner. If it is
      // slower, it is turned away by the existence check instead: the assertions hold both ways.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(resultOf(winner).<BaseUsages>getProperty("usages")).isNotNull();
      assertThat(failureOf(loser))
          .isInstanceOf(AccessNotAllowedException.class)
          .hasMessage("fileAlreadyExists");
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(this.archive(repo, name, filename)).hasBinaryContent(winnerBytes);
    assertThat(digestOf(this.archive(repo, name, filename))).hasContent(sha256Hex(winnerBytes));
    assertThat(this.storedSummary(repo, name, version)).isEqualTo("winner");
  }

  @Test
  @DisplayName("a first upload that loses the race for a file does not replace the winner's file")
  void loserOfTheRaceForANewPackageKeepsTheWinnersFile() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();

    this.assertLoserOfARaceForTheSameFileKeepsTheWinnersFile(repo, name, "1.0.0");

    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name)).isOne();
    // Once, for the winner: the loser never reached the storage.
    verify(this.pypiStorageService, times(1)).writePackageArchive(any(), any(), any(), any());
  }

  @Test
  @DisplayName("an upload that loses the race for a file of an existing package keeps the winner's")
  void loserOfTheRaceForAnExistingPackageKeepsTheWinnersFile() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("seed"), "seed");

    this.assertLoserOfARaceForTheSameFileKeepsTheWinnersFile(repo, name, "1.0.1");

    assertThat(this.releaseCount(repo, name)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "two concurrent uploads of different files of a new release both succeed, none is orphaned")
  void concurrentFilesOfANewReleaseBothSucceed() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    final var sdistBytes = content("sdist");
    final var wheelBytes = content("wheel");
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = this.holdFirstArchiveWrite(firstWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(
              () -> this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), sdistBytes, "sdist"));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(
              () -> this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), wheelBytes, "wheel"));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(failureOf(first)).isNull();
      assertThat(failureOf(second)).as("the second file is not a conflict").isNull();
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).hasBinaryContent(sdistBytes);
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).hasBinaryContent(wheelBytes);
    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name)).isOne();
  }

  @Test
  @DisplayName("two concurrent first uploads of a new package with different versions both succeed")
  void concurrentFirstUploadsOfANewPackageShareOnePackageRow() throws Exception {
    final var repo = this.pypiRepo(false);
    final var name = uniquePackageName();
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = this.holdFirstArchiveWrite(firstWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(
              () -> this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("a"), "a"));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(
              () -> this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("b"), "b"));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(failureOf(first)).isNull();
      assertThat(failureOf(second)).as("a first upload never fails on the package index").isNull();
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name)).isEqualTo(2);
  }
}
