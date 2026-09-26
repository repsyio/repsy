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
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.npm.ui.facades.NpmApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1280: unpublishing, deprecating and deleting npm versions and packages leave storage and the
 * database in agreement, whichever half of the operation fails, and cannot lose an update against a
 * publish of the same package.
 *
 * <p>Like a publish (RPS-1124) and a dist-tag change (RPS-1272, see {@link
 * NpmDistTagStorageConsistencyIT}), each of these operations locks the package row, writes and
 * flushes the rows first and changes storage second, in one transaction. The changes of the package
 * metadata are made to the metadata as it is stored, and put back when the operation fails; the
 * tarball is removed last, because a removed file cannot be put back.
 *
 * <p>Each race is driven deterministically: the operation that goes first is held inside its
 * transaction, at its storage call, by a latch. The other one is then started, and the test waits
 * until the database reports a session blocked on a lock (or, without the lock, the operation
 * finished) before it lets the first one go on. Nothing depends on a sleep.
 *
 * <p>Runs without a test transaction, because a failed row write aborts a PostgreSQL transaction
 * and a race needs both requests to commit. It deletes the repos and users it commits, and the
 * triggers it adds.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("npm deletes and deprecations keep storage and the database in agreement (RPS-1280)")
class NpmDeleteStorageConsistencyIT extends AbstractIntegrationTest {

  /** A version the database refuses to delete through {@link #refuseVersionDeletes()}. */
  private static final String UNDELETABLE_VERSION = "1.9.9";

  /** A package the database refuses to delete through {@link #refusePackageDeletes()}. */
  private static final String UNDELETABLE_PACKAGE_PREFIX = "keepme-";

  /** A deprecation message the database refuses to store through {@link #refuseDeprecation()}. */
  private static final String REFUSED_MESSAGE = "reject-me";

  private static final String VERSION_TRIGGER = "it_refuse_npm_version_delete";
  private static final String PACKAGE_TRIGGER = "it_refuse_npm_package_delete";
  private static final String DEPRECATION_TRIGGER = "it_refuse_npm_deprecation";
  private static final String HOST = "http://localhost:9090";
  private static final String PACKAGE_PATH = "/{repo}/{packagePath}";
  private static final String DELETE_PACKAGE_PATH = "/{repo}/{name}/-rev/{rev}";

  @MockitoBean private UsageUpdateService usageUpdateService;

  /** A spy that calls through, so only the test that stubs it changes the storage behaviour. */
  @MockitoSpyBean private NpmStorageService npmStorageService;

  /** The file system under the npm storage service, to make the removal of a tarball fail. */
  @MockitoSpyBean(name = "osStorageStrategyNpm")
  private StorageStrategy npmStorageStrategy;

  @Autowired private NpmProtocolFacade npmProtocolFacade;
  @Autowired private NpmApiFacade npmApiFacade;
  @Autowired private RepoTxService repoTxService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // The triggers go first: deleting the repos deletes the rows they would refuse to delete.
    this.jdbcTemplate.execute(
        "drop trigger if exists " + VERSION_TRIGGER + " on npm_package_version");
    this.jdbcTemplate.execute("drop function if exists " + VERSION_TRIGGER + "()");
    this.jdbcTemplate.execute("drop trigger if exists " + PACKAGE_TRIGGER + " on npm_package");
    this.jdbcTemplate.execute("drop function if exists " + PACKAGE_TRIGGER + "()");
    this.jdbcTemplate.execute(
        "drop trigger if exists " + DEPRECATION_TRIGGER + " on npm_package_version");
    this.jdbcTemplate.execute("drop function if exists " + DEPRECATION_TRIGGER + "()");
    // Every table that references a repo cascades on delete, so this takes the packages with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.userRepository.deleteAllById(this.createdUserIds);
    this.createdUserIds.clear();
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private void refuseVersionDeletes() {
    this.refuse(
        VERSION_TRIGGER,
        "before delete on npm_package_version",
        "old.version = '" + UNDELETABLE_VERSION + "'",
        "old");
  }

  private void refusePackageDeletes() {
    this.refuse(
        PACKAGE_TRIGGER,
        "before delete on npm_package",
        "old.name like '" + UNDELETABLE_PACKAGE_PREFIX + "%'",
        "old");
  }

  private void refuseDeprecation() {
    this.refuse(
        DEPRECATION_TRIGGER,
        "before update on npm_package_version",
        "new.deprecation_message = '" + REFUSED_MESSAGE + "'",
        "new");
  }

  private void refuse(
      final String name, final String event, final String condition, final String row) {
    this.jdbcTemplate.execute(
        "create function "
            + name
            + "() returns trigger language plpgsql as $$ begin if "
            + condition
            + " then raise exception 'the test refuses this change'; end if; return "
            + row
            + "; end $$");
    this.jdbcTemplate.execute(
        "create trigger " + name + " " + event + " for each row execute function " + name + "()");
  }

  private Repo npmRepo() {
    final var name = uniqueRepoName("npm-del");
    final var created = this.repoTxService.createRepo(name, RepoType.NPM, false, null);
    this.createdRepoIds.add(created.getId());
    // No scan, no scan thread through the spy the tests stub (RPS-1336).
    this.disableSecurityScan(created.getId());
    this.npmStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private RepoInfo infoOf(final Repo repo) {
    return this.repoTxService.getRepoByNameAndType(repo.getName(), RepoType.NPM).orElseThrow();
  }

  private String adminToken() {
    final var userInfo =
        this.userTxService.create(uniqueUsername("npm-admin"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(userInfo.getId());

    return this.protocolBearerTokenFor(
        this.userRepository.findById(userInfo.getId()).orElseThrow());
  }

  private static String uniquePackageName() {
    return "deleting-" + randomTag();
  }

  private static byte[] tarballOf(final String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] publishBody(
      final Repo repo, final String name, final String version, final byte[] tarball) {

    final var dist = new LinkedHashMap<String, Object>();
    dist.put(
        "tarball",
        HOST + "/" + repo.getName() + "/" + name + "/-/" + name + "-" + version + ".tgz");

    final var versionMetadata = new LinkedHashMap<String, Object>();
    versionMetadata.put("name", name);
    versionMetadata.put("version", version);
    versionMetadata.put("description", "version " + version);
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
      final byte[] tarball,
      final String token)
      throws Exception {

    final var request =
        put(PACKAGE_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.publishBody(repo, name, version, tarball));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private MockHttpServletResponse putMetadata(
      final Repo repo, final String name, final Map<String, Object> metadata, final String token)
      throws Exception {

    final var request =
        put(PACKAGE_PATH, repo.getName(), name)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(this.objectMapper.writeValueAsBytes(metadata));

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /** {@code npm deprecate}: the stored packument with the message set, sent back as a whole. */
  private Map<String, Object> deprecationPayload(
      final Repo repo, final String name, final String version, final String message)
      throws IOException {

    return this.withDeprecation(this.metadata(repo, name), version, message);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> withDeprecation(
      final Map<String, Object> metadata, final String version, final String message) {

    final var versions = (Map<String, Object>) metadata.get("versions");
    ((Map<String, Object>) versions.get(version)).put("deprecated", message);

    return metadata;
  }

  /** {@code npm unpublish name@version}: the packument without the version, from the protocol. */
  @SuppressWarnings("unchecked")
  private String unpublish(final Repo repo, final String name, final String version)
      throws IOException {

    final var payload = this.metadata(repo, name);
    ((Map<String, Object>) payload.get("versions")).remove(version);

    return this.npmProtocolFacade.unPublishPackageVersion(
        this.contextOf(repo), null, name, payload);
  }

  private ProtocolContext contextOf(final Repo repo) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(this.infoOf(repo))
            .build());

    return context;
  }

  private MockHttpServletResponse deletePackageOverHttp(
      final Repo repo, final String name, final String token) throws Exception {

    final var request =
        delete(DELETE_PACKAGE_PATH, repo.getName(), name, "1-abc").header(AUTHORIZATION, token);

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private static Path tarballFile(final Repo repo, final String name, final String version) {
    return storageDirOf(repo).resolve(name).resolve(name + "-" + version + ".tgz");
  }

  private static Path metadataFile(final Repo repo, final String name) {
    return storageDirOf(repo).resolve(name).resolve("package.json");
  }

  private Map<String, Object> metadata(final Repo repo, final String name) throws IOException {
    return this.objectMapper.readValue(
        Files.readAllBytes(metadataFile(repo, name)), new TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private List<String> metadataVersions(final Repo repo, final String name) throws IOException {
    return List.copyOf(((Map<String, Object>) this.metadata(repo, name).get("versions")).keySet());
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> metadataTags(final Repo repo, final String name) throws IOException {
    return new TreeMap<>((Map<String, String>) this.metadata(repo, name).get("dist-tags"));
  }

  @SuppressWarnings("unchecked")
  private Object metadataDeprecation(final Repo repo, final String name, final String version)
      throws IOException {

    final var versions = (Map<String, Object>) this.metadata(repo, name).get("versions");

    return ((Map<String, Object>) versions.get(version)).get("deprecated");
  }

  private int count(final String sql, final Object... arguments) {
    final var count = this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);

    return count == null ? 0 : count;
  }

  private int storedVersionCount(final Repo repo, final String name) {
    return this.count(
        """
        select count(*) from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ?
        """,
        repo.getId(),
        name);
  }

  private List<String> storedVersions(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForList(
        """
        select v.version from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ? order by v.version
        """,
        String.class,
        repo.getId(),
        name);
  }

  private int packageCount(final Repo repo, final String name) {
    return this.count(
        "select count(*) from npm_package where repo_id = ? and name = ?", repo.getId(), name);
  }

  private String storedLatest(final Repo repo, final String name) {
    return this.jdbcTemplate.queryForObject(
        "select latest from npm_package where repo_id = ? and name = ?",
        String.class,
        repo.getId(),
        name);
  }

  /** The dist-tags the database has for the package, as tag to version. */
  private Map<String, String> storedTags(final Repo repo, final String name) {
    final var tags = new TreeMap<String, String>();

    this.jdbcTemplate.query(
        """
        select t.tag_name, v.version from npm_package_dist_tag t
          join npm_package_version v on v.id = t.package_version_id
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ?
        """,
        rs -> {
          tags.put(rs.getString(1), rs.getString(2));
        },
        repo.getId(),
        name);

    return tags;
  }

  private String storedDeprecationMessage(
      final Repo repo, final String name, final String version) {
    return this.jdbcTemplate.queryForObject(
        """
        select coalesce(v.deprecation_message, '') from npm_package_version v
          join npm_package p on p.id = v.package_id
        where p.repo_id = ? and p.name = ? and v.version = ?
        """,
        String.class,
        repo.getId(),
        name,
        version);
  }

  /** A package with the published versions, each with its own tarball, the last being latest. */
  private String publishedVersions(final Repo repo, final String token, final String... versions)
      throws Exception {
    final var name = uniquePackageName();

    for (final var version : versions) {
      assertThat(
              this.publish(repo, name, version, tarballOf("tarball " + version), token).getStatus())
          .isEqualTo(200);
    }

    return name;
  }

  // -------------------------------------------------------------------------------------------
  // Holding an operation inside its transaction
  // -------------------------------------------------------------------------------------------

  /** Holds the first write of the package metadata (after the read it is based on) at the gate. */
  private CountDownLatch holdNextMetadataWrite(final CountDownLatch reached) throws Exception {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());

    return gate;
  }

  private CountDownLatch holdNextPackageDelete(final CountDownLatch reached) {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .deletePackage(any(), any());

    return gate;
  }

  private CountDownLatch holdNextPublish(final CountDownLatch reached) throws Exception {
    final var gate = new CountDownLatch(1);
    doAnswer(invocation -> this.passGate(reached, gate, invocation))
        .doAnswer(InvocationOnMock::callRealMethod)
        .when(this.npmStorageService)
        .writeTarballAndMetadata(any(), any(), any(), any(), any(), any());

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

  /** Runs the action here and returns what it failed with, as a finished future. */
  private Future<?> attempt(final Callable<?> action) {
    final var result = new CompletableFuture<Object>();

    try {
      result.complete(action.call());
    } catch (final Exception e) {
      result.completeExceptionally(e);
    }

    return result;
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
   * Runs {@code first} up to its held call, starts {@code second}, waits until the second one has
   * met the lock, then lets the first one go on. Both must succeed.
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

  private Callable<Object> publishing(
      final Repo repo,
      final String name,
      final String version,
      final byte[] tarball,
      final String token) {
    return () -> {
      assertThat(this.publish(repo, name, version, tarball, token).getStatus()).isEqualTo(200);
      return null;
    };
  }

  /** Makes the removal of any tarball fail, after the file system has been asked to. */
  private void failTarballRemovals() {
    doAnswer(
            invocation -> {
              final var path = invocation.<StoragePath>getArgument(0);

              if (path.getPath().endsWith(".tgz")) {
                throw new IllegalStateException("storage refused to remove " + path.getPath());
              }

              return invocation.callRealMethod();
            })
        .when(this.npmStorageStrategy)
        .delete(any());
  }

  /** Writes the metadata like the real service, then fails, as a storage that dies mid-write. */
  private void failAfterWritingTheMetadata() throws IOException {
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("storage went away after the write");
            })
        .when(this.npmStorageService)
        .writeMetadataToFile(any(), any(), any());
  }

  // -------------------------------------------------------------------------------------------
  // Unpublish (protocol)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("unpublishing the latest version moves latest, rows and metadata together")
  void unpublishOfTheLatestVersionMovesLatest() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "1.2.0");

    final var unpublished = this.unpublish(repo, name, "1.2.0");

    assertThat(unpublished).isEqualTo("1.2.0");
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.1.0");
    assertThat(this.storedTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "1.1.0");
    assertThat(this.metadataTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(tarballFile(repo, name, "1.2.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.1.0")).exists();
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
  }

  @Test
  @DisplayName("unpublishing an older version keeps latest and drops the tags pointing at it")
  void unpublishOfAnOlderVersionKeepsLatest() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    assertThat(this.tagOver(repo, name, "beta", "1.0.0", token)).isEqualTo(200);

    this.unpublish(repo, name, "1.0.0");

    assertThat(this.storedVersions(repo, name)).containsExactly("1.1.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.1.0");
    assertThat(this.storedTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(this.metadataTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
  }

  private int tagOver(
      final Repo repo,
      final String name,
      final String tag,
      final String version,
      final String token)
      throws Exception {

    final var request =
        put("/{repo}/-/package/{name}/dist-tags/{tag}", repo.getName(), name, tag)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("\"" + version + "\"");

    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse().getStatus();
  }

  @Test
  @DisplayName("unpublishing the only version deletes the package, its rows and its directory")
  void unpublishOfTheLastVersionDeletesThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");

    this.unpublish(repo, name, "1.0.0");

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(storageDirOf(repo).resolve(name)).doesNotExist();
  }

  @Test
  @DisplayName("a version delete the database refuses leaves the metadata and the tarball as is")
  void refusedUnpublishLeavesStorageUntouched() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", UNDELETABLE_VERSION, "2.0.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.refuseVersionDeletes();

    final var thrown =
        failureOf(this.attempt(() -> this.unpublish(repo, name, UNDELETABLE_VERSION)));

    assertThat(thrown).isNotNull();
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, UNDELETABLE_VERSION))
        .hasBinaryContent(tarballOf("tarball " + UNDELETABLE_VERSION));
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
  }

  @Test
  @DisplayName("a metadata write that fails puts the metadata back and keeps rows and tarball")
  void unpublishWithAFailedMetadataWriteChangesNothing() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "1.2.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.failAfterWritingTheMetadata();

    final var thrown = failureOf(this.attempt(() -> this.unpublish(repo, name, "1.2.0")));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, "1.2.0")).hasBinaryContent(tarballOf("tarball 1.2.0"));
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
    assertThat(this.storedTags(repo, name)).containsEntry("latest", "1.2.0");
  }

  @Test
  @DisplayName("a tarball that cannot be removed rolls the rows back and restores the metadata")
  void unpublishWithAFailedTarballRemovalChangesNothing() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "1.2.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.failTarballRemovals();

    final var thrown = failureOf(this.attempt(() -> this.unpublish(repo, name, "1.2.0")));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(metadataFile(repo, name))
        .as("the metadata was rewritten before the tarball, so it had to be put back")
        .hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, "1.2.0")).hasBinaryContent(tarballOf("tarball 1.2.0"));
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
    assertThat(this.storedTags(repo, name)).containsEntry("latest", "1.2.0");
  }

  @Test
  @DisplayName("a publish that waits for an unpublish keeps the unpublish's change and its own")
  void publishDuringAnUnpublishSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextMetadataWrite(reached);

    this.race(
        reached,
        gate,
        () -> this.unpublish(repo, name, "1.0.0"),
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token));

    assertThat(this.storedVersions(repo, name)).containsExactly("1.1.0", "1.2.0");
    assertThat(this.metadataVersions(repo, name))
        .as("the publish read the metadata the unpublish wrote, not the one before it")
        .containsExactlyInAnyOrder("1.1.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
    assertThat(this.metadataTags(repo, name)).containsEntry("latest", "1.2.0");
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.1.0")).exists();
    assertThat(tarballFile(repo, name, "1.2.0")).exists();
  }

  @Test
  @DisplayName("an unpublish that waits for a publish keeps the publish's version and its own")
  void unpublishDuringAPublishSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextPublish(reached);

    this.race(
        reached,
        gate,
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token),
        () -> this.unpublish(repo, name, "1.0.0"));

    assertThat(this.storedVersions(repo, name)).containsExactly("1.1.0", "1.2.0");
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.1.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.2.0")).exists();
  }

  // -------------------------------------------------------------------------------------------
  // Deprecate (protocol)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deprecating and un-deprecating a version changes the row and the metadata")
  void deprecationStoresRowAndMetadata() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");

    final var deprecated =
        this.putMetadata(
            repo, name, this.deprecationPayload(repo, name, "1.0.0", "use 1.1"), token);

    assertThat(deprecated.getStatus()).isEqualTo(200);
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.metadataDeprecation(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.metadataDeprecation(repo, name, "1.1.0")).isNull();

    final var restored =
        this.putMetadata(repo, name, this.deprecationPayload(repo, name, "1.0.0", ""), token);

    assertThat(restored.getStatus()).isEqualTo(200);
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEmpty();
    assertThat(this.metadataDeprecation(repo, name, "1.0.0"))
        .as("an empty message removes the field (RPS-1360)")
        .isNull();
  }

  @Test
  @DisplayName("a deprecation the database refuses leaves the metadata as it was")
  void refusedDeprecationLeavesTheMetadataUntouched() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    final var payload = this.deprecationPayload(repo, name, "1.0.0", REFUSED_MESSAGE);
    this.refuseDeprecation();

    final var response = this.putMetadata(repo, name, payload, token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEmpty();
  }

  @Test
  @DisplayName("a failed metadata write rolls the deprecation back and restores the metadata")
  void failedMetadataWriteRollsBackADeprecation() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    final var payload = this.deprecationPayload(repo, name, "1.0.0", "use 1.1");
    this.failAfterWritingTheMetadata();

    final var response = this.putMetadata(repo, name, payload, token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEmpty();
    assertThat(
            this.count(
                """
            select count(*) from npm_package_version v join npm_package p on p.id = v.package_id
            where p.repo_id = ? and p.name = ? and v.deprecated
            """,
                repo.getId(),
                name))
        .isZero();
  }

  @Test
  @DisplayName("a deprecation sent from a stale packument does not drop a version published since")
  void staleDeprecationKeepsAVersionPublishedSinceTheRead() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    // npm reads the packument, the user takes a while, and a publish lands before the PUT.
    final var stalePayload = this.deprecationPayload(repo, name, "1.0.0", "use 1.1");
    assertThat(this.publish(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token).getStatus())
        .isEqualTo(200);

    final var response = this.putMetadata(repo, name, stalePayload, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.metadataVersions(repo, name))
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.metadataDeprecation(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.metadataTags(repo, name)).containsEntry("latest", "1.2.0");
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEqualTo("use 1.1");
  }

  @Test
  @DisplayName("a publish that waits for a deprecation keeps the deprecation and its own version")
  void publishDuringADeprecationSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var payload = this.deprecationPayload(repo, name, "1.0.0", "use 1.1");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextMetadataWrite(reached);

    this.race(
        reached,
        gate,
        () -> {
          assertThat(this.putMetadata(repo, name, payload, token).getStatus()).isEqualTo(200);
          return null;
        },
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token));

    assertThat(this.metadataVersions(repo, name))
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.metadataDeprecation(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
  }

  @Test
  @DisplayName("a deprecation that waits for a publish keeps the publish's version")
  void deprecationDuringAPublishSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var payload = this.deprecationPayload(repo, name, "1.0.0", "use 1.1");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextPublish(reached);

    this.race(
        reached,
        gate,
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token),
        () -> {
          assertThat(this.putMetadata(repo, name, payload, token).getStatus()).isEqualTo(200);
          return null;
        });

    assertThat(this.metadataVersions(repo, name))
        .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.metadataDeprecation(repo, name, "1.0.0")).isEqualTo("use 1.1");
    assertThat(this.storedDeprecationMessage(repo, name, "1.0.0")).isEqualTo("use 1.1");
  }

  // -------------------------------------------------------------------------------------------
  // Version delete (panel)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deleting the latest version from the panel moves latest, rows and metadata")
  void panelDeleteOfTheLatestVersionMovesLatest() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "1.2.0");

    final var usages =
        this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.2.0");

    assertThat(usages.getDiskUsage()).isNegative();
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.1.0");
    assertThat(this.storedTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "1.1.0");
    assertThat(this.metadataTags(repo, name)).containsExactly(Map.entry("latest", "1.1.0"));
    assertThat(tarballFile(repo, name, "1.2.0")).doesNotExist();
  }

  @Test
  @DisplayName("deleting the only version from the panel deletes the package")
  void panelDeleteOfTheLastVersionDeletesThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");

    this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.0.0");

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(storageDirOf(repo).resolve(name)).doesNotExist();
  }

  @Test
  @DisplayName("deleting a version that does not exist changes nothing, even for a lone version")
  void panelDeleteOfAMissingVersionChangesNothing() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));

    final var thrown =
        failureOf(
            this.attempt(
                () ->
                    this.npmApiFacade.deletePackageVersion(
                        this.infoOf(repo), null, name, "9.9.9")));

    assertThat(thrown).hasMessage("packageVersionNotFound");
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0");
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
  }

  @Test
  @DisplayName("a panel version delete the database refuses leaves storage untouched")
  void refusedPanelVersionDeleteLeavesStorageUntouched() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", UNDELETABLE_VERSION, "2.0.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.refuseVersionDeletes();

    final var thrown =
        failureOf(
            this.attempt(
                () ->
                    this.npmApiFacade.deletePackageVersion(
                        this.infoOf(repo), null, name, UNDELETABLE_VERSION)));

    assertThat(thrown).isNotNull();
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, UNDELETABLE_VERSION)).exists();
    assertThat(this.storedVersionCount(repo, name)).isEqualTo(3);
  }

  @Test
  @DisplayName("a panel version delete whose tarball cannot be removed keeps rows and metadata")
  void panelVersionDeleteWithAFailedTarballRemovalChangesNothing() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0", "1.2.0");
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.failTarballRemovals();

    final var thrown =
        failureOf(
            this.attempt(
                () ->
                    this.npmApiFacade.deletePackageVersion(
                        this.infoOf(repo), null, name, "1.2.0")));

    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, "1.2.0")).exists();
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
  }

  @Test
  @DisplayName("a version whose tarball is already gone can still be deleted")
  void aVersionWithoutATarballCanBeDeleted() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    Files.delete(tarballFile(repo, name, "1.0.0"));

    this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.0.0");

    assertThat(this.storedVersions(repo, name)).containsExactly("1.1.0");
    assertThat(this.metadataVersions(repo, name)).containsExactly("1.1.0");
  }

  @Test
  @DisplayName("a publish that waits for a panel version delete keeps the change and its own")
  void publishDuringAPanelVersionDeleteSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextMetadataWrite(reached);

    this.race(
        reached,
        gate,
        () -> this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.0.0"),
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token));

    assertThat(this.storedVersions(repo, name)).containsExactly("1.1.0", "1.2.0");
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.1.0", "1.2.0");
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.2.0")).exists();
  }

  @Test
  @DisplayName("a panel version delete that waits for a publish keeps the publish's version")
  void panelVersionDeleteDuringAPublishSurvives() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextPublish(reached);

    this.race(
        reached,
        gate,
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token),
        () -> this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.1.0"));

    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.2.0");
    assertThat(this.metadataVersions(repo, name)).containsExactlyInAnyOrder("1.0.0", "1.2.0");
    assertThat(this.storedLatest(repo, name)).isEqualTo("1.2.0");
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
  }

  // -------------------------------------------------------------------------------------------
  // Package delete (panel and protocol)
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deleting a package removes its rows and its directory")
  void deletePackageRemovesRowsAndDirectory() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");

    final var usages = this.npmApiFacade.deletePackage(this.infoOf(repo), null, name);

    assertThat(usages.getDiskUsage()).isNegative();
    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(storageDirOf(repo).resolve(name)).doesNotExist();
  }

  /** Removes the package's directory behind the database's back, as a partial failure would. */
  private void loseTheDirectoryOf(final Repo repo, final String name) throws IOException {
    try (final var walk = Files.walk(storageDirOf(repo).resolve(name))) {
      walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
    assertThat(storageDirOf(repo).resolve(name)).doesNotExist();
  }

  @Test
  @DisplayName("a package whose directory is already gone can be deleted from the panel (RPS-1290)")
  void panelDeleteOfAPackageWithoutADirectory() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    this.loseTheDirectoryOf(repo, name);

    final var usages = this.npmApiFacade.deletePackage(this.infoOf(repo), null, name);

    assertThat(usages.getDiskUsage()).isZero();
    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
  }

  @Test
  @DisplayName("a package whose directory is already gone can be deleted over the protocol")
  void protocolDeleteOfAPackageWithoutADirectory() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");
    this.loseTheDirectoryOf(repo, name);

    final var response = this.deletePackageOverHttp(repo, name, token);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(this.packageCount(repo, name)).isZero();
  }

  @Test
  @DisplayName("the only version of a package whose directory is gone can be deleted")
  void panelDeleteOfTheLastVersionWithoutADirectory() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");
    this.loseTheDirectoryOf(repo, name);

    this.npmApiFacade.deletePackageVersion(this.infoOf(repo), null, name, "1.0.0");

    assertThat(this.packageCount(repo, name)).isZero();
  }

  @Test
  @DisplayName("a directory that exists but cannot be removed still rolls the package delete back")
  void existingDirectoryThatCannotBeRemovedKeepsThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0");
    doAnswer(
            invocation -> {
              throw new UncheckedIOException(new IOException("disk failure"));
            })
        .when(this.npmStorageStrategy)
        .delete(any());

    final var thrown =
        failureOf(
            this.attempt(() -> this.npmApiFacade.deletePackage(this.infoOf(repo), null, name)));

    assertThat(thrown).isNotNull();
    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
  }

  @Test
  @DisplayName("deleting a package that does not exist is a not-found")
  void deleteOfAMissingPackageIsNotFound() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();

    assertThat(this.deletePackageOverHttp(repo, "missing-" + randomTag(), token).getStatus())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("a protocol package delete the database refuses leaves the directory untouched")
  void refusedProtocolPackageDeleteLeavesStorageUntouched() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = UNDELETABLE_PACKAGE_PREFIX + randomTag();
    assertThat(this.publish(repo, name, "1.0.0", tarballOf("tarball"), token).getStatus())
        .isEqualTo(200);
    final var metadataBefore = Files.readAllBytes(metadataFile(repo, name));
    this.refusePackageDeletes();

    final var response = this.deletePackageOverHttp(repo, name, token);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(metadataFile(repo, name)).hasBinaryContent(metadataBefore);
    assertThat(tarballFile(repo, name, "1.0.0")).exists();
    assertThat(this.packageCount(repo, name)).isOne();
  }

  @Test
  @DisplayName("a directory that cannot be removed rolls the package delete back")
  void failedDirectoryRemovalKeepsThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    doAnswer(
            invocation -> {
              throw new IOException("storage refused to remove the package");
            })
        .when(this.npmStorageService)
        .deletePackage(any(), any());

    final var thrown =
        failureOf(
            this.attempt(() -> this.npmApiFacade.deletePackage(this.infoOf(repo), null, name)));

    // An IOException thrown unchecked by the storage strategy must still undo the rows.
    assertThat(thrown).isNotNull();
    assertThat(this.storedVersions(repo, name)).containsExactly("1.0.0", "1.1.0");
    assertThat(this.packageCount(repo, name)).isOne();
    assertThat(tarballFile(repo, name, "1.1.0")).exists();
  }

  @Test
  @DisplayName("a publish that waits for a package delete recreates the package, and only it")
  void publishAfterPackageDeleteRecreatesThePackage() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextPackageDelete(reached);

    this.race(
        reached,
        gate,
        () -> this.npmApiFacade.deletePackage(this.infoOf(repo), null, name),
        this.publishing(repo, name, "2.0.0", tarballOf("tarball 2.0.0"), token));

    // The delete's removal of the directory must not take the published files with it.
    assertThat(this.storedVersions(repo, name)).containsExactly("2.0.0");
    assertThat(this.metadataVersions(repo, name)).containsExactly("2.0.0");
    assertThat(tarballFile(repo, name, "2.0.0")).exists();
    assertThat(tarballFile(repo, name, "1.0.0")).doesNotExist();
    assertThat(tarballFile(repo, name, "1.1.0")).doesNotExist();
  }

  @Test
  @DisplayName("a package delete that waits for a publish removes what the publish stored")
  void packageDeleteAfterPublishRemovesThePublishedFiles() throws Exception {
    final var repo = this.npmRepo();
    final var token = this.adminToken();
    final var name = this.publishedVersions(repo, token, "1.0.0", "1.1.0");
    final var reached = new CountDownLatch(1);
    final var gate = this.holdNextPublish(reached);

    this.race(
        reached,
        gate,
        this.publishing(repo, name, "1.2.0", tarballOf("tarball 1.2.0"), token),
        () -> this.npmApiFacade.deletePackage(this.infoOf(repo), null, name));

    assertThat(this.packageCount(repo, name)).isZero();
    assertThat(this.storedVersionCount(repo, name)).isZero();
    assertThat(storageDirOf(repo).resolve(name)).doesNotExist();
  }
}
