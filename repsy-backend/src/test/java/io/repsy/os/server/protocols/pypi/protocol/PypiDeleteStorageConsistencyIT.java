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
import static org.mockito.Mockito.verify;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.server.protocols.pypi.ui.facades.PypiApiFacade;
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
import java.util.concurrent.Callable;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1277: deleting a PyPI release or package while the same package is being published leaves
 * storage and the database in agreement, whichever of the two gets the package first.
 *
 * <p>Both deletes take the package's row lock, as a publish does (RPS-1124), and remove the rows
 * and the archives inside that one transaction. Each race is driven deterministically: the
 * operation that goes first is held inside its transaction, at its storage call, by a latch. The
 * other operation is then started and the test waits until the database reports it blocked on a
 * lock (or, without the lock, finished) before it lets the first one go on. Nothing depends on a
 * sleep.
 *
 * <p>Runs without a test transaction, because a race needs both sides to commit. It deletes the
 * repos it commits.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PyPI deletes keep storage and the database in agreement with a publish (RPS-1277)")
class PypiDeleteStorageConsistencyIT extends AbstractIntegrationTest {

  private static final String REFUSE_DELETE_SUMMARY = "undeletable";
  private static final String REFUSE_DELETE_TRIGGER = "tr_pypi_release__it_refuse_delete";
  private static final String REFUSE_DELETE_FUNCTION = "fn_pypi_release__it_refuse_delete";

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private PypiStorageService pypiStorageService;

  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @Autowired private PypiApiFacade pypiApiFacade;
  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    this.jdbcTemplate.execute(
        "drop trigger if exists " + REFUSE_DELETE_TRIGGER + " on pypi_release");
    this.jdbcTemplate.execute("drop function if exists " + REFUSE_DELETE_FUNCTION + "()");
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
  }

  /**
   * Makes the database refuse to delete a release row that carries {@link #REFUSE_DELETE_SUMMARY}.
   */
  private void refuseToDeleteReleasesAtTheDatabase() {
    this.jdbcTemplate.execute(
        "create function "
            + REFUSE_DELETE_FUNCTION
            + "() returns trigger language plpgsql as $$ begin "
            + "if old.summary = '"
            + REFUSE_DELETE_SUMMARY
            + "' then raise exception 'delete refused by the test'; end if; "
            + "return old; end $$");
    this.jdbcTemplate.execute(
        "create trigger "
            + REFUSE_DELETE_TRIGGER
            + " before delete on pypi_release for each row execute function "
            + REFUSE_DELETE_FUNCTION
            + "()");
  }

  private RepoInfo pypiRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(uniqueRepoName("pypi-del"), RepoType.PYPI, false, null);
    this.createdRepoIds.add(repoInfo.getId());
    this.pypiStorageService.createRepo(repoInfo.getStorageKey());
    repoInfo.setAllowOverride(false);

    return repoInfo;
  }

  private static String uniquePackageName() {
    return "deleting-" + randomTag();
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

  private void upload(
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
  }

  private Path archive(final RepoInfo repo, final String packageName, final String filename) {
    final Repo entity = this.repoRepository.findByName(repo.getName()).orElseThrow();

    return storageDirOf(entity).resolve(packageName).resolve(filename);
  }

  private static Path digestOf(final Path archive) {
    return archive.resolveSibling(archive.getFileName() + ".sha256");
  }

  private int count(final String sql, final Object... arguments) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);

    return count == null ? 0 : count;
  }

  private int releaseCount(final RepoInfo repo, final String packageName) {
    return this.count(
        """
        select count(*) from pypi_release r
          join pypi_package p on p.id = r.package_id
        where p.repo_id = ? and p.normalized_name = ?
        """,
        repo.getId(),
        packageName);
  }

  private int releaseCount(final RepoInfo repo, final String packageName, final String version) {
    return this.count(
        """
        select count(*) from pypi_release r
          join pypi_package p on p.id = r.package_id
        where p.repo_id = ? and p.normalized_name = ? and r.version = ?
        """,
        repo.getId(),
        packageName,
        version);
  }

  private int packageCount(final RepoInfo repo, final String packageName) {
    return this.count(
        "select count(*) from pypi_package where repo_id = ? and normalized_name = ?",
        repo.getId(),
        packageName);
  }

  private String latestVersion(final RepoInfo repo, final String packageName) {
    return this.jdbcTemplate.queryForObject(
        "select latest_version from pypi_package where repo_id = ? and normalized_name = ?",
        String.class,
        repo.getId(),
        packageName);
  }

  private CountDownLatch holdNextDeleteRelease(final CountDownLatch reached) {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.pypiStorageService)
        .deleteRelease(any(), any(), any());

    return gate;
  }

  private CountDownLatch holdNextDeletePackage(final CountDownLatch reached) {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.pypiStorageService)
        .deletePackage(any(), any());

    return gate;
  }

  private CountDownLatch holdNextArchiveWrite(final CountDownLatch reached) throws Exception {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.pypiStorageService)
        .writePackageArchive(any(), any(), any(), any());

    return gate;
  }

  private Object passGate(
      final CountDownLatch reached, final CountDownLatch gate, final InvocationOnMock invocation)
      throws Throwable {
    reached.countDown();
    assertThat(gate.await(30, TimeUnit.SECONDS)).as("the test opened the gate").isTrue();

    return invocation.callRealMethod();
  }

  /**
   * Waits until a database session is blocked on a lock, or the given operation has finished (as it
   * would without the lock). Either way the race is set up: the operation that is being held has
   * nothing left to overtake.
   */
  private void awaitBlockedOnALockOrDone(final Future<?> operation) throws Exception {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

    while (!operation.isDone() && !this.someSessionWaitsForALock()) {
      assertThat(System.nanoTime()).as("the operation blocked or finished").isLessThan(deadline);
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private boolean someSessionWaitsForALock() {
    return this.count(
            "select count(*) from pg_stat_activity"
                + " where datname = current_database() and wait_event_type = 'Lock'")
        > 0;
  }

  private static Throwable failureOf(final Future<?> future) throws Exception {
    try {
      future.get(30, TimeUnit.SECONDS);
    } catch (final ExecutionException e) {
      return e.getCause();
    }

    return null;
  }

  /**
   * Runs {@code first} up to its held storage call, starts {@code second}, waits until the second
   * one has met the lock, then lets the first one go on. Both must succeed.
   */
  private void race(
      final CountDownLatch firstReached,
      final CountDownLatch gate,
      final Callable<?> first,
      final Callable<?> second)
      throws Exception {

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var firstResult = executor.submit(first);
      assertThat(firstReached.await(30, TimeUnit.SECONDS)).isTrue();

      final var secondResult = executor.submit(second);
      this.awaitBlockedOnALockOrDone(secondResult);
      gate.countDown();

      assertThat(failureOf(firstResult)).as("the operation that went first").isNull();
      assertThat(failureOf(secondResult)).as("the operation that went second").isNull();
    } finally {
      gate.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("a publish that waits for a release delete recreates the release, and only it")
  void publishAfterDeleteReleaseRecreatesTheRelease() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("old"), "old");
    this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("keep"), "keep");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextDeleteRelease(reached);
    final var published = content("republished");

    this.race(
        reached,
        gate,
        () -> this.pypiApiFacade.deleteRelease(repo, name, "1.0.0"),
        () -> {
          this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), published, "republished");
          return null;
        });

    // The delete removed the archive it found, and did not reach forward to the published one.
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheel(name, "1.0.0")))).doesNotExist();
    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).hasBinaryContent(published);
    assertThat(digestOf(this.archive(repo, name, sdist(name, "1.0.0"))))
        .hasContent(sha256Hex(published));
    assertThat(this.releaseCount(repo, name, "1.0.0")).isOne();
    assertThat(this.releaseCount(repo, name, "2.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "2.0.0"))).exists();
  }

  @Test
  @DisplayName("a release delete that waits for a publish removes what the publish stored")
  void deleteReleaseAfterPublishRemovesThePublishedFile() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("old"), "old");
    this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("keep"), "keep");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextArchiveWrite(reached);

    this.race(
        reached,
        gate,
        () -> {
          this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), content("new"), "new");
          return null;
        },
        () -> this.pypiApiFacade.deleteRelease(repo, name, "1.0.0"));

    assertThat(this.releaseCount(repo, name, "1.0.0")).isZero();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, sdist(name, "1.0.0")))).doesNotExist();
    assertThat(this.releaseCount(repo, name, "2.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "2.0.0"))).exists();
    assertThat(this.latestVersion(repo, name)).isEqualTo("2.0.0");
  }

  @Test
  @DisplayName("a publish that waits for the delete of the last release recreates the package")
  void publishAfterDeleteOfTheLastReleaseRecreatesThePackage() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("old"), "old");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextDeletePackage(reached);
    final var published = content("republished");

    this.race(
        reached,
        gate,
        () -> this.pypiApiFacade.deleteRelease(repo, name, "1.0.0"),
        () -> {
          this.upload(repo, name, "1.0.0", sdist(name, "1.0.0"), published, "republished");
          return null;
        });

    // The delete's removal of the package directory must not take the published file with it.
    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name, "1.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(this.archive(repo, name, sdist(name, "1.0.0"))).hasBinaryContent(published);
    assertThat(digestOf(this.archive(repo, name, sdist(name, "1.0.0"))))
        .hasContent(sha256Hex(published));
  }

  @Test
  @DisplayName("a publish that waits for a package delete recreates the package, and only it")
  void publishAfterDeletePackageRecreatesThePackage() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("old"), "old");
    this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("old2"), "old2");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextDeletePackage(reached);
    final var published = content("republished");

    this.race(
        reached,
        gate,
        () -> this.pypiApiFacade.deletePackage(repo, name),
        () -> {
          this.upload(repo, name, "3.0.0", wheel(name, "3.0.0"), published, "republished");
          return null;
        });

    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name, "3.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "3.0.0"))).hasBinaryContent(published);
    assertThat(digestOf(this.archive(repo, name, wheel(name, "3.0.0"))))
        .hasContent(sha256Hex(published));
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(this.archive(repo, name, wheel(name, "2.0.0"))).doesNotExist();
  }

  @Test
  @DisplayName("a package delete that waits for a publish removes what the publish stored")
  void deletePackageAfterPublishRemovesThePublishedRelease() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("old"), "old");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextArchiveWrite(reached);

    this.race(
        reached,
        gate,
        () -> {
          this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("new"), "new");
          return null;
        },
        () -> this.pypiApiFacade.deletePackage(repo, name));

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.releaseCount(repo, name)).isZero();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).doesNotExist();
    assertThat(this.archive(repo, name, wheel(name, "2.0.0"))).doesNotExist();
    assertThat(digestOf(this.archive(repo, name, wheel(name, "2.0.0")))).doesNotExist();
  }

  @Test
  @DisplayName("a delete of a package that does not exist yet is not found, and touches no file")
  void deleteOfAPackageThatIsNotThereIsNotFound() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();

    assertThatThrownBy(() -> this.pypiApiFacade.deletePackage(repo, name))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("packageNotFound");
    assertThatThrownBy(() -> this.pypiApiFacade.deleteRelease(repo, name, "1.0.0"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("packageNotFound");

    verify(this.pypiStorageService, never()).deletePackage(any(), any());
    verify(this.pypiStorageService, never()).deleteRelease(any(), any(), any());
  }

  @Test
  @DisplayName("rows the database refuses to delete keep the archives of the release")
  void refusedReleaseDeleteKeepsTheFiles() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    final var bytes = content("kept");
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), bytes, REFUSE_DELETE_SUMMARY);
    this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("other"), "other");
    this.refuseToDeleteReleasesAtTheDatabase();

    assertThatThrownBy(() -> this.pypiApiFacade.deleteRelease(repo, name, "1.0.0"))
        .isInstanceOf(Exception.class);

    assertThat(this.releaseCount(repo, name, "1.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).hasBinaryContent(bytes);
    verify(this.pypiStorageService, never()).deleteRelease(any(), any(), any());
  }

  @Test
  @DisplayName("rows the database refuses to delete keep the archives of the package")
  void refusedPackageDeleteKeepsTheFiles() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    final var bytes = content("kept");
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), bytes, REFUSE_DELETE_SUMMARY);
    this.refuseToDeleteReleasesAtTheDatabase();

    assertThatThrownBy(() -> this.pypiApiFacade.deletePackage(repo, name))
        .isInstanceOf(Exception.class);

    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name, "1.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).hasBinaryContent(bytes);
    verify(this.pypiStorageService, never()).deletePackage(any(), any());
  }

  @Test
  @DisplayName("archives that cannot be removed keep the rows of the release")
  void failedReleaseArchiveRemovalKeepsTheRows() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("other"), "other");
    this.upload(repo, name, "2.0.0", wheel(name, "2.0.0"), content("kept"), "kept");
    doAnswer(
            invocation -> {
              throw new IllegalStateException("storage went away");
            })
        .when(this.pypiStorageService)
        .deleteRelease(any(), any(), any());

    // 2.0.0 is the latest release, so the delete moves the package to 1.0.0 before it fails.
    assertThatThrownBy(() -> this.pypiApiFacade.deleteRelease(repo, name, "2.0.0"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(this.releaseCount(repo, name, "2.0.0")).isOne();
    assertThat(this.latestVersion(repo, name)).isEqualTo("2.0.0");
    assertThat(this.archive(repo, name, wheel(name, "2.0.0"))).exists();
  }

  @Test
  @DisplayName("archives that cannot be removed keep the rows of the package")
  void failedPackageArchiveRemovalKeepsTheRows() throws Exception {
    final var repo = this.pypiRepo();
    final var name = uniquePackageName();
    this.upload(repo, name, "1.0.0", wheel(name, "1.0.0"), content("kept"), "kept");
    doAnswer(
            invocation -> {
              throw new IllegalStateException("storage went away");
            })
        .when(this.pypiStorageService)
        .deletePackage(any(), any());

    assertThatThrownBy(() -> this.pypiApiFacade.deletePackage(repo, name))
        .isInstanceOf(IllegalStateException.class);

    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(this.releaseCount(repo, name, "1.0.0")).isOne();
    assertThat(this.archive(repo, name, wheel(name, "1.0.0"))).exists();
  }
}
