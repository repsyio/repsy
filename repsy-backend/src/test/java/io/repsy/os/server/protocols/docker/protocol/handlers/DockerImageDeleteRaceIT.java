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

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.bytes;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * RPS-1288: an image goes with its last manifest, and a push that meets that delete must not fail.
 *
 * <p>The push handler looks the image up (or creates it) in one transaction and saves the manifest
 * in another, so the image can be deleted in between; and a delete of the last manifest counts what
 * the image has while a push may be writing to it. The three interleavings are forced, not hoped
 * for, with hooks on the two services the requests pass through (the same idiom as {@code
 * DockerConcurrentFirstPushIT}), and a stress test runs many of them at random.
 *
 * <p>Runs without a test transaction since the requests have to commit and meet in the database; it
 * deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Push and delete of the last manifest of an image (RPS-1288)")
class DockerImageDeleteRaceIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final long TIMEOUT_SECONDS = 60;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private ImageTxService imageTxService;
  @MockitoSpyBean private ManifestTxService manifestTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<ExecutorService> pools = new ArrayList<>();

  private DockerWire wire;
  private String protocolToken;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(uniqueUsername("imagerace"), UserRole.ADMIN, VALID_PASSWORD_HASH);
    this.createdUserIds.add(info.getId());
    final var user = this.userRepository.findById(info.getId()).orElseThrow();
    this.protocolToken = this.protocolBearerTokenFor(user);
    this.wire =
        new DockerWire(
            this.mockMvc, this.webApplicationContext, protocolPort(), this.protocolToken);
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
    final var name = uniqueRepoName("docker-race");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private ExecutorService pool(final int threads) {
    final var pool = Executors.newFixedThreadPool(threads);
    this.pools.add(pool);

    return pool;
  }

  private MockHttpServletResponse deleteReference(final Repo repo, final String reference)
      throws Exception {

    return this.mockMvc
        .perform(
            delete("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header(AUTHORIZATION, this.protocolToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse push(final Repo repo, final String tag, final String layer)
      throws Exception {

    return this.wire.putImage(repo, IMAGE, tag, imageManifest(layer));
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

  private <T> T onAnotherThread(final Callable<T> work) throws Exception {
    return this.pool(1).submit(work).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
  @DisplayName("the image is deleted after the push looked it up: the push creates it again")
  void theImageIsDeletedBetweenTheLookupAndTheSave() throws Exception {
    final var repo = this.dockerRepo();
    final var seeded = imageManifest("layer-seed");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-seed");
    assertThat(this.wire.putImage(repo, IMAGE, "seed", seeded).getStatus()).isEqualTo(201);
    this.wire.pushBlobsOf(repo, IMAGE, "layer-new");
    final var firstImageId = this.jdbcImageId(repo);

    // The lookup of the push below finds the image, and then the last manifest of the image is
    // deleted by digest (by another request, on another thread), which deletes the image before the
    // push has written anything.
    final var lookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              final var found = invocation.callRealMethod();

              if (lookups.getAndIncrement() == 0) {
                assertThat(
                        this.onAnotherThread(
                                () -> this.deleteReference(repo, sha256(bytes(seeded))))
                            .getStatus())
                    .isEqualTo(202);
                assertThat(this.imageNames(repo)).as("the last manifest took the image").isEmpty();
              }

              return found;
            })
        .when(this.imageTxService)
        .findOrCreateImage(any(), eq(IMAGE));

    final var response = this.push(repo, "new", "layer-new");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    assertThat(lookups.get()).as("the lookup ran again after the image was deleted").isEqualTo(2);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.jdbcImageId(repo))
        .as("a new row, not the deleted one")
        .isNotEqualTo(firstImageId);
    assertThat(this.tagNames(repo)).containsExactly("new");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
    assertThat(this.wire.getManifest(repo, IMAGE, "new").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("an image that never got a manifest is deleted under the push")
  void anEmptyImageIsDeletedBetweenTheLookupAndTheSave() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    // What a push that failed after it created the image leaves behind: an image with no manifest.
    final var emptyImageId = this.imageTxService.findOrCreateImage(repo.getId(), IMAGE).getId();

    // The lookup of the push finds it, and then the cleanup deletes it (it has no manifest).
    final var lookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              final var found = invocation.callRealMethod();

              if (lookups.getAndIncrement() == 0) {
                assertThat(
                        this.onAnotherThread(
                            () ->
                                this.imageTxService.deleteImageIfEmpty(repo.getId(), emptyImageId)))
                    .isTrue();
              }

              return found;
            })
        .when(this.imageTxService)
        .findOrCreateImage(any(), eq(IMAGE));

    final var response = this.push(repo, "latest", "layer-one");

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    assertThat(lookups.get()).isEqualTo(2);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.jdbcImageId(repo)).isNotEqualTo(emptyImageId);
    assertThat(this.tagNames(repo)).containsExactly("latest");
  }

  @Test
  @DisplayName("a push that arrives while the last manifest is being deleted waits, then wins")
  void aPushWaitsForTheDeleteThatHoldsTheImage() throws Exception {
    final var repo = this.dockerRepo();
    final var seeded = imageManifest("layer-seed");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-seed");
    assertThat(this.wire.putImage(repo, IMAGE, "seed", seeded).getStatus()).isEqualTo(201);
    this.wire.pushBlobsOf(repo, IMAGE, "layer-new");

    // The delete holds the image row (its transaction is open) until the push is blocked on it.
    final var deleteHoldsTheImage = new CountDownLatch(1);
    final var pushIsBlocked = new CountDownLatch(1);
    final var held = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (held.getAndIncrement() == 0) {
                deleteHoldsTheImage.countDown();
                assertThat(pushIsBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              }

              return invocation.callRealMethod();
            })
        .when(this.imageTxService)
        .deleteImageIfEmpty(any(), any());

    final var pool = this.pool(2);
    final Future<MockHttpServletResponse> deletion =
        pool.submit(() -> this.deleteReference(repo, sha256(bytes(seeded))));
    assertThat(deleteHoldsTheImage.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    final Future<MockHttpServletResponse> push =
        pool.submit(() -> this.push(repo, "new", "layer-new"));
    this.awaitABlockedBackend();
    pushIsBlocked.countDown();

    assertThat(deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(202);
    final var pushed = push.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
    assertThat(this.imageNames(repo)).containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("new");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a delete of the last manifest waits for a push that is writing, and finds its manifest")
  void aDeleteWaitsForThePushThatHoldsTheImage() throws Exception {
    final var repo = this.dockerRepo();
    final var seeded = imageManifest("layer-seed");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-seed");
    assertThat(this.wire.putImage(repo, IMAGE, "seed", seeded).getStatus()).isEqualTo(201);
    this.wire.pushBlobsOf(repo, IMAGE, "layer-new");

    // The push has locked the image and queued its manifest (its transaction is open) and holds
    // there until the delete is blocked on the image.
    final var pushHoldsTheImage = new CountDownLatch(1);
    final var deleteIsBlocked = new CountDownLatch(1);
    final var held = new AtomicInteger();
    doAnswer(
            invocation -> {
              final var result = invocation.callRealMethod();

              if (held.getAndIncrement() == 0) {
                pushHoldsTheImage.countDown();
                assertThat(deleteIsBlocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              }

              return result;
            })
        .when(this.manifestTxService)
        .createSinglePlatformManifest(any(), any(), any());

    final var pool = this.pool(2);
    final Future<MockHttpServletResponse> push =
        pool.submit(() -> this.push(repo, "new", "layer-new"));
    assertThat(pushHoldsTheImage.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    final Future<MockHttpServletResponse> deletion =
        pool.submit(() -> this.deleteReference(repo, sha256(bytes(seeded))));
    this.awaitABlockedBackend();
    deleteIsBlocked.countDown();

    final var pushed = push.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(pushed.getStatus()).as(pushed.getContentAsString()).isEqualTo(201);
    assertThat(deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(202);
    assertThat(this.imageNames(repo))
        .as("the pushed manifest is counted: the image stays")
        .containsExactly(IMAGE);
    assertThat(this.tagNames(repo)).containsExactly("new");
    assertThat(this.manifestRows(repo)).isEqualTo(1);
  }

  @Test
  @DisplayName("many pushes and deletes of manifests of one image never fail and lose nothing")
  void manyPushesAndDeletesOfOneImage() throws Exception {
    final var repo = this.dockerRepo();
    final var workers = 6;
    final var rounds = 12;

    // Blobs first, from this thread: the shared config blob is not safe to upload twice at once.
    for (var worker = 0; worker < workers; worker++) {
      this.wire.pushBlobsOf(repo, IMAGE, "layer-" + worker);
    }

    // Each worker pushes a manifest of its own under a tag of its own and deletes it by digest, so
    // the image is created and deleted over and over while the others' manifests keep it alive
    // only some of the time. Every push must answer 201, and every delete must find its manifest.
    final var pool = this.pool(workers);
    final var failures = new AtomicReference<List<String>>(new ArrayList<>());
    final var start = new CountDownLatch(1);
    final var tasks = new ArrayList<Callable<Void>>();

    for (var worker = 0; worker < workers; worker++) {
      final var layer = "layer-" + worker;
      final var digest = sha256(bytes(imageManifest(layer)));

      tasks.add(
          () -> {
            start.await();

            for (var round = 0; round < rounds; round++) {
              final var pushed = this.push(repo, "tag-" + layer, layer);
              final var deleted = this.deleteReference(repo, digest);

              if (pushed.getStatus() != 201 || deleted.getStatus() != 202) {
                synchronized (failures) {
                  failures
                      .get()
                      .add(
                          "%s round %d: push %d %s, delete %d %s"
                              .formatted(
                                  layer,
                                  round,
                                  pushed.getStatus(),
                                  pushed.getContentAsString(),
                                  deleted.getStatus(),
                                  deleted.getContentAsString()));
                }
              }
            }

            return null;
          });
    }

    final var futures = new ArrayList<Future<Void>>();
    tasks.forEach(task -> futures.add(pool.submit(task)));
    start.countDown();
    for (final var future : futures) {
      future.get(TIMEOUT_SECONDS * 4, TimeUnit.SECONDS);
    }

    assertThat(failures.get()).isEmpty();
    assertThat(this.imageNames(repo)).as("everything was deleted, so the image is").isEmpty();
    assertThat(this.manifestRows(repo)).isZero();
    assertThat(this.tagNames(repo)).isEmpty();
  }

  private UUID jdbcImageId(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select id from docker_image where repo_id = ? and name = ?",
        UUID.class,
        repo.getId(),
        IMAGE);
  }
}
