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

import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1652: the embedded H2 database of the Docker image must keep accepting writes after the
 * connection that created its tables is gone.
 *
 * <p>H2 2.4.240 (the version of the Spring Boot BOM) kept the session that prepared a {@code CHECK
 * (col IN (...))} constraint, so once that connection closed every insert or update of the table
 * failed with "Check constraint invalid ... The database has been closed"
 * (h2database/h2database#4308, #4320, #4342; fixed in 2.5.250). The schema of Repsy has such
 * constraints on {@code repo.type} and {@code users.role}, and HikariCP retires each connection
 * after {@code max-lifetime} (30 minutes by default), so the panel could not create a repository or
 * record a login after about half an hour of uptime. Running the whole UI suite on H2 found it: a
 * second full run, 30 minutes after the stack started, failed hundreds of tests with a 500.
 *
 * <p>The test needs no Docker and no Spring context: two plain JDBC connections to one in-memory
 * database, the first closed before the second writes.
 */
@DisplayName("H2 keeps CHECK (IN ...) constraints working after their connection closed (RPS-1652)")
class H2CheckConstraintAcrossConnectionsTest {

  @Test
  @DisplayName("an insert on a new connection passes the check that an old, closed one created")
  void insertAfterTheCreatingConnectionClosed() throws SQLException {
    final var url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    try (final Connection creator = DriverManager.getConnection(url);
        final Statement statement = creator.createStatement()) {
      statement.execute(
          "create table repo (id int primary key, type varchar(10) not null,"
              + " constraint ch_repo__type check (type in ('MAVEN', 'NPM', 'DOCKER')))");
      statement.execute("insert into repo values (1, 'MAVEN')");
    }

    try (final Connection writer = DriverManager.getConnection(url);
        final Statement statement = writer.createStatement()) {
      assertThatCode(() -> statement.execute("insert into repo values (2, 'NPM')"))
          .doesNotThrowAnyException();
    }
  }
}
