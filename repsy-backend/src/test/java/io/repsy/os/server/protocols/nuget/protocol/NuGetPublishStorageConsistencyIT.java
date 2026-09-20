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
package io.repsy.os.server.protocols.nuget.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-999: a NuGet push leaves storage and the database in agreement, whichever half of it fails.
 *
 * <p>The version row is written first and the files second, inside one transaction. So a push whose
 * row cannot be written never touches the files, and a push whose files cannot be written leaves no
 * row (and, for a new version, no files) behind.
 *
 * <p>Runs without a test transaction, unlike {@link NuGetPublishProtocolIT}: a failed row write
 * aborts a PostgreSQL transaction, so inside a test transaction the state afterwards could not be
 * read, and a race between two pushes needs both to commit. It deletes the repos and users it
 * commits.
 *
 * <p>{@link UsageUpdateService} is mocked like in {@link NuGetPublishProtocolIT}: the mock records
 * whether a push reported any usage.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("NuGet push keeps storage and the database in agreement (RPS-999)")
class NuGetPublishStorageConsistencyIT extends AbstractIntegrationTest {

  private static final String PUSH_PATH = "/{repo}/v3/package";

  /**
   * A title the database refuses through {@link #rejectTitleAtTheDatabase()}. An over-long title no
   * longer does it (RPS-1005 cuts it), so the row rejection these tests need is made explicit.
   */
  private static final String REJECTED_TITLE = "reject-me";

  private static final String REJECT_TITLE_CONSTRAINT = "ch_nuget_package_version__it_rejected";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private NuGetStorageService nugetStorageService;

  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;
  @Autowired private RepoTxService repoTxService;

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
        "alter table nuget_package_version drop constraint if exists " + REJECT_TITLE_CONSTRAINT);
  }

  /**
   * Makes the database refuse a version row with {@link #REJECTED_TITLE}: a check violation, which
   * is a {@code DataIntegrityViolationException} but not the unique index on (package, version).
   */
  private void rejectTitleAtTheDatabase() {
    this.jdbcTemplate.execute(
        "alter table nuget_package_version add constraint "
            + REJECT_TITLE_CONSTRAINT
            + " check (title is distinct from '"
            + REJECTED_TITLE
            + "')");
  }

  private Repo nugetRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("nuget-cons");
    final var created = this.repoTxService.createRepo(name, RepoType.NUGET, false, null);
    this.createdRepoIds.add(created.getId());
    this.nugetStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("nuget-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  /** A package whose {@code content} lets a test tell two pushes of the same version apart. */
  private static byte[] nupkg(
      final String id, final String version, final String title, final String content) {
    final var nuspec =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
          <metadata>
            <id>%s</id>
            <version>%s</version>
            <title>%s</title>
            <authors>Repsy</authors>
            <description>consistency fixture</description>
          </metadata>
        </package>
        """
            .formatted(id, version, title);
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(id + ".nuspec"));
      zip.write(nuspec.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("lib/net8.0/" + id + ".dll"));
      zip.write(content.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private static String uniquePackageId() {
    return "Repsy.Consistency" + randomTag();
  }

  private MockHttpServletResponse push(final Repo repo, final byte[] nupkg, final String token)
      throws Exception {
    final var request =
        multipart(HttpMethod.PUT, PUSH_PATH, repo.getName())
            .part(new MockPart("package", "package.nupkg", nupkg))
            .header(AUTHORIZATION, token);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static Path versionDir(final Repo repo, final String id, final String version) {
    return storageDirOf(repo)
        .resolve("packages")
        .resolve(id.toLowerCase(Locale.ROOT))
        .resolve(version);
  }

  private static Path nupkgFile(final Repo repo, final String id, final String version) {
    return versionDir(repo, id, version)
        .resolve(id.toLowerCase(Locale.ROOT) + "." + version + ".nupkg");
  }

  private int storedVersionCount(final Repo repo, final String id) {
    return this.nugetPackageRepository
        .findByRepoIdAndPackageIdIgnoreCase(repo.getId(), id)
        .map(
            pkg ->
                this.nugetPackageVersionRepository
                    .findByNugetPackageIdOrderByPublishedAtDesc(pkg.getId())
                    .size())
        .orElse(0);
  }

  /** Writes the files like the real service, then fails, as a storage that dies mid-push would. */
  private void failAfterWritingFiles() throws IOException {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IOException("storage went away after the write");
            })
        .when(this.nugetStorageService)
        .writePackage(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a row the database rejects leaves no files behind")
  void rejectedRowLeavesNoFiles() throws Exception {
    final var repo = this.nugetRepo(false);
    final var id = uniquePackageId();
    this.rejectTitleAtTheDatabase();

    final var response =
        this.push(repo, nupkg(id, "1.0.0", REJECTED_TITLE, "never stored"), this.adminToken());

    assertThat(response.getStatus())
        .as("a rejection that is not a duplicate version is a server error, not a 409 (RPS-1005)")
        .isEqualTo(500);
    assertThat(this.storedVersionCount(repo, id)).isZero();
    assertThat(versionDir(repo, id, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a row the database rejects leaves the files of the version it would replace")
  void rejectedOverrideKeepsTheExistingVersion() throws Exception {
    final var repo = this.nugetRepo(true);
    final var id = uniquePackageId();
    final var token = this.adminToken();
    final var original = nupkg(id, "1.0.0", "original", "the first push");

    assertThat(this.push(repo, original, token).getStatus()).isEqualTo(201);
    this.rejectTitleAtTheDatabase();

    final var response =
        this.push(repo, nupkg(id, "1.0.0", REJECTED_TITLE, "the second push"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(Files.readAllBytes(nupkgFile(repo, id, "1.0.0"))).isEqualTo(original);
    assertThat(this.storedVersionCount(repo, id)).isEqualTo(1);
    assertThat(
            this.nugetPackageVersionRepository
                .findByNugetPackageIdOrderByPublishedAtDesc(
                    this.nugetPackageRepository
                        .findByRepoIdAndPackageIdIgnoreCase(repo.getId(), id)
                        .orElseThrow()
                        .getId())
                .getFirst()
                .getTitle())
        .isEqualTo("original");
  }

  @Test
  @DisplayName("a push that loses the race for a version does not replace the winner's files")
  void loserOfTheRaceKeepsTheWinnersFiles() throws Exception {
    final var repo = this.nugetRepo(false);
    final var id = uniquePackageId();
    final var token = this.adminToken();
    final var winnerPackage = nupkg(id, "1.0.0", "winner", "the winner's bytes");
    final var loserPackage = nupkg(id, "1.0.0", "loser", "the loser's bytes");

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
        .when(this.nugetStorageService)
        .writePackage(any(), any(), any(), any(), any());

    final var executor = Executors.newFixedThreadPool(2);
    try {
      final var winner = executor.submit(() -> this.push(repo, winnerPackage, token));
      assertThat(winnerWriting.await(30, TimeUnit.SECONDS)).isTrue();

      final var loser = executor.submit(() -> this.push(repo, loserPackage, token));
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

    verify(this.nugetStorageService, times(1)).writePackage(any(), any(), any(), any(), any());
    assertThat(Files.readAllBytes(nupkgFile(repo, id, "1.0.0"))).isEqualTo(winnerPackage);
    assertThat(this.storedVersionCount(repo, id)).isEqualTo(1);
  }

  @Test
  @DisplayName("a failed file write rolls back the row and removes the files of a new version")
  void failedFileWriteRollsBackANewVersion() throws Exception {
    final var repo = this.nugetRepo(false);
    final var id = uniquePackageId();
    this.failAfterWritingFiles();

    final var response =
        this.push(repo, nupkg(id, "1.0.0", "new", "half stored"), this.adminToken());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, id)).isZero();
    assertThat(versionDir(repo, id, "1.0.0")).doesNotExist();
    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("a failed file write keeps the row and the files of the version being replaced")
  void failedFileWriteKeepsTheReplacedVersion() throws Exception {
    final var repo = this.nugetRepo(true);
    final var id = uniquePackageId();
    final var token = this.adminToken();
    assertThat(this.push(repo, nupkg(id, "1.0.0", "original", "the first push"), token).getStatus())
        .isEqualTo(201);
    this.failAfterWritingFiles();

    final var response = this.push(repo, nupkg(id, "1.0.0", "second", "the second push"), token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(this.storedVersionCount(repo, id)).isEqualTo(1);
    assertThat(nupkgFile(repo, id, "1.0.0")).exists();
  }
}
