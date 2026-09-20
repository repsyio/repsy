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
package io.repsy.os.shared.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.RepsyApplication;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins what the {@code @Async} per-protocol {@code *AuthListener}s do when a {@code
 * UserCreatedEvent} is published: one default repository per {@link RepoType}, private, without
 * usage and with its storage directory created, and a repeated event changing none of that.
 *
 * <p>Since every {@code AbstractIntegrationTest} class shares one PostgreSQL database (RPS-941),
 * the default repositories already exist there and repo names are unique ({@code ux_repo__name}),
 * so those classes can neither wipe the {@code repo} table nor re-publish the event. This class is
 * therefore the documented exception to "one container, owned by {@code AbstractIntegrationTest}":
 * it boots a context of its own on a dedicated, initially empty {@code postgres:18} and a storage
 * root of its own, so nothing another class leaves behind can change the outcome, and it may empty
 * the {@code repo} table freely. {@link DirtiesContext} closes that context together with its
 * container.
 *
 * <p>Startup already published the event once for the {@code admin} user, so every test first waits
 * for that seeding to finish, empties the table and publishes the event itself. The listeners are
 * {@code @Async}, so the class is not transactional: only committed rows are visible to them.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("UserCreatedEvent default repository seeding")
class DefaultRepoSeedingIT {

  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
  private static final int TYPE_COUNT = RepoType.values().length;

  /** The name each protocol's listener gives its default repository. */
  private static final Map<RepoType, String> DEFAULT_NAMES = defaultNames();

  @Container @ServiceConnection
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18")
          .withDatabaseName("repsy")
          .withUsername("repsy")
          .withPassword("repsy123");

  private static final Path STORAGE_ROOT = createStorageRoot();

  @DynamicPropertySource
  static void registerStorageProperties(final DynamicPropertyRegistry registry) {
    registry.add("storage-gateway.fs.base-path", STORAGE_ROOT::toString);
  }

  @Autowired private RepoRepository repoRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;

  private final ListAppender<ILoggingEvent> asyncFailures = new ListAppender<>();
  private Logger asyncFailureLogger;

  @BeforeEach
  void startFromFreshlySeededEmptyTable() {
    // The startup event of AdminUserInitializer is still being handled on other threads until every
    // type has a repo; deleting earlier would race a listener that has not committed yet.
    this.awaitRepoCount(TYPE_COUNT);
    this.repoRepository.deleteAllInBatch();
    assertThat(this.repoRepository.count()).isZero();

    this.asyncFailureLogger =
        (Logger) LoggerFactory.getLogger(SimpleAsyncUncaughtExceptionHandler.class);
    this.asyncFailures.start();
    this.asyncFailureLogger.addAppender(this.asyncFailures);

    this.publishUserCreated();
    this.awaitRepoCount(TYPE_COUNT);
  }

  @AfterEach
  void detachAppender() {
    this.asyncFailureLogger.detachAppender(this.asyncFailures);
    this.asyncFailures.stop();
  }

  private static Map<RepoType, String> defaultNames() {
    final var names = new EnumMap<RepoType, String>(RepoType.class);
    names.put(RepoType.MAVEN, "maven");
    names.put(RepoType.NPM, "npm");
    names.put(RepoType.PYPI, "pypi");
    names.put(RepoType.DOCKER, "docker");
    names.put(RepoType.CARGO, "cargo");
    names.put(RepoType.GOLANG, "go");
    names.put(RepoType.HELM, "helm");
    names.put(RepoType.NUGET, "nuget");
    names.put(RepoType.RUBY, "ruby");
    return names;
  }

  private static Path createStorageRoot() {
    try {
      return Files.createTempDirectory("repsy-it-default-repos");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void publishUserCreated() {
    this.eventPublisher.publishEvent(new UserCreatedEvent<>(UUID.randomUUID(), "seeded-user"));
  }

  private void awaitRepoCount(final long expected) {
    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.repoRepository.count()).isEqualTo(expected));
  }

  private Repo defaultRepoOf(final RepoType type) {
    final var repos =
        this.repoRepository.findAll().stream().filter(repo -> repo.getType() == type).toList();

    assertThat(repos).as("default repositories of type %s", type).hasSize(1);
    return repos.getFirst();
  }

  /** Storage directory of a repo: {@code <root>/<protocol dir>/<repo id>}. */
  private static Path storageDirOf(final Repo repo) {
    final var protocolDir =
        repo.getType() == RepoType.GOLANG
            ? "golang"
            : repo.getType().name().toLowerCase(Locale.ROOT);

    return STORAGE_ROOT.resolve(protocolDir).resolve(repo.getId().toString());
  }

  @Test
  @DisplayName("creates exactly one default repository for every repository type")
  void oneRepositoryPerType() {
    final var repos = this.repoRepository.findAll();

    assertThat(repos).hasSize(TYPE_COUNT);
    assertThat(repos.stream().map(Repo::getType).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(RepoType.values());
    assertThat(repos.stream().map(Repo::getName).collect(Collectors.toSet())).hasSize(TYPE_COUNT);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(RepoType.class)
  @DisplayName("creates a private, empty default repository with its storage directory")
  void defaultRepository(final RepoType type) {
    final var repo = this.defaultRepoOf(type);

    assertThat(repo.getName()).isEqualTo(DEFAULT_NAMES.get(type));
    assertThat(repo.isPrivateRepo()).as("private").isTrue();
    assertThat(repo.getDiskUsage()).as("disk usage").isZero();
    assertThat(repo.getDescription()).as("description").isNull();

    // The listener creates the directory after the row has been committed.
    await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> assertThat(storageDirOf(repo)).isDirectory());
  }

  @Test
  @DisplayName("a repeated event neither duplicates nor changes the existing repositories")
  void repeatedEventIsHarmless() {
    final var before =
        this.repoRepository.findAll().stream().collect(Collectors.toMap(Repo::getId, repo -> repo));

    assertThatCode(this::publishUserCreated).doesNotThrowAnyException();

    // The listeners run asynchronously and a failure only shows up in the log, so wait for each of
    // them to have reported one instead of sleeping.
    await()
        .atMost(ASYNC_TIMEOUT)
        .untilAsserted(() -> assertThat(this.asyncFailures.list).hasSize(TYPE_COUNT));

    assertThat(this.asyncFailedWith(ItemAlreadyExistException.class)).isEqualTo(TYPE_COUNT);

    final var after = this.repoRepository.findAll();
    assertThat(after).hasSize(TYPE_COUNT);
    assertThat(after.stream().map(Repo::getId).collect(Collectors.toSet()))
        .isEqualTo(before.keySet());
    assertThat(after).allSatisfy(repo -> assertThat(repo).isEqualTo(before.get(repo.getId())));
  }

  private long asyncFailedWith(final Class<? extends Throwable> type) {
    return this.asyncFailures.list.stream()
        .filter(
            event ->
                event.getLevel() == Level.ERROR
                    && event.getThrowableProxy() != null
                    && type.getName().equals(event.getThrowableProxy().getClassName()))
        .count();
  }
}
