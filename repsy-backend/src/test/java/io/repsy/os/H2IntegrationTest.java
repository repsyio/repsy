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
import static org.awaitility.Awaitility.await;

import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Full application context backed by the embedded H2 database and its Flyway migrations. */
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
public abstract class H2IntegrationTest {

  /**
   * The URL of the packaged H2 profile (the README's and the Docker image's {@code DB_URL}) with an
   * in-memory database. It must not carry {@code DATABASE_TO_LOWER}: the migrations create the
   * tables with quoted lower-case names, which H2 matches case-sensitively, so a native query that
   * leaves a table name unquoted finds no table there (RPS-1385). With {@code
   * DATABASE_TO_LOWER=TRUE} such a query passes here and fails in the image.
   */
  private static final String H2_URL = "jdbc:h2:mem:rps957;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

  private static final Duration SEEDING_TIMEOUT = Duration.ofSeconds(30);

  private static final Path STORAGE_ROOT = createStorageRoot();

  @Autowired private RepoRepository repoRepository;

  @DynamicPropertySource
  static void registerH2Properties(final DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> H2_URL);
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("storage-gateway.fs.base-path", STORAGE_ROOT::toString);
    registry.add("admin.initial-password", () -> "H2TestAdmin1!");
  }

  private static Path createStorageRoot() {
    try {
      return Files.createTempDirectory("repsy-rps957-h2");
    } catch (final IOException e) {
      throw new IllegalStateException("Could not create H2 test storage", e);
    }
  }

  /**
   * Waits for the startup seeding to have committed its default repo, one per {@link RepoType}
   * (RPS-1070), before a test runs any assertion of its own.
   *
   * <p>{@code AdminUserInitializer} publishes a {@code UserCreatedEvent} at context startup and the
   * {@code @Async} per-protocol {@code *AuthListener}s then each create one default repo, in their
   * own committed transaction, independently of this class's test transaction. {@link
   * AbstractIntegrationTest}'s classes wait for the same thing through {@code
   * CommittedRowsGuard#awaitDefaultRepos} before their first test; the H2 suites share one context
   * and database the same way but had no such barrier, so the first H2 class of a run could start,
   * and read the repo counts, while a listener was still committing its insert (RPS-1674).
   */
  @BeforeEach
  void awaitDefaultReposSeeded() {
    await()
        .atMost(SEEDING_TIMEOUT)
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () ->
                assertThat(this.repoRepository.count())
                    .isGreaterThanOrEqualTo(RepoType.values().length));
  }
}
