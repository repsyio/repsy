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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1124: an npm publish leaves storage and the database in agreement, whichever half of it
 * fails.
 *
 * <p>The version rows are written first and the tarball and the package metadata second, inside one
 * transaction that holds the package row locked. So a publish whose rows cannot be written never
 * touches storage, a publish whose files cannot be written leaves no rows (and, for a new version,
 * no files) behind, and two publishes of one package run one after another: a loser never replaces
 * the winner's tarball or the package metadata the winner wrote.
 *
 * <p>Runs without a test transaction, unlike {@link NpmPublishProtocolIT}: a failed row write
 * aborts a PostgreSQL transaction, so inside a test transaction the state afterwards could not be
 * read, and a race between two publishes needs both to commit. It deletes the repos and users it
 * commits.
 *
 * <p>{@link UsageUpdateService} is mocked: the mock records whether a publish reported any usage.
 *
 * <p>Replacing a version whose file write fails part-way is not covered beyond keeping its rows:
 * the file system strategy truncates the file in place, so its old bytes cannot be restored.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("npm publish keeps storage and the database in agreement (RPS-1124)")
class NpmPublishStorageConsistencyIT extends AbstractIntegrationTest {

  /** A description the database refuses through {@link #rejectDescriptionAtTheDatabase()}. */
  private static final String REJECTED_DESCRIPTION = "reject-me";

  private static final String REJECT_DESCRIPTION_CONSTRAINT = "ch_npm_package_version__it_rejected";
  private static final String HOST = "http://localhost:9090";
  private static final String PUBLISH_PATH = "/{repo}/{packagePath}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private NpmStorageService npmStorageService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
    this.jdbcTemplate.execute(
        "alter table npm_package_version drop constraint if exists "
            + REJECT_DESCRIPTION_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row with {@link #REJECTED_DESCRIPTION}: a check violation,
   * which is a {@code DataIntegrityViolationException} but not the unique index on (package,
   * version).
   */
  private void rejectDescriptionAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table npm_package_version add constraint "
            + REJECT_DESCRIPTION_CONSTRAINT
            + " check (description is distinct from '"
            + REJECTED_DESCRIPTION
            + "')");
  }

  private Repo npmRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("npm-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.NPM, false, null);
    this.createdRepoIds.add(created.getId());
    this.npmStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("npm-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniquePackageName() {
    return "consistency-" + randomTag();
  }

  private byte[] publishBody(
      final Repo repo,
      final String name,
      final String version,
      final String description,
      final byte[] tarball) {

    final var dist = new LinkedHashMap<String, Object>();
    dist.put(
        "tarball",
        HOST + "/" + repo.getName() + "/" + name + "/-/" + name + "-" + version + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", name);
    versionMetadata.put("version", version);
    versionMetadata.put("description", description);
    versionMetadata.put("dist", dist);

    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", name);
    body.put("name", name);
    body.put("dist-tags", Map.of("latest", version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            name + "-" + version + ".tgz",
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarball),
                "length",
                tarball.length)));

    return this.objectMapper.writeValueAsBytes(body);
  }

  private MockHttpServletResponse publish(
      final Repo repo,
      final String name,
      final String version,
      final String description,
      final byte[] tarball,
      final String token)
      throws Exception {

    final var request =
        put(PUBLISH_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.publishBody(repo, name, version, description, tarball));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static byte[] tarballOf(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static Path tarballFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve(name).resolve(name + "-" + version + ".tgz");
  }

  private static Path metadataFile(final Repo repo, final String name) {
    return storageDirOf(repo).resolve(name).resolve("package.json");
  }

  /** The version names the stored package metadata lists. */
  private List<String> metadataVersions(final Repo repo, final String name) throws IOException {
    final Map<String, Object> metadata =
        this.objectMapper.readValue(
            Files.readAllBytes(metadataFile(repo, name)), new TypeReference<>() {});

    @SuppressWarnings("unchecked")
    final var versions = (Map<String, Object>) metadata.get("versions");

    return List.copyOf(versions.keySet());
  }

  private int storedPackageCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            "select count(*) from npm_package where repo_id = ? and name = ?",
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private int storedVersionCount(final Repo repo, final String name) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from npm_package_version v
              join npm_package p on p.id = v.package_id
            where p.repo_id = ? and p.name = ?
            """,
            Integer.class,
            repo.getId(),
            name);

    return count == null ? 0 : count;
  }

  private String storedDescription(final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select v.description from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  /**
   * Writes the files like the real service, then fails, as a storage that dies mid-publish would.
   */
  private void failAfterWritingFiles() throws Exception {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a publish records the rows, the tarball and the package metadata together")
  void aPublishStoresRowsAndFiles() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var tarball = tarballOf("first");

    final var response = this.publish(repo, name, "1.0.0", "first", tarball, this.adminToken());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(tarball);
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.0.0");
    assertThat(this.storedPackageCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
  }

  @Test
  @DisplayName("rows the database rejects leave no files behind for a new package")
  void rejectedRowsLeaveNoFilesForANewPackage() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    this.rejectDescriptionAtTheDatabase();

    final var response =
        this.publish(
            repo, name, "1.0.0", REJECTED_DESCRIPTION, tarballOf("rejected"), this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error, not a 403")
        .isEqualTo(500);
    assertThat(this.storedPackageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(metadataFile(repo, name)).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("rows the database rejects leave the stored package as it was for a new version")
  void rejectedRowsLeaveTheStoredPackageAsItWas() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    assertThat(this.publish(repo, name, "1.0.0", "first", tarballOf("first"), token).getStatus())
        .isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.rejectDescriptionAtTheDatabase();

    final var response =
        this.publish(repo, name, "1.1.0", REJECTED_DESCRIPTION, tarballOf("rejected"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
  }

  @Test
  @DisplayName("rows the database rejects leave the files and rows of the version it replaces")
  void rejectedOverrideKeepsTheExistingVersion() throws Exception {
    final var repo = this.npmRepo(true);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    final var original = tarballOf("original");
    assertThat(this.publish(repo, name, "1.0.0", "original", original, token).getStatus())
        .isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.rejectDescriptionAtTheDatabase();

    final var response =
        this.publish(repo, name, "1.0.0", REJECTED_DESCRIPTION, tarballOf("replacement"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(original);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedDescription(repo, name, "1.0.0")).isEqualTo("original");
  }

  @Test
  @DisplayName("a failed file write rolls back the rows and removes the files of a new package")
  void failedFileWriteRollsBackANewPackage() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    this.failAfterWritingFiles();

    final var response =
        this.publish(repo, name, "1.0.0", "first", tarballOf("first"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedPackageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(metadataFile(repo, name)).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write rolls back a new version and puts the metadata back")
  void failedFileWriteRollsBackANewVersion() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    final var first = tarballOf("first");
    assertThat(this.publish(repo, name, "1.0.0", "first", first, token).getStatus()).isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.failAfterWritingFiles();

    final var response = this.publish(repo, name, "1.1.0", "second", tarballOf("second"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(first);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.0.0");
  }

  @Test
  @DisplayName("a failed file write keeps the rows of the version being replaced")
  void failedFileWriteKeepsTheReplacedVersion() throws Exception {
    final var repo = this.npmRepo(true);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    assertThat(
            this.publish(repo, name, "1.0.0", "original", tarballOf("original"), token).getStatus())
        .isEqualTo(200);
    this.failAfterWritingFiles();

    final var response = this.publish(repo, name, "1.0.0", "second", tarballOf("second"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDescription(repo, name, "1.0.0")).isEqualTo("original");
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
    assertThat(metadataFile(repo, name)).exists();
  }

  @Test
  @DisplayName("a plain storage I/O failure surfaces as a server error and leaves nothing behind")
  void storageIoFailureLeavesNothingBehind() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    doAnswer(
            invocation -> {
              throw new IOException("disk full");
            })
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());

    final var response =
        this.publish(repo, name, "1.0.0", "first", tarballOf("first"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedPackageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  /**
   * Holds the first call of the storage write until the returned latch is released, and lets the
   * calls after it run as usual. The write is inside the publish's transaction, so a publish held
   * here has uncommitted rows.
   */
  private CountDownLatch holdTheFirstWrite(final CountDownLatch firstWriting) throws Exception {
    final var release = new CountDownLatch(1);

    doAnswer(
            invocation -> {
              firstWriting.countDown();
              release.await(30, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());

    return release;
  }

  @Test
  @DisplayName("a publish that loses the race for a version does not replace the winner's files")
  void loserOfTheRaceKeepsTheWinnersFiles() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    // The package exists before the race, so both publishes race for the version, not the package.
    assertThat(this.publish(repo, name, "1.0.0", "seed", tarballOf("seed"), token).getStatus())
        .isEqualTo(200);
    final var winnerTarball = tarballOf("winner");

    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = this.holdTheFirstWrite(winnerWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.publish(repo, name, "1.0.1", "winner", winnerTarball, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(
              () -> this.publish(repo, name, "1.0.1", "loser", tarballOf("loser"), token));
      // Long enough for the loser to reach the package row and wait on the winner's lock. If it is
      // slower, it is turned away by the existing-version check instead: the assertions hold both
      // ways.
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(403);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(tarballFile(repo, name, "1.0.1")).hasBinaryContent(winnerTarball);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(this.storedDescription(repo, name, "1.0.1"))
        .as("the row describes the winner's publish, the one that is stored")
        .isEqualTo("winner");
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.0.0", "1.0.1");
  }

  @Test
  @DisplayName(
      "two concurrent first publishes of a new package with the same version: the loser answers"
          + " 403, not 500, and the winner's files stay")
  void concurrentFirstPublishesOfTheSameVersionOneLoses() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    final var winnerTarball = tarballOf("winner");

    final var winnerWriting = new CountDownLatch(1);
    final var releaseWinner = this.holdTheFirstWrite(winnerWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner =
          executor.submit(() -> this.publish(repo, name, "1.0.0", "winner", winnerTarball, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser =
          executor.submit(
              () -> this.publish(repo, name, "1.0.0", "loser", tarballOf("loser"), token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseWinner.countDown();

      assertThat(winner.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(loser.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(403);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
    }

    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(winnerTarball);
    assertThat(this.storedPackageCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(1);
    assertThat(this.storedDescription(repo, name, "1.0.0")).isEqualTo("winner");
  }

  @Test
  @DisplayName(
      "two concurrent first publishes of a new package with different versions both succeed and"
          + " both stay in the package metadata")
  void concurrentFirstPublishesOfDifferentVersionsBothStay() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    final var firstTarball = tarballOf("first");
    final var secondTarball = tarballOf("second");

    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = this.holdTheFirstWrite(firstWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(() -> this.publish(repo, name, "1.0.0", "first", firstTarball, token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(() -> this.publish(repo, name, "2.0.0", "second", secondTarball, token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedPackageCount(repo, name)).isEqualTo(1);
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(2);
    assertThat(tarballFile(repo, name, "1.0.0")).hasBinaryContent(firstTarball);
    assertThat(tarballFile(repo, name, "2.0.0")).hasBinaryContent(secondTarball);
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "2.0.0");
  }

  @Test
  @DisplayName(
      "two concurrent publishes of different versions of an existing package both stay in the"
          + " package metadata")
  void concurrentPublishesOfDifferentVersionsBothStay() throws Exception {
    final var repo = this.npmRepo(false);
    final var name = uniquePackageName();
    final var token = this.adminToken();
    assertThat(this.publish(repo, name, "1.0.0", "seed", tarballOf("seed"), token).getStatus())
        .isEqualTo(200);
    final var firstTarball = tarballOf("first");
    final var secondTarball = tarballOf("second");

    final var firstWriting = new CountDownLatch(1);
    final var releaseFirst = this.holdTheFirstWrite(firstWriting);

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var first =
          executor.submit(() -> this.publish(repo, name, "1.1.0", "first", firstTarball, token));
      assertThat(firstWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var second =
          executor.submit(() -> this.publish(repo, name, "1.2.0", "second", secondTarball, token));
      TimeUnit.MILLISECONDS.sleep(500);
      releaseFirst.countDown();

      assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(second.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }

    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
    assertThat(tarballFile(repo, name, "1.1.0")).hasBinaryContent(firstTarball);
    assertThat(tarballFile(repo, name, "1.2.0")).hasBinaryContent(secondTarball);
    assertThat(this.metadataVersions(repo, name))
        .as("the second publish read the metadata the first one wrote, not the one before it")
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
  }
}
