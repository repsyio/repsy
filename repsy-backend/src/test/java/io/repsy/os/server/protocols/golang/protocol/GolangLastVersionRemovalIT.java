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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.golang.shared.storage.services.GolangStorageService;
import io.repsy.os.server.protocols.golang.ui.facades.GolangApiFacade;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1288: deleting the last version of a Go module removes the module (its row and its storage),
 * gives the usage of what it removed back to the repo and reports every version that went, and none
 * of it races with a publish of the same module.
 *
 * <p>The delete locks the module row for update and a publish shares that lock, so the two take
 * turns. Both orders are driven deterministically: the operation that goes first is held inside its
 * transaction, at a storage call, by a latch; the other one is started and the test waits until the
 * database reports it blocked on a lock; then the first one goes on. A stress test runs the pairs
 * without any hold.
 *
 * <p>Runs without a test transaction, because a race needs both sides to commit. It deletes the
 * repos and the user it commits. {@link UsageUpdateService} is mocked: it is {@code @Async}, so it
 * could not see rows a rolled-back test never committed anyway, and the mock records what a delete
 * gives back.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
@DisplayName("Deleting the last Go module version removes the module (RPS-1288)")
class GolangLastVersionRemovalIT extends AbstractIntegrationTest {

  private static final int STRESS_ROUNDS = 25;

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private GolangStorageService golangStorageService;

  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private GolangApiFacade golangApiFacade;
  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private String panelToken;
  private String protocolToken;

  @BeforeEach
  void createAdmin() {
    final var info =
        this.userTxService.create(
            uniqueUsername("go-rm"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(info.getId());
    this.panelToken = this.bearerTokenFor(info.getId(), info.getUsername());
    this.protocolToken =
        this.protocolBearerTokenFor(this.userRepository.findById(info.getId()).orElseThrow());
  }

  @AfterEach
  void deleteCommittedData() {
    reset(this.golangStorageService);
    // Every table that references a repo cascades on delete, so this takes the modules with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private RepoInfo goRepo() {
    final var repoInfo =
        this.repoTxService.createRepo(uniqueRepoName("go-rm"), RepoType.GOLANG, false, null);
    this.createdRepoIds.add(repoInfo.getId());
    this.golangStorageService.createRepo(repoInfo.getStorageKey());

    return repoInfo;
  }

  private static String uniqueModulePath() {
    return "example.com/rm" + randomTag();
  }

  private static byte[] moduleZip(final String modulePath, final String version) {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = modulePath + "@" + version + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + modulePath + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "marker.go"));
      zip.write(("package marker // " + version + "\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  private MockHttpServletResponse upload(
      final RepoInfo repo, final String modulePath, final String version) throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/" + modulePath + "/@v/" + version, repo.getName())
                .header(AUTHORIZATION, this.protocolToken)
                .contentType("application/zip")
                .content(moduleZip(modulePath, version))
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void publish(final RepoInfo repo, final String modulePath, final String version)
      throws Exception {
    final var response = this.upload(repo, modulePath, version);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
  }

  private MockHttpServletResponse wireGet(
      final RepoInfo repo, final String modulePath, final String suffix) throws Exception {
    return this.mockMvc
        .perform(
            get("/{repo}/" + modulePath + "/" + suffix, repo.getName())
                .header(AUTHORIZATION, this.protocolToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void deleteVersionThroughThePanel(
      final RepoInfo repo, final String modulePath, final String version) throws Exception {
    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}/versions", repo.getName())
                .param("modulePath", modulePath)
                .param("version", version)
                .with(apiPort())
                .header(AUTHORIZATION, this.panelToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleVersionDeleted"));
  }

  private void deleteModuleThroughThePanel(final RepoInfo repo, final String modulePath)
      throws Exception {
    this.mockMvc
        .perform(
            delete("/api/go/modules/{repo}", repo.getName())
                .param("modulePath", modulePath)
                .with(apiPort())
                .header(AUTHORIZATION, this.panelToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.msgId").value("moduleDeleted"));
  }

  private Repo entityOf(final RepoInfo repo) {
    return this.repoRepository.findByName(repo.getName()).orElseThrow();
  }

  private Path atV(final RepoInfo repo, final String modulePath) {
    return storageDirOf(this.entityOf(repo)).resolve(modulePath).resolve("@v");
  }

  /** The names of the files a module keeps in storage, sorted; empty when it has no directory. */
  private List<String> storedFiles(final RepoInfo repo, final String modulePath)
      throws IOException {
    final var dir = this.atV(repo, modulePath);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (final Stream<Path> files = Files.list(dir)) {
      return files.map(file -> file.getFileName().toString()).sorted().toList();
    }
  }

  private static List<String> filesOf(final String... versions) {
    return Stream.of(versions)
        .flatMap(v -> Stream.of(v + ".info", v + ".mod", v + ".zip"))
        .sorted()
        .toList();
  }

  private long bytesOnDisk(final RepoInfo repo, final String modulePath) throws IOException {
    final var dir = this.atV(repo, modulePath);
    if (!Files.exists(dir)) {
      return 0L;
    }
    try (final Stream<Path> files = Files.list(dir)) {
      return files.mapToLong(file -> file.toFile().length()).sum();
    }
  }

  private long bytesOfVersion(final RepoInfo repo, final String modulePath, final String version)
      throws IOException {
    long total = 0L;
    for (final var name : filesOf(version)) {
      final var file = this.atV(repo, modulePath).resolve(name);
      total += Files.exists(file) ? Files.size(file) : 0L;
    }

    return total;
  }

  private int count(final String sql, final Object... arguments) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);

    return count == null ? 0 : count;
  }

  private int moduleCount(final RepoInfo repo, final String modulePath) {
    return this.count(
        "select count(*) from go_module where repo_id = ? and module_path = ?",
        repo.getId(),
        modulePath);
  }

  private List<String> versionsOf(final RepoInfo repo, final String modulePath) {
    return this.jdbcTemplate.queryForList(
        """
        select v.version from go_module_version v
          join go_module m on m.id = v.module_id
        where m.repo_id = ? and m.module_path = ?
        order by v.version
        """,
        String.class,
        repo.getId(),
        modulePath);
  }

  private List<String> deletedVersionEvents(final String modulePath) {
    return this.applicationEvents.stream(ArtifactVersionDeletedEvent.class)
        .filter(event -> event.artifactName().equals(modulePath))
        .map(ArtifactVersionDeletedEvent::artifactVersion)
        .sorted()
        .toList();
  }

  /** The disk usage every {@code updateUsage} of the test reported, summed. */
  private long reportedDiskUsage(final RepoInfo repo) {
    final var captor = ArgumentCaptor.forClass(UsageChangedInfo.class);
    Mockito.verify(this.usageUpdateService, Mockito.atLeast(0)).updateUsage(captor.capture());

    return captor.getAllValues().stream()
        .filter(info -> info.repoId().equals(repo.getStorageKey()))
        .mapToLong(info -> info.usages().getDiskUsage())
        .sum();
  }

  @Test
  @DisplayName(
      "deleting the last version removes the module, its directory and gives the usage back")
  void lastVersionRemovesTheModule() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    this.publish(repo, module, "v1.1.0");
    final var firstBytes = this.bytesOfVersion(repo, module, "v1.0.0");
    final var secondBytes = this.bytesOfVersion(repo, module, "v1.1.0");
    assertThat(firstBytes).isPositive();
    // What the uploads reported is not the refund under test.
    Mockito.clearInvocations(this.usageUpdateService);

    this.deleteVersionThroughThePanel(repo, module, "v1.0.0");

    assertThat(this.moduleCount(repo, module)).as("a version is left: the module stays").isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.1.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.1.0"));
    assertThat(this.reportedDiskUsage(repo)).isEqualTo(-firstBytes);
    assertThat(this.deletedVersionEvents(module)).containsExactly("v1.0.0");

    this.deleteVersionThroughThePanel(repo, module, "v1.1.0");

    assertThat(this.moduleCount(repo, module))
        .as("the last version went: the module goes")
        .isZero();
    assertThat(this.atV(repo, module)).doesNotExist();
    assertThat(this.reportedDiskUsage(repo)).isEqualTo(-(firstBytes + secondBytes));
    assertThat(this.deletedVersionEvents(module)).containsExactly("v1.0.0", "v1.1.0");
    // The panel no longer knows the module...
    this.mockMvc
        .perform(
            get("/api/go/modules/{repo}/info", repo.getName())
                .param("modulePath", module)
                .with(apiPort())
                .header(AUTHORIZATION, this.panelToken))
        .andExpect(status().isNotFound());
    // ...and the wire answers as it always did for a module without versions.
    final var list = this.wireGet(repo, module, "@v/list");
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(list.getContentAsString()).isEmpty();
    assertThat(this.wireGet(repo, module, "@latest").getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a module whose last version was deleted is created again by the next publish")
  void republishRecreatesTheModule() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    this.deleteVersionThroughThePanel(repo, module, "v1.0.0");
    assertThat(this.moduleCount(repo, module)).isZero();

    this.publish(repo, module, "v1.0.0");

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.0.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.0.0"));
    assertThat(this.wireGet(repo, module, "@v/list").getContentAsString()).isEqualTo("v1.0.0");
    assertThat(this.wireGet(repo, module, "@latest").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("deleting a version that is not there is not found and keeps the module")
  void missingVersionIsNotFound() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");

    assertThatThrownBy(() -> this.golangApiFacade.deleteModuleVersion(repo, module, "v9.9.9"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("versionNotFound");
    assertThatThrownBy(() -> this.golangApiFacade.deleteModuleVersion(repo, module + "x", "v1.0.0"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("moduleNotFound");

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.0.0");
    assertThat(this.deletedVersionEvents(module)).isEmpty();
  }

  @Test
  @DisplayName("deleting the module reports every version that went and gives all its bytes back")
  void deleteModuleReportsEveryVersion() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    this.publish(repo, module, "v1.1.0");
    this.publish(repo, module, "v2.0.0");
    final var bytes = this.bytesOnDisk(repo, module);
    assertThat(bytes).isPositive();
    Mockito.clearInvocations(this.usageUpdateService);

    this.deleteModuleThroughThePanel(repo, module);

    assertThat(this.moduleCount(repo, module)).isZero();
    assertThat(this.atV(repo, module)).doesNotExist();
    assertThat(this.deletedVersionEvents(module)).containsExactly("v1.0.0", "v1.1.0", "v2.0.0");
    assertThat(this.reportedDiskUsage(repo)).isEqualTo(-bytes);
  }

  @Test
  @DisplayName("a module's removal leaves the module that lives below its directory alone")
  void nestedModuleSurvivesTheRemovalOfItsParent() throws Exception {
    final var repo = this.goRepo();
    final var parent = uniqueModulePath();
    final var nested = parent + "/v2";
    this.publish(repo, parent, "v1.0.0");
    this.publish(repo, nested, "v2.0.0");

    this.deleteVersionThroughThePanel(repo, parent, "v1.0.0");

    assertThat(this.moduleCount(repo, parent)).isZero();
    assertThat(this.atV(repo, parent)).doesNotExist();
    assertThat(this.moduleCount(repo, nested)).isOne();
    assertThat(this.storedFiles(repo, nested)).isEqualTo(filesOf("v2.0.0"));
    assertThat(this.wireGet(repo, nested, "@v/list").getContentAsString()).isEqualTo("v2.0.0");

    this.publish(repo, parent, "v1.1.0");
    this.deleteModuleThroughThePanel(repo, parent);

    assertThat(this.moduleCount(repo, nested)).isOne();
    assertThat(this.storedFiles(repo, nested)).isEqualTo(filesOf("v2.0.0"));
  }

  // ---------------------------------------------------------------------------------------------
  // Races
  // ---------------------------------------------------------------------------------------------

  private CountDownLatch holdNextFileWrite(final CountDownLatch reached) {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.golangStorageService)
        .writeInputStreamToPath(any(), any(), any());

    return gate;
  }

  private CountDownLatch holdNextDirectoryDelete(final CountDownLatch reached) {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.golangStorageService)
        .deleteDirectory(any());

    return gate;
  }

  private Object passGate(
      final CountDownLatch reached, final CountDownLatch gate, final InvocationOnMock invocation)
      throws Throwable {
    reached.countDown();
    assertThat(gate.await(30, TimeUnit.SECONDS)).as("the test opened the gate").isTrue();

    return invocation.callRealMethod();
  }

  /** Waits until a database session is blocked on a lock, or the operation has finished. */
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
   * one has met the lock, then lets the first one go on. Neither may fail.
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
  @DisplayName("a delete of the last version waits for a publish, and the module keeps its new one")
  void deleteOfTheLastVersionWaitsForAPublish() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextFileWrite(reached);

    // The publish is inside its transaction, holding the module row and its uncommitted v1.1.0. A
    // delete that did not wait for it would count no version left and remove the module under it.
    this.race(
        reached,
        gate,
        () -> {
          this.publish(repo, module, "v1.1.0");
          return null;
        },
        () -> {
          this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");
          return null;
        });

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.1.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.1.0"));
    assertThat(this.wireGet(repo, module, "@v/list").getContentAsString()).isEqualTo("v1.1.0");
  }

  @Test
  @DisplayName("a publish waits for the delete of the last version and creates the module again")
  void publishWaitsForTheDeleteOfTheLastVersion() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextDirectoryDelete(reached);

    // The delete is inside its transaction, with the module row deleted and uncommitted. The
    // publish
    // must neither hit a foreign key violation on the vanished row nor lose its files to the
    // delete.
    this.race(
        reached,
        gate,
        () -> {
          this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");
          return null;
        },
        () -> {
          this.publish(repo, module, "v1.1.0");
          return null;
        });

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.1.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.1.0"));
    assertThat(this.wireGet(repo, module, "@v/list").getContentAsString()).isEqualTo("v1.1.0");
  }

  @Test
  @DisplayName("a publish of the deleted version itself waits for the delete and stores it again")
  void publishOfTheSameVersionWaitsForTheDelete() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextDirectoryDelete(reached);

    this.race(
        reached,
        gate,
        () -> {
          this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");
          return null;
        },
        () -> {
          this.publish(repo, module, "v1.0.0");
          return null;
        });

    assertThat(this.moduleCount(repo, module)).isOne();
    assertThat(this.versionsOf(repo, module)).containsExactly("v1.0.0");
    assertThat(this.storedFiles(repo, module)).isEqualTo(filesOf("v1.0.0"));
  }

  @Test
  @DisplayName("a module delete waits for a publish and removes what the publish stored")
  void deleteModuleWaitsForAPublish() throws Exception {
    final var repo = this.goRepo();
    final var module = uniqueModulePath();
    this.publish(repo, module, "v1.0.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextFileWrite(reached);

    this.race(
        reached,
        gate,
        () -> {
          this.publish(repo, module, "v1.1.0");
          return null;
        },
        () -> this.golangApiFacade.deleteModule(repo, module));

    assertThat(this.moduleCount(repo, module)).isZero();
    assertThat(this.atV(repo, module)).doesNotExist();
    assertThat(this.versionsOf(repo, module)).isEmpty();
  }

  @Test
  @DisplayName(
      "publishes and deletes of the last version, run together, leave rows and files equal")
  void stressPublishAgainstDeleteOfTheLastVersion() throws Exception {
    final var repo = this.goRepo();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      for (var round = 0; round < STRESS_ROUNDS; round++) {
        final var module = uniqueModulePath();
        this.publish(repo, module, "v1.0.0");
        final var barrier = new CyclicBarrier(2);

        final Future<MockHttpServletResponse> publishing =
            executor.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  return this.upload(repo, module, "v1.1.0");
                });
        final Future<Void> deleting =
            executor.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");
                  return null;
                });

        final var response = publishing.get(60, TimeUnit.SECONDS);
        assertThat(response.getStatus())
            .as("round %d: %s", round, response.getContentAsString())
            .isEqualTo(200);
        deleting.get(60, TimeUnit.SECONDS);

        // Whichever went first, the publish's version is there, and only it.
        assertThat(this.moduleCount(repo, module)).as("round %d", round).isOne();
        assertThat(this.versionsOf(repo, module)).as("round %d", round).containsExactly("v1.1.0");
        assertThat(this.storedFiles(repo, module))
            .as("round %d", round)
            .isEqualTo(filesOf("v1.1.0"));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("deletes of the two versions of a module, run together, remove the module once")
  void stressTwoDeletesOfTheLastTwoVersions() throws Exception {
    final var repo = this.goRepo();
    final var executor = Executors.newFixedThreadPool(2);
    try {
      for (var round = 0; round < STRESS_ROUNDS; round++) {
        final var module = uniqueModulePath();
        this.publish(repo, module, "v1.0.0");
        this.publish(repo, module, "v1.1.0");
        final var barrier = new CyclicBarrier(2);

        final var first =
            executor.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  this.golangApiFacade.deleteModuleVersion(repo, module, "v1.0.0");
                  return null;
                });
        final var second =
            executor.submit(
                () -> {
                  barrier.await(30, TimeUnit.SECONDS);
                  this.golangApiFacade.deleteModuleVersion(repo, module, "v1.1.0");
                  return null;
                });

        first.get(60, TimeUnit.SECONDS);
        second.get(60, TimeUnit.SECONDS);

        assertThat(this.moduleCount(repo, module)).as("round %d", round).isZero();
        assertThat(this.versionsOf(repo, module)).as("round %d", round).isEmpty();
        assertThat(this.storedFiles(repo, module)).as("round %d", round).isEmpty();
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
