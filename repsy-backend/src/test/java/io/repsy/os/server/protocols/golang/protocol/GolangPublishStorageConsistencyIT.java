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
package io.repsy.os.server.protocols.golang.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.golang.shared.storage.services.GolangStorageService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.golang.shared.utils.GoModuleHashCalculator;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1124: a Go module upload leaves storage and the database in agreement, whichever half of it
 * fails.
 *
 * <p>The version row is written first and the {@code .mod}, {@code .zip} and {@code .info} files
 * second, inside one transaction. So an upload whose row cannot be written never touches storage,
 * and an upload whose files cannot be written leaves no row and no partly written file behind (Go
 * versions are immutable, so a version is always new).
 *
 * <p>Runs without a test transaction, unlike the other Go protocol ITs: a failed row write aborts a
 * PostgreSQL transaction, so inside a test transaction the state afterwards could not be read, and
 * a race between two uploads needs both to commit. It deletes the repos and users it commits.
 *
 * <p>{@link UsageUpdateService} is mocked: the mock records whether an upload reported any usage.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Go module upload keeps storage and the database in agreement (RPS-1124)")
class GolangPublishStorageConsistencyIT extends AbstractIntegrationTest {

  /**
   * A {@code go} directive the database refuses through {@link #rejectGoVersionAtTheDatabase()}.
   */
  private static final String REJECTED_GO_VERSION = "9.99";

  private static final String REJECT_GO_VERSION_CONSTRAINT = "ch_go_module_version__it_rejected";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private GolangStorageService golangStorageService;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the modules with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.jdbcTemplate.execute(
        "alter table go_module_version drop constraint if exists " + REJECT_GO_VERSION_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row whose go directive is {@link #REJECTED_GO_VERSION}: a
   * check violation, which is a {@code DataIntegrityViolationException} but not the unique index on
   * (module, version).
   */
  private void rejectGoVersionAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table go_module_version add constraint "
            + REJECT_GO_VERSION_CONSTRAINT
            + " check (go_version is distinct from '"
            + REJECTED_GO_VERSION
            + "')");
  }

  private Repo goRepo() {
    final var name = uniqueRepoName("go-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.GOLANG, false, null);
    this.createdRepoIds.add(created.getId());
    this.golangStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("go-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniqueModulePath() {
    return "example.com/consistency" + randomTag();
  }

  /** A module zip whose {@code marker.go} carries {@code marker}, so two zips differ in bytes. */
  private static byte[] moduleZip(
      final String modulePath, final String version, final String goVersion, final String marker) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = modulePath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(
          ("module " + modulePath + "\n\ngo " + goVersion + "\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "marker.go"));
      zip.write(("package marker // " + marker + "\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static byte[] moduleZip(final String modulePath, final String version) {
    return moduleZip(modulePath, version, "1.21", "plain");
  }

  private MockHttpServletResponse upload(
      final Repo repo,
      final String modulePath,
      final String version,
      final byte[] zip,
      final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/" + modulePath + "/@v/" + version, repo.getName())
                .header(AUTHORIZATION, token)
                .contentType("application/zip")
                .content(zip)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private static Path versionFile(
      final Repo repo, final String modulePath, final String version, final String extension) {
    return storageDirOf(repo).resolve(modulePath).resolve("@v").resolve(version + extension);
  }

  /** Every file the repo holds in storage, relative to its directory. */
  private static List<String> storedFiles(final Repo repo) throws IOException {
    final var dir = storageDirOf(repo);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (final var files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .map(file -> dir.relativize(file).toString())
          .toList();
    }
  }

  private int storedVersionCount(final Repo repo, final String modulePath) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from go_module_version v
              join go_module m on m.id = v.module_id
            where m.repo_id = ? and m.module_path = ?
            """,
            Integer.class,
            repo.getId(),
            modulePath);

    return count == null ? 0 : count;
  }

  private int storedModuleCount(final Repo repo, final String modulePath) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from go_module where repo_id = ? and module_path = ?",
            Integer.class,
            repo.getId(),
            modulePath);

    return count == null ? 0 : count;
  }

  private String storedZipHash(final Repo repo, final String modulePath, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select v.zip_hash from go_module_version v
          join go_module m on m.id = v.module_id
        where m.repo_id = ? and m.module_path = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        modulePath,
        version);
  }

  private static String zipHashOf(final byte[] zip) throws IOException {
    return GoModuleHashCalculator.hashZip(new java.io.ByteArrayInputStream(zip));
  }

  private static boolean isWriteOf(final Object[] arguments, final String extension) {
    return arguments[0] instanceof final StoragePath path
        && path.getRelativePath().getPath().endsWith(extension);
  }

  /** Writes the file of the given extension like the real service, then fails. */
  private void failAfterWriting(final String extension) {
    doAnswer(
            invocation -> {
              final var usages = invocation.callRealMethod();
              if (isWriteOf(invocation.getArguments(), extension)) {
                throw new IllegalStateException("storage went away after the write");
              }
              return usages;
            })
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());
  }

  @Test
  @DisplayName("an upload records the row and the three files together")
  void anUploadStoresRowAndFiles() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var zip = moduleZip(modulePath, "v1.0.0");

    final var response = this.upload(repo, modulePath, "v1.0.0", zip, this.adminToken());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".zip")).hasBinaryContent(zip);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".mod")).isRegularFile();
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".info")).isRegularFile();
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(1);
  }

  @Test
  @DisplayName("a row the database rejects leaves no file behind")
  void rejectedRowLeavesNoFile() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    this.rejectGoVersionAtTheDatabase();

    final var response =
        this.upload(
            repo,
            modulePath,
            "v1.0.0",
            moduleZip(modulePath, "v1.0.0", REJECTED_GO_VERSION, "plain"),
            this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error, not a 409")
        .isEqualTo(500);
    assertThat(this.storedVersionCount(repo, modulePath)).isZero();
    assertThat(this.storedModuleCount(repo, modulePath)).isZero();
    assertThat(storedFiles(repo)).isEmpty();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a file that cannot be stored leaves neither its row nor the files written before")
  void failedFileWriteLeavesNoRowAndNoFiles() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    // The .mod is written before the .zip, so it is already in storage when the .zip fails.
    this.failAfterWriting(".zip");

    final var response =
        this.upload(repo, modulePath, "v1.0.0", moduleZip(modulePath, "v1.0.0"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, modulePath)).isZero();
    assertThat(this.storedModuleCount(repo, modulePath)).isZero();
    assertThat(storedFiles(repo)).isEmpty();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed upload can be retried: the version is not left claimed by a dead row")
  void failedUploadCanBeRetried() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    final var zip = moduleZip(modulePath, "v1.0.0");
    this.failAfterWriting(".info");

    assertThat(this.upload(repo, modulePath, "v1.0.0", zip, token).getStatus()).isEqualTo(500);
    assertThat(storedFiles(repo)).isEmpty();

    doAnswer(invocation -> invocation.callRealMethod())
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());

    assertThat(this.upload(repo, modulePath, "v1.0.0", zip, token).getStatus()).isEqualTo(200);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".zip")).hasBinaryContent(zip);
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(1);
  }

  @Test
  @DisplayName("a failed upload leaves the files of the other versions of the module alone")
  void failedUploadKeepsOtherVersions() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    final var first = moduleZip(modulePath, "v1.0.0");
    assertThat(this.upload(repo, modulePath, "v1.0.0", first, token).getStatus()).isEqualTo(200);
    this.failAfterWriting(".info");

    final var response =
        this.upload(repo, modulePath, "v1.0.1", moduleZip(modulePath, "v1.0.1"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".zip")).hasBinaryContent(first);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".info")).isRegularFile();
    assertThat(versionFile(repo, modulePath, "v1.0.1", ".zip")).doesNotExist();
    assertThat(versionFile(repo, modulePath, "v1.0.1", ".mod")).doesNotExist();
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(1);
  }

  @Test
  @DisplayName("an upload of a version that exists is a 409 and leaves its files untouched")
  void duplicateVersionKeepsTheFiles() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    final var original = moduleZip(modulePath, "v1.0.0", "1.21", "original");
    assertThat(this.upload(repo, modulePath, "v1.0.0", original, token).getStatus()).isEqualTo(200);

    final var response =
        this.upload(
            repo, modulePath, "v1.0.0", moduleZip(modulePath, "v1.0.0", "1.21", "second"), token);

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(versionFile(repo, modulePath, "v1.0.0", ".zip")).hasBinaryContent(original);
    assertThat(this.storedZipHash(repo, modulePath, "v1.0.0")).isEqualTo(zipHashOf(original));
  }

  @Test
  @DisplayName("an upload that loses the race for a version does not replace the winner's files")
  void loserOfTheRaceKeepsTheWinnersFiles() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    // The module exists before the race, so both uploads race for the version row, not the module.
    assertThat(
            this.upload(repo, modulePath, "v1.0.0", moduleZip(modulePath, "v1.0.0"), token)
                .getStatus())
        .isEqualTo(200);
    final var winnerZip = moduleZip(modulePath, "v1.0.1", "1.21", "winner");
    final var loserZip = moduleZip(modulePath, "v1.0.1", "1.21", "loser");

    // Holds the winner inside its first storage write, which is inside its transaction, so the
    // loser starts while the winner's row is uncommitted and the version is not yet visible to it.
    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(invocation -> invocation.callRealMethod())
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.upload(repo, modulePath, "v1.0.1", winnerZip, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(() -> this.upload(repo, modulePath, "v1.0.1", loserZip, token));
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

    assertThat(versionFile(repo, modulePath, "v1.0.1", ".zip")).hasBinaryContent(winnerZip);
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(2);
    assertThat(this.storedZipHash(repo, modulePath, "v1.0.1"))
        .as("the row describes the winner's zip, the one that is stored")
        .isEqualTo(zipHashOf(winnerZip));
  }

  @Test
  @DisplayName("two concurrent first uploads of a new module with different versions both succeed")
  void concurrentFirstUploadsOfANewModuleShareOneModuleRow() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();

    // Holds the first upload inside its file write, so its module row is uncommitted when the
    // second reaches its own insert of that module and has to wait for it.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(invocation -> invocation.callRealMethod())
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(
              () ->
                  this.upload(repo, modulePath, "v1.0.0", moduleZip(modulePath, "v1.0.0"), token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();
      final var second =
          executor.submit(
              () ->
                  this.upload(repo, modulePath, "v2.0.0", moduleZip(modulePath, "v2.0.0"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      final var r = second.get(30, TimeUnit.SECONDS);
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
    assertThat(this.storedModuleCount(repo, modulePath)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "two concurrent first uploads of a new module with the same version: the loser answers 409")
  void concurrentFirstUploadsOfANewModuleWithTheSameVersionOneLoses() throws Exception {
    final var repo = this.goRepo();
    final var modulePath = uniqueModulePath();
    final var token = this.adminToken();
    final var winnerZip = moduleZip(modulePath, "v1.0.0", "1.21", "winner");
    final var loserZip = moduleZip(modulePath, "v1.0.0", "1.21", "loser");

    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(invocation -> invocation.callRealMethod())
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.upload(repo, modulePath, "v1.0.0", winnerZip, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();
      final var loser =
          executor.submit(() -> this.upload(repo, modulePath, "v1.0.0", loserZip, token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(409);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(versionFile(repo, modulePath, "v1.0.0", ".zip")).hasBinaryContent(winnerZip);
    assertThat(this.storedModuleCount(repo, modulePath)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, modulePath)).isEqualTo(1);
    assertThat(this.storedZipHash(repo, modulePath, "v1.0.0")).isEqualTo(zipHashOf(winnerZip));
  }
}
