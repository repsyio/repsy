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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.index;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1350: a manifest push that fails must not leave an image that stores no manifest.
 *
 * <p>The push creates the image in the same transaction that saves the manifest, so a failure
 * anywhere after the image was created (a layer that is not stored, an index of unknown manifests,
 * a database or storage error) rolls the image back with everything else. The concurrent cases are
 * forced, not hoped for: a push that holds a new, uncommitted image while a second push to the same
 * image waits for it, and then either fails or succeeds.
 *
 * <p>Runs without a test transaction since the requests have to commit and meet in the database; it
 * deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A failed Docker manifest push leaves no image without a manifest (RPS-1350)")
class DockerFailedFirstPushIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final long TIMEOUT_SECONDS = 60;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @MockitoBean private UsageUpdateService usageUpdateService;
  // The same overrides as DockerImageDeleteRaceIT, so the two share one Spring context.
  @MockitoSpyBean private ImageTxService imageTxService;
  @MockitoSpyBean private ManifestTxService manifestTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<ExecutorService> pools = new ArrayList<>();

  private DockerWire wire;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(
            uniqueUsername("failedpush"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.protocolBearerTokenFor(user));
  }

  @AfterEach
  void deleteCommittedData() {
    this.pools.forEach(ExecutorService::shutdownNow);
    this.pools.clear();
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-failed");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private List<String> imageNames(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        "select name from docker_image where repo_id = ?", String.class, repo.getId());
  }

  private List<String> tagNames(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select t.name from docker_tag t join docker_image i on i.id = t.image_id
        where i.repo_id = ? order by t.name
        """,
        String.class,
        repo.getId());
  }

  private int manifestRows(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from docker_manifest m join docker_image i on i.id = m.image_id
            where i.repo_id = ?
            """,
            Integer.class,
            repo.getId());

    return count == null ? 0 : count;
  }

  private ExecutorService pool(final int threads) {
    final var pool = Executors.newFixedThreadPool(threads);
    this.pools.add(pool);

    return pool;
  }

  /** Waits until some backend is blocked on a row lock, i.e. the two requests have met. */
  private void awaitABlockedBackend() throws InterruptedException {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

    while (System.nanoTime() < deadline) {
      final var waiting =
          this.jdbcTemplate.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
              Integer.class);

      if (waiting != null && waiting > 0) {
        return;
      }

      Thread.sleep(20);
    }

    throw new IllegalStateException("no request ever waited for the other one's lock");
  }

  @Test
  @DisplayName("a manifest that references a layer the repo does not have leaves no image")
  void aMissingLayerLeavesNoImage() throws Exception {
    final var repo = this.dockerRepo();

    final var response = this.wire.putImage(repo, IMAGE, "latest", imageManifest("layer-missing"));

    assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(400, 499);
    assertThat(this.imageNames(repo)).isEmpty();
    assertThat(this.manifestRows(repo)).isZero();
  }

  @Test
  @DisplayName("an index of manifests the image does not have leaves no image")
  void anIndexOfUnknownManifestsLeavesNoImage() throws Exception {
    final var repo = this.dockerRepo();

    final var response =
        this.wire.putManifest(
            repo, IMAGE, "latest", DockerWire.OCI_INDEX, index(imageManifest("layer-unknown")));

    assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(400, 499);
    assertThat(this.imageNames(repo)).isEmpty();
  }

  @Test
  @DisplayName("a failure after the image was created and locked rolls the image back")
  void aLateFailureLeavesNoImage() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");

    // The image row exists in the transaction and is share-locked when the save fails, as it would
    // be by a database or storage error while the manifest is written.
    final var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("the manifest cannot be stored");
              }

              return invocation.callRealMethod();
            })
        .when(this.manifestTxService)
        .createSinglePlatformManifest(any(), any(), any());

    final var failed = this.wire.putImage(repo, IMAGE, "latest", imageManifest("layer-one"));

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isGreaterThanOrEqualTo(500);
    verify(this.imageTxService).findOrCreateImage(repo.getId(), IMAGE);
    assertThat(this.imageNames(repo)).as("no image without a manifest").isEmpty();

    final var retried = this.wire.putImage(repo, IMAGE, "latest", imageManifest("layer-one"));

    assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(201);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("latest");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("a failed push into an image that has manifests keeps the image and its manifests")
  void aFailedPushKeepsAnExistingImage() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    assertThat(this.wire.putImage(repo, IMAGE, "one", imageManifest("layer-one")).getStatus())
        .isEqualTo(201);

    final var failed = this.wire.putImage(repo, IMAGE, "two", imageManifest("layer-missing"));

    assertThat(failed.getStatus()).as(failed.getContentAsString()).isBetween(400, 499);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("one");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a push that waits for another push's new image gets it when that push fails, and no image"
          + " is left by the failed one")
  void aPushWaitsForAFailingFirstPushAndCreatesTheImageItself() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-a");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-b");

    final var aHoldsTheImage = new CountDownLatch(1);
    final var bIsBlocked = new CountDownLatch(1);
    final var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                aHoldsTheImage.countDown();
                assertThat(bIsBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("the manifest cannot be stored");
              }

              return invocation.callRealMethod();
            })
        .when(this.manifestTxService)
        .createSinglePlatformManifest(any(), any(), any());

    final var pool = this.pool(2);
    final Future<MockHttpServletResponse> a =
        pool.submit(() -> this.wire.putImage(repo, IMAGE, "a", imageManifest("layer-a")));
    assertThat(aHoldsTheImage.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    final Future<MockHttpServletResponse> b =
        pool.submit(() -> this.wire.putImage(repo, IMAGE, "b", imageManifest("layer-b")));
    this.awaitABlockedBackend();
    bIsBlocked.countDown();

    assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isGreaterThanOrEqualTo(500);
    final var pushed = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("b");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.wire.getManifest(repo, IMAGE, "b").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("two first pushes into a new image both succeed and share the one image")
  void twoFirstPushesShareTheNewImage() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-a");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-b");

    final var aHoldsTheImage = new CountDownLatch(1);
    final var bIsBlocked = new CountDownLatch(1);
    final var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.getAndIncrement() == 0) {
                aHoldsTheImage.countDown();
                assertThat(bIsBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              }

              return invocation.callRealMethod();
            })
        .when(this.manifestTxService)
        .createSinglePlatformManifest(any(), any(), any());

    final var pool = this.pool(2);
    final Future<MockHttpServletResponse> a =
        pool.submit(() -> this.wire.putImage(repo, IMAGE, "a", imageManifest("layer-a")));
    assertThat(aHoldsTheImage.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    final Future<MockHttpServletResponse> b =
        pool.submit(() -> this.wire.putImage(repo, IMAGE, "b", imageManifest("layer-b")));
    this.awaitABlockedBackend();
    bIsBlocked.countDown();

    final var first = a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final var second = b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
    assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(201);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("a", "b");
    assertThat(this.manifestRows(repo)).isEqualTo(2);
  }
}
