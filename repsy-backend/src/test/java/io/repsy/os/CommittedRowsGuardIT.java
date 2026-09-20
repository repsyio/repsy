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
package io.repsy.os;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1011: {@link CommittedRowsGuard} fails the class that leaves committed rows behind, and only
 * that class.
 *
 * <p>The fixture classes below are real {@link AbstractIntegrationTest} subclasses, so the guard is
 * wired in exactly as for every other suite. They are run through the JUnit engine from the tests,
 * and the {@code $} in their binary names keeps Surefire and Failsafe from running them on their
 * own.
 *
 * <p>This class doesn't run in a test transaction: the fixtures commit, and the counts it compares
 * must see their rows. Its own guard doubles as a check that the cleanup left the database as it
 * was.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Committed-rows guard")
class CommittedRowsGuardIT extends AbstractIntegrationTest {

  @Test
  @DisplayName("fails the class that leaves a user and a repo behind, naming both tables")
  void failsTheLeakingClass() {
    final var usersBefore = this.userRepository.count();
    final var reposBefore = this.repoRepository.count();

    final var results = run(LeakingFixture.class);

    assertThat(results.testEvents().succeeded().count())
        .as("the leaking test itself passes, the class is what fails")
        .isEqualTo(1);

    final var failure = single(containerFailures(results));

    assertThat(failure)
        .contains(LeakingFixture.class.getName())
        .contains("users: %d -> %d (+1)".formatted(usersBefore, usersBefore + 1))
        .contains("repo: %d -> %d (+1)".formatted(reposBefore, reposBefore + 1))
        .contains("later classes are not affected");
  }

  @Test
  @DisplayName("removes what the leaking class added, so a later class is not affected")
  void removesTheLeakedRows() {
    final var usersBefore = this.userRepository.count();
    final var reposBefore = this.repoRepository.count();

    run(LeakingFixture.class);

    assertThat(this.userRepository.count()).isEqualTo(usersBefore);
    assertThat(this.repoRepository.count()).isEqualTo(reposBefore);

    final var later = run(CleanFixture.class);

    assertThat(later.testEvents().succeeded().count()).isEqualTo(1);
    assertThat(containerFailures(later)).isEmpty();
  }

  @Test
  @DisplayName("passes a class that deletes what it commits")
  void passesAClassThatCleansUp() {
    final var results = run(CleanFixture.class);

    assertThat(results.testEvents().succeeded().count()).isEqualTo(1);
    assertThat(containerFailures(results)).isEmpty();
  }

  @Test
  @DisplayName("checks a class with @Nested classes as a whole, after its own @AfterAll")
  void checksNestedClassesAsPartOfTheOuterClass() {
    final var results = run(NestedFixture.class);

    assertThat(results.testEvents().succeeded().count()).isEqualTo(1);
    assertThat(containerFailures(results)).isEmpty();
  }

  @Test
  @DisplayName("passes a class whose rows are rolled back with the test transaction")
  void passesARolledBackClass() {
    final var results = run(RolledBackFixture.class);

    assertThat(results.testEvents().succeeded().count()).isEqualTo(1);
    assertThat(containerFailures(results)).isEmpty();
  }

  private static EngineExecutionResults run(final Class<?> fixture) {
    return EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
  }

  /** The messages of the failed containers, which is where a failing {@code afterAll} lands. */
  private static List<String> containerFailures(final EngineExecutionResults results) {
    return results.containerEvents().failed().stream()
        .map(event -> event.getRequiredPayload(TestExecutionResult.class))
        .map(result -> result.getThrowable().orElseThrow().getMessage())
        .toList();
  }

  private static String single(final List<String> messages) {
    assertThat(messages).hasSize(1);

    return messages.getFirst();
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures: what a suite of the shared database might do
  // ---------------------------------------------------------------------------------------------

  private static UUID commitUser(final AbstractIntegrationTest test) {
    return test.userTxService
        .create(uniqueUsername("guard"), UserRole.USER, PasswordHasher.hash(VALID_PASSWORD))
        .getId();
  }

  private static Repo commitRepo(final AbstractIntegrationTest test) {
    final var repo = new Repo();
    repo.setName(uniqueRepoName("guard"));
    repo.setType(RepoType.MAVEN);
    repo.setAllowOverride(true);
    repo.setSearchable(true);

    return test.repoRepository.saveAndFlush(repo);
  }

  /** Commits a user and a repo and forgets to delete them. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  static class LeakingFixture extends AbstractIntegrationTest {

    @Test
    void commitsAndForgetsToClean() {
      commitUser(this);
      commitRepo(this);
    }
  }

  /** Commits a user and a repo and deletes both again. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  static class CleanFixture extends AbstractIntegrationTest {

    @Test
    void commitsAndCleansUp() {
      final var userId = commitUser(this);
      final var repo = commitRepo(this);

      this.repoRepository.deleteById(repo.getId());
      this.userRepository.deleteById(userId);
    }
  }

  /** Commits in a {@code @Nested} class and deletes it all once, in the outer {@code @AfterAll}. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  static class NestedFixture extends AbstractIntegrationTest {

    private static final List<UUID> COMMITTED_USERS = new CopyOnWriteArrayList<>();

    @AfterAll
    static void deleteCommitted(@Autowired final UserRepository users) {
      users.deleteAllById(COMMITTED_USERS);
      COMMITTED_USERS.clear();
    }

    @Nested
    class Inner {

      @Test
      void commitsAndLeavesTheCleanupToTheOuterClass() {
        COMMITTED_USERS.add(commitUser(NestedFixture.this));
      }
    }
  }

  /** Runs in the default per-test transaction, which is rolled back. */
  static class RolledBackFixture extends AbstractIntegrationTest {

    @Test
    void createsRowsInsideTheTestTransaction() {
      this.createUser(uniqueUsername("guard"), UserRole.USER);
      this.seedRepo(RepoType.NPM, uniqueRepoName("guard"));
    }
  }
}
