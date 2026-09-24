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
package io.repsy.os.shared.repo.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1134: two concurrent panel API calls that race for the same repository name.
 *
 * <p>{@code RepoTxService#createRepo}/{@code #renameRepo} guard the name with a check-then-write
 * ({@code existsByName} followed by an insert/update): both concurrent callers can pass the check,
 * and the loser then fails on the {@code ux_repo__name} unique index at commit. This class proves,
 * through real concurrent HTTP requests against a real Postgres container (not a single mocked
 * exception), what the panel API caller actually gets for the loser: a status code and a msgId.
 *
 * <p>Not transactional like most IT classes: each worker thread must run its request in its own,
 * really-committed transaction for the database to see two genuinely concurrent writers. Fixtures
 * are therefore committed for real and {@link #cleanUp()} removes exactly what the test created,
 * following the pattern in {@code UsageControllerIT}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("RepoTxService: concurrent create/rename to the same name (RPS-1134)")
class RepoNameRaceIT extends AbstractIntegrationTest {

  @Autowired private RepoTxService repoTxService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    this.repoRepository.deleteAllById(this.createdRepoIds);
    this.userRepository.deleteAllById(this.createdUserIds);
  }

  /**
   * Creates and commits an ADMIN, without the flush {@link AbstractIntegrationTest#createUser}
   * does: that flush needs an active transaction, and this class runs with none bound to the
   * calling thread ({@link Propagation#NOT_SUPPORTED}).
   */
  private String committedAdminToken() {
    final var username = uniqueUsername("race");
    final var hash = PasswordHasher.hash(VALID_PASSWORD);
    final var userInfo = this.userTxService.create(username, UserRole.ADMIN, hash);
    this.createdUserIds.add(userInfo.getId());
    return this.bearerTokenFor(userInfo.getId(), username);
  }

  /**
   * Runs both requests from separate threads, released at the same instant by a barrier, and
   * returns their results in submission order (not completion order).
   */
  private List<MvcResult> runConcurrently(
      final Callable<MvcResult> first, final Callable<MvcResult> second) throws Exception {

    final var barrier = new CyclicBarrier(2);
    final ExecutorService pool = Executors.newFixedThreadPool(2);

    try {
      final Callable<MvcResult> synced1 =
          () -> {
            barrier.await();
            return first.call();
          };
      final Callable<MvcResult> synced2 =
          () -> {
            barrier.await();
            return second.call();
          };

      final Future<MvcResult> future1 = pool.submit(synced1);
      final Future<MvcResult> future2 = pool.submit(synced2);

      return List.of(future1.get(30, TimeUnit.SECONDS), future2.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private static int statusOf(final MvcResult result) {
    return result.getResponse().getStatus();
  }

  private static String msgIdOf(final MvcResult result) throws Exception {
    final Map<String, Object> envelope =
        JsonPath.read(result.getResponse().getContentAsString(), "$");
    return (String) envelope.get("msgId");
  }

  @Test
  @DisplayName(
      "two concurrent creates of the same name: one succeeds, the other gets 409 repoExists, one"
          + " row")
  void concurrentCreateSameName() throws Exception {
    final var token = this.committedAdminToken();
    final var name = uniqueRepoName("race");
    final var body = "{\"name\":\"%s\",\"type\":\"MAVEN\"}".formatted(name);

    final Callable<MvcResult> create =
        () ->
            this.perform(
                    post("/api/repos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(AUTHORIZATION, token))
                .andReturn();

    final var results = this.runConcurrently(create, create);

    try {
      final var statuses = results.stream().map(RepoNameRaceIT::statusOf).sorted().toList();
      assertThat(statuses)
          .as("one winner (200) and one loser (409), never a 500")
          .containsExactly(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());

      final var loser =
          results.stream()
              .filter(result -> statusOf(result) == HttpStatus.CONFLICT.value())
              .findFirst()
              .orElseThrow();
      assertThat(msgIdOf(loser)).as("loser's msgId").isEqualTo("repoExists");
    } finally {
      this.repoRepository.findByName(name).ifPresent(repo -> this.createdRepoIds.add(repo.getId()));
    }

    assertThat(this.createdRepoIds).as("exactly one row for the contested name").hasSize(1);
  }

  @Test
  @DisplayName("two concurrent renames to the same target name: one succeeds, the other gets 409")
  void concurrentRenameToSameName() throws Exception {
    final var token = this.committedAdminToken();
    final var repoA =
        this.repoTxService.createRepo(uniqueRepoName("race-a"), RepoType.MAVEN, false, null);
    final var repoB =
        this.repoTxService.createRepo(uniqueRepoName("race-b"), RepoType.NPM, false, null);
    this.createdRepoIds.add(repoA.getId());
    this.createdRepoIds.add(repoB.getId());

    final var target = uniqueRepoName("race-target");
    final var body = "{\"name\":\"%s\"}".formatted(target);

    final Callable<MvcResult> renameA =
        () ->
            this.perform(
                    patch("/api/repos/" + repoA.getName() + "/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(AUTHORIZATION, token))
                .andReturn();
    final Callable<MvcResult> renameB =
        () ->
            this.perform(
                    patch("/api/repos/" + repoB.getName() + "/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(AUTHORIZATION, token))
                .andReturn();

    final var results = this.runConcurrently(renameA, renameB);

    final var statuses = results.stream().map(RepoNameRaceIT::statusOf).sorted().toList();
    assertThat(statuses)
        .as("one winner (200) and one loser (409), never a 500")
        .containsExactly(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());

    final var loser =
        results.stream()
            .filter(result -> statusOf(result) == HttpStatus.CONFLICT.value())
            .findFirst()
            .orElseThrow();
    assertThat(msgIdOf(loser)).as("loser's msgId").isEqualTo("repoExists");

    assertThat(this.repoRepository.findByName(target))
        .as("the target name now resolves")
        .isPresent();
  }
}
