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
package io.repsy.os.server.protocols.cargo.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.cargo.shared.crate.storage.CargoStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1124: a {@code cargo publish} leaves storage and the database in agreement, whichever half of
 * it fails.
 *
 * <p>The version row is written first and the {@code .crate} and its index line second, inside one
 * transaction. So a push whose row cannot be written never touches storage, and a push whose files
 * cannot be written leaves no row (and no crate file) behind.
 *
 * <p>Runs without a test transaction, unlike {@link CargoPublishHardeningIT}: a failed row write
 * aborts a PostgreSQL transaction, so inside a test transaction the state afterwards could not be
 * read, and a race between two pushes needs both to commit. It deletes the repos and users it
 * commits, and publishes with no authors, so it leaves no row in the shared author table.
 *
 * <p>{@link UsageUpdateService} is mocked: the mock records whether a push reported any usage.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Cargo publish keeps storage and the database in agreement (RPS-1124)")
class CargoPublishStorageConsistencyIT extends AbstractIntegrationTest {

  private static final String PUBLISH_PATH = "/{repo}/api/v1/crates/new";

  /** A license the database refuses through {@link #rejectLicenseAtTheDatabase()}. */
  private static final String REJECTED_LICENSE = "reject-me";

  private static final String REJECT_LICENSE_CONSTRAINT = "ch_cargo_crate_meta__it_rejected";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private CargoStorageService cargoStorageService;

  /** The strategy under the storage service, spied to fail a single write or append. */
  @MockitoSpyBean(name = "osStorageStrategyCargo")
  private StorageStrategy cargoStrategy;

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the crates with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.jdbcTemplate.execute(
        "alter table cargo_crate_meta drop constraint if exists " + REJECT_LICENSE_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row with {@link #REJECTED_LICENSE}: a check violation,
   * which is a {@code DataIntegrityViolationException} but not the unique index on (crate,
   * version).
   */
  private void rejectLicenseAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table cargo_crate_meta add constraint "
            + REJECT_LICENSE_CONSTRAINT
            + " check (license is distinct from '"
            + REJECTED_LICENSE
            + "')");
  }

  private Repo cargoRepo() {
    final var name = uniqueRepoName("cargo-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.CARGO, false, null);
    this.createdRepoIds.add(created.getId());
    // No scan, no scan thread through the strategy spy the tests stub (RPS-1336, RPS-1341).
    this.disableSecurityScan(created.getId());
    this.cargoStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("cargo-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniqueCrateName() {
    return "cons" + randomTag();
  }

  /** A crate that differs from the others of the same name and version by {@code marker}. */
  private static byte[] crateArchive(final String name, final String version, final String marker) {
    final var manifest =
        "[package]\nname = \"%s\"\n\n[lib]\n# %s\n"
            .formatted(name, marker)
            .getBytes(StandardCharsets.UTF_8);
    final var bytes = new ByteArrayOutputStream();

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      final var entry = new TarArchiveEntry(name + "-" + version + "/Cargo.toml");

      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  private static byte[] publishBody(
      final String name, final String version, final String license, final byte[] crate) {
    final var metadata =
        MAPPER
            .writeValueAsString(
                Map.of(
                    "name",
                    name,
                    "vers",
                    version,
                    "deps",
                    List.of(),
                    "features",
                    Map.of(),
                    "authors",
                    List.of(),
                    "description",
                    "storage consistency fixture",
                    "license",
                    license))
            .getBytes(StandardCharsets.UTF_8);
    final var out = new ByteArrayOutputStream();

    out.writeBytes(u32le(metadata.length));
    out.writeBytes(metadata);
    out.writeBytes(u32le(crate.length));
    out.writeBytes(crate);

    return out.toByteArray();
  }

  private static byte[] u32le(final int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private MockHttpServletResponse push(
      final Repo repo,
      final String name,
      final String version,
      final byte[] crate,
      final String token)
      throws Exception {
    return this.push(repo, name, version, "MIT", crate, token);
  }

  private MockHttpServletResponse push(
      final Repo repo,
      final String name,
      final String version,
      final String license,
      final byte[] crate,
      final String token)
      throws Exception {
    final var request =
        put(PUBLISH_PATH, repo.getName())
            .header(AUTHORIZATION, token)
            .content(publishBody(name, version, license, crate));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static Path crateFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo)
        .resolve("crates")
        .resolve(name)
        .resolve(name + "-" + version + ".crate");
  }

  /** The sparse-index file of a crate whose name has four or more characters. */
  private static Path indexFile(final Repo repo, final String name) {
    return storageDirOf(repo)
        .resolve("index")
        .resolve(name.substring(0, 2))
        .resolve(name.substring(2, 4))
        .resolve(name);
  }

  private static List<String> indexLines(final Repo repo, final String name) throws IOException {
    final var file = indexFile(repo, name);

    return Files.exists(file) ? Files.readAllLines(file) : List.of();
  }

  private int storedVersionCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from cargo_crate_index i
              join cargo_crate c on c.id = i.crate_id
            where c.repo_id = ? and c.name = ?
            """,
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private int storedCrateCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from cargo_crate where repo_id = ? and name = ?",
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private String storedChecksum(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select i.cksum from cargo_crate_index i
          join cargo_crate c on c.id = i.crate_id
        where c.repo_id = ? and c.name = ? and i.vers = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  /** Writes the crate like the real strategy, then fails, as a storage that dies mid-push would. */
  private void failAfterWritingCrate() throws IOException {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IOException("storage went away after the write");
            })
        .when(this.cargoStrategy)
        .write(any(), any(), any());
  }

  @Test
  @DisplayName("a push records the row, the crate file and one index line together")
  void aPushStoresRowAndFiles() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var crate = crateArchive(name, "1.0.0", "first");

    final var response = this.push(repo, name, "1.0.0", crate, this.adminToken());

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(crateFile(repo, name, "1.0.0")).hasBinaryContent(crate);
    assertThat(indexLines(repo, name)).hasSize(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedChecksum(repo, name, "1.0.0")).isEqualTo(sha256Hex(crate));
  }

  @Test
  @DisplayName("a row the database rejects leaves no crate file and no index line behind")
  void rejectedRowLeavesNoFiles() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    this.rejectLicenseAtTheDatabase();

    final var response =
        this.push(
            repo,
            name,
            "1.0.0",
            REJECTED_LICENSE,
            crateArchive(name, "1.0.0", "rejected"),
            this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error")
        .isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedCrateCount(repo, name)).isZero();
    assertThat(crateFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(indexLines(repo, name)).isEmpty();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a row the database rejects leaves the files of the versions already published")
  void rejectedRowKeepsThePublishedVersions() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();
    final var first = crateArchive(name, "1.0.0", "first");
    assertThat(this.push(repo, name, "1.0.0", first, token).getStatus()).isEqualTo(200);
    this.rejectLicenseAtTheDatabase();

    final var response =
        this.push(
            repo, name, "1.0.1", REJECTED_LICENSE, crateArchive(name, "1.0.1", "rejected"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(crateFile(repo, name, "1.0.0")).hasBinaryContent(first);
    assertThat(crateFile(repo, name, "1.0.1")).doesNotExist();
    assertThat(indexLines(repo, name)).hasSize(1);
  }

  @Test
  @DisplayName("publishing an existing version again leaves its crate file and index line alone")
  void republishingAnExistingVersionChangesNothing() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();
    final var original = crateArchive(name, "1.0.0", "original");
    assertThat(this.push(repo, name, "1.0.0", original, token).getStatus()).isEqualTo(200);
    final var originalIndex = indexLines(repo, name);

    final var response =
        this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "replacement"), token);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(crateFile(repo, name, "1.0.0")).hasBinaryContent(original);
    assertThat(indexLines(repo, name)).isEqualTo(originalIndex);
    assertThat(this.storedChecksum(repo, name, "1.0.0")).isEqualTo(sha256Hex(original));
  }

  @Test
  @DisplayName("a push that loses the race for a version does not replace the winner's files")
  void loserOfTheRaceKeepsTheWinnersFiles() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();
    // The crate exists before the race, so both pushes race for the version row, not the crate.
    assertThat(
            this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "seed"), token).getStatus())
        .isEqualTo(200);
    final var winnerCrate = crateArchive(name, "1.0.1", "winner");
    final var loserCrate = crateArchive(name, "1.0.1", "loser");

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
        .when(this.cargoStorageService)
        .writeCrateAndIndex(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, name, "1.0.1", winnerCrate, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser = executor.submit(() -> this.push(repo, name, "1.0.1", loserCrate, token));
      // Long enough for the loser to reach its row write and wait on the winner's row. If it is
      // slower, it is turned away by the existing-version check instead: the assertions hold both
      // ways.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(400);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    // Once for the winner, and once for the push that seeded the crate.
    verify(this.cargoStorageService, times(2))
        .writeCrateAndIndex(any(), any(), any(), any(), any(), any());
    assertThat(crateFile(repo, name, "1.0.1")).hasBinaryContent(winnerCrate);
    assertThat(indexLines(repo, name)).as("one index line per version").hasSize(2);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(this.storedChecksum(repo, name, "1.0.1"))
        .as("the row describes the winner's crate, the one that is stored")
        .isEqualTo(sha256Hex(winnerCrate));
  }

  @Test
  @DisplayName(
      "two concurrent first pushes of a new crate name with different versions both succeed")
  void concurrentFirstPushesOfANewCrateShareOneCrateRow() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();

    // Holds the first push inside the storage write, so its crate row is uncommitted when the
    // second push reaches its own insert of that crate and has to wait for it.
    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              firstWriting.countDown();
              releaseFirst.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.cargoStorageService)
        .writeCrateAndIndex(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(
              () -> this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "a"), token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(
              () -> this.push(repo, name, "2.0.0", crateArchive(name, "2.0.0", "b"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedCrateCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(indexLines(repo, name)).hasSize(2);
  }

  @Test
  @DisplayName(
      "two concurrent first pushes of a new crate name with the same version: the loser is"
          + " turned away as a duplicate, not a server error")
  void concurrentFirstPushesOfANewCrateWithTheSameVersionOneLoses() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();
    final var winnerCrate = crateArchive(name, "1.0.0", "winner");

    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              winnerWriting.countDown();
              releaseWinner.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.cargoStorageService)
        .writeCrateAndIndex(any(), any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, name, "1.0.0", winnerCrate, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(
              () -> this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "loser"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus())
          .as("a first push of a new crate name never answers 500 on this race")
          .isEqualTo(400);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedCrateCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(crateFile(repo, name, "1.0.0")).hasBinaryContent(winnerCrate);
    assertThat(indexLines(repo, name)).hasSize(1);
  }

  @Test
  @DisplayName("a crate write that fails after the file exists rolls back the row and removes it")
  void failedCrateWriteRollsBackANewVersion() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    this.failAfterWritingCrate();

    final var response =
        this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "first"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedCrateCount(repo, name)).isZero();
    assertThat(crateFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(indexLines(repo, name)).isEmpty();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("an index append that fails removes the crate file and rolls back the row")
  void failedIndexAppendRollsBackANewVersion() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    doAnswer(
            invocation -> {
              throw new IOException("index append failed");
            })
        .when(this.cargoStrategy)
        .append(any(), any(), any());

    final var response =
        this.push(repo, name, "1.0.0", crateArchive(name, "1.0.0", "first"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(this.storedCrateCount(repo, name)).isZero();
    assertThat(crateFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(indexLines(repo, name)).isEmpty();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed write of a later version leaves the earlier version intact")
  void failedWriteKeepsThePublishedVersions() throws Exception {
    final var repo = this.cargoRepo();
    final var name = uniqueCrateName();
    final var token = this.adminToken();
    final var first = crateArchive(name, "1.0.0", "first");
    assertThat(this.push(repo, name, "1.0.0", first, token).getStatus()).isEqualTo(200);
    this.failAfterWritingCrate();

    final var response =
        this.push(repo, name, "1.0.1", crateArchive(name, "1.0.1", "second"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(crateFile(repo, name, "1.0.0")).hasBinaryContent(first);
    assertThat(crateFile(repo, name, "1.0.1")).doesNotExist();
    assertThat(indexLines(repo, name)).hasSize(1);
  }

  private static String sha256Hex(final byte[] bytes) throws IOException {
    try (final var upload = SpooledUpload.spool(new ByteArrayInputStream(bytes))) {
      return upload.sha256Hex();
    }
  }
}
