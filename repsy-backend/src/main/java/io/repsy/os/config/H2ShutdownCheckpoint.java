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
package io.repsy.os.config;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flushes the embedded H2 database to its file when the application stops.
 *
 * <p>An install whose {@code DB_URL} sets {@code DB_CLOSE_ON_EXIT=FALSE} (the Docker image's
 * default did until RPS-1857, and the README showed it) never has the database closed when the JVM
 * exits: a {@code docker stop} or restart ends the process with it still open. H2's MVStore keeps a
 * committed transaction in memory until its background writer saves it, and that only happens once
 * enough unsaved data has piled up, so a small write (a user created in the panel, a login's
 * refresh token) can sit unsaved for minutes and is lost by an ordinary, orderly restart. {@code
 * H2CheckpointPostProcessor} (RPS-1556) closes that window for the protocol writes that can be
 * followed by a crash; this closes it for a stop, for every kind of write. Without that option H2
 * closes the database itself on exit and this finds nothing left to flush.
 *
 * <p>The bean depends on the {@link DataSource}, so Spring destroys it first, while the pool still
 * has connections and after the web server has stopped taking requests. Only {@code CHECKPOINT
 * SYNC} runs, never {@code SHUTDOWN}, so a request still finishing is not cut off by a closed
 * database. PostgreSQL needs nothing: it fsyncs its WAL on commit.
 */
@Slf4j
@Component
public class H2ShutdownCheckpoint {

  private static final String CHECKPOINT_SQL = "CHECKPOINT SYNC";

  private final @NonNull DataSource dataSource;
  private final boolean enabled;

  public H2ShutdownCheckpoint(
      final @NonNull DataSource dataSource,
      @Value("${spring.datasource.url:}") final @NonNull String datasourceUrl) {

    this.dataSource = dataSource;
    this.enabled = datasourceUrl.startsWith("jdbc:h2:");
  }

  @PreDestroy
  void checkpoint() {
    if (!this.enabled) {
      return;
    }

    try (final Connection connection = this.dataSource.getConnection();
        final Statement statement = connection.createStatement()) {
      statement.execute(CHECKPOINT_SQL);
    } catch (final SQLException e) {
      // Stopping must not fail because of this; the database is then in the state it was before.
      log.warn("{} failed on shutdown; recent writes may not be on disk", CHECKPOINT_SQL, e);
    }
  }
}
