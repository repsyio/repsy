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

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1322: two concurrent pushes that move the same existing tag to two different manifests both
 * load the {@code docker_tag} row at the same {@code @Version}; the second update finds the version
 * changed and fails with an {@code ObjectOptimisticLockingFailureException} at commit. The
 * annotation that was meant to retry it was never enabled, and inside the facade's transaction a
 * retry could not succeed anyway, so the push handler runs the whole save transaction again (as it
 * does for the unique-index race of RPS-1314), which then moves the tag to the manifest of the
 * second push: both clients get their {@code 201} and the last write wins.
 *
 * <p>The race is forced, not hoped for: both pushes are held at a barrier after they read the tag
 * and before they update it. Runs without a test transaction since both pushes have to commit; it
 * deletes the repo and the user it committed.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Concurrent moves of one Docker tag (RPS-1322)")
@Import(DockerConcurrentTagMoveIT.TagReadHook.class)
class DockerConcurrentTagMoveIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String TAG = "latest";
  private static final long TIMEOUT_SECONDS = 30;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private RepoTxService repoTxService;
  @Autowired private DockerStorageService dockerStorageService;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static final AtomicReference<Runnable> AFTER_TAG_READ = new AtomicReference<>();

  /**
   * Runs {@link #AFTER_TAG_READ} when {@code TagRepository.findByImageIdAndName} returns. A Spring
   * Data repository cannot be a {@code @MockitoSpyBean}, so the repository is wrapped in a plain
   * proxy that calls through.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class TagReadHook {

    @Bean
    static BeanPostProcessor tagReadHookProcessor() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
          if (!(bean instanceof TagRepository)) {
            return bean;
          }

          return Proxy.newProxyInstance(
              TagRepository.class.getClassLoader(),
              new Class<?>[] {TagRepository.class},
              (proxy, method, args) -> {
                final Object result;
                try {
                  result = method.invoke(bean, args);
                } catch (final InvocationTargetException e) {
                  throw e.getCause();
                }

                final var hook = AFTER_TAG_READ.get();
                if (hook != null && "findByImageIdAndName".equals(method.getName())) {
                  hook.run();
                }

                return result;
              });
        }
      };
    }
  }

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  private DockerWire wire;

  @BeforeEach
  void setUpWire() {
    final var info =
        this.userTxService.create(
            uniqueUsername("tagmove"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
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
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
    AFTER_TAG_READ.set(null);
  }

  private Repo dockerRepo() {
    final var name = uniqueRepoName("docker-move");
    final var created = this.repoTxService.createRepo(name, RepoType.DOCKER, false, null);
    this.createdRepoIds.add(created.getId());
    this.dockerStorageService.createRepo(created.getId());

    return this.repoRepository.findByName(name).orElseThrow();
  }

  /**
   * Makes the first two tag reads wait for each other, so both pushes hold the tag row at the same
   * version before either updates it. Later reads (the retry) pass.
   */
  private void holdTheFirstTwoTagReadsAtABarrier() {
    final var barrier = new CyclicBarrier(2);
    final var arrivals = new AtomicInteger();

    AFTER_TAG_READ.set(
        () -> {
          if (arrivals.getAndIncrement() < 2) {
            try {
              barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (final Exception e) {
              throw new IllegalStateException("the two pushes did not meet", e);
            }
          }
        });
  }

  private List<MockHttpServletResponse> runConcurrently(
      final Callable<MockHttpServletResponse> first, final Callable<MockHttpServletResponse> second)
      throws Exception {

    final var pool = Executors.newFixedThreadPool(2);
    try {
      final var one = pool.submit(first);
      final var two = pool.submit(second);

      return List.of(
          one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), two.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("two pushes moving one existing tag both get 201 and the tag ends on one of them")
  void twoPushesMovingOneTag() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-seed");
    assertThat(this.wire.putImage(repo, IMAGE, TAG, imageManifest("layer-seed")).getStatus())
        .isEqualTo(201);
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-two");
    final var one = imageManifest("layer-one");
    final var two = imageManifest("layer-two");
    this.holdTheFirstTwoTagReadsAtABarrier();

    final var responses =
        this.runConcurrently(
            () -> this.wire.putImage(repo, IMAGE, TAG, one),
            () -> this.wire.putImage(repo, IMAGE, TAG, two));

    assertThat(responses)
        .as("the push that lost the race is retried, not answered with an error")
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactly(201, 201);
    assertThat(this.tagDigests(repo))
        .as("one tag row, on the manifest of one of the two pushes")
        .hasSize(1)
        .containsAnyOf(sha256(bytes(one)), sha256(bytes(two)));
    assertThat(this.manifestRows(repo)).as("the two new manifests and the seed").isEqualTo(3);
  }

  /**
   * RPS-1325: a tag that keeps losing its version race (a heavily contended one) exhausts the three
   * runs of the handler, and the {@code ObjectOptimisticLockingFailureException} used to reach the
   * client as a 500. Every read of the tag is followed by another writer bumping the row's version,
   * so no run can commit. The loser now gets a 503 with {@code Retry-After} in the registry error
   * format, since Docker clients retry a 5xx and give up on a 409, and nothing of the losing push
   * stays behind. Once the contention is over the same push succeeds.
   */
  @Test
  @DisplayName(
      "a push that loses the version race on every run is answered 503, and stores nothing")
  void exhaustedRetriesAnswerARetryableRegistryError() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-seed");
    final var seed = imageManifest("layer-seed");
    assertThat(this.wire.putImage(repo, IMAGE, TAG, seed).getStatus()).isEqualTo(201);
    this.wire.pushBlobsOf(repo, IMAGE, "layer-one");
    final var one = imageManifest("layer-one");
    final var tagReads = new AtomicInteger();

    AFTER_TAG_READ.set(
        () -> {
          tagReads.incrementAndGet();
          this.jdbcTemplate.update(
              """
              update docker_tag set version = version + 1
              where name = ? and image_id in (select id from docker_image where repo_id = ?)
              """,
              TAG,
              repo.getId());
        });

    final var response = this.wire.putImage(repo, IMAGE, TAG, one);

    assertThat(response.getStatus())
        .as("answered %s", response.getContentAsString())
        .isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString())
        .as("the registry error format, not the panel envelope")
        .contains("\"errors\"", "\"code\":\"UNKNOWN\"", "concurrentModification")
        .doesNotContain("\"msgId\"");
    assertThat(tagReads).as("the handler ran the whole save three times").hasValue(3);
    assertThat(this.tagDigests(repo))
        .as("the tag still points at the manifest it had")
        .containsExactly(sha256(bytes(seed)));
    assertThat(this.manifestRows(repo)).as("the losing push stored no manifest").isEqualTo(1);

    AFTER_TAG_READ.set(null);

    assertThat(this.wire.putImage(repo, IMAGE, TAG, one).getStatus())
        .as("the same push repeated without contention")
        .isEqualTo(201);
    assertThat(this.tagDigests(repo)).containsExactly(sha256(bytes(one)));
  }

  private List<String> tagDigests(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select t.digest from docker_tag t
          join docker_image i on i.id = t.image_id
        where i.repo_id = ? and t.name = ?
        """,
        String.class,
        repo.getId(),
        TAG);
  }

  private int manifestRows(final Repo repo) {
    final var count =
        this.jdbcTemplate.queryForObject(
            """
            select count(*) from docker_manifest m
              join docker_image i on i.id = m.image_id
            where i.repo_id = ?
            """,
            Integer.class,
            repo.getId());

    return count == null ? 0 : count;
  }
}
