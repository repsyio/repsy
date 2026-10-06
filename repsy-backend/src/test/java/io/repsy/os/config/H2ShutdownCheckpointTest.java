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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("H2ShutdownCheckpoint")
class H2ShutdownCheckpointTest {

  private static final String H2_URL =
      "jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
  private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/repsy";

  private DataSource dataSource;
  private Connection connection;
  private Statement statement;

  @BeforeEach
  void setUp() throws SQLException {
    this.dataSource = mock(DataSource.class);
    this.connection = mock(Connection.class);
    this.statement = mock(Statement.class);

    when(this.dataSource.getConnection()).thenReturn(this.connection);
    when(this.connection.createStatement()).thenReturn(this.statement);
  }

  @Test
  @DisplayName("on H2, stopping runs CHECKPOINT SYNC")
  void checkpointsOnH2() throws SQLException {
    new H2ShutdownCheckpoint(this.dataSource, H2_URL).checkpoint();

    verify(this.statement).execute("CHECKPOINT SYNC");
  }

  @Test
  @DisplayName("on PostgreSQL, stopping never opens a connection")
  void skipsPostgres() {
    new H2ShutdownCheckpoint(this.dataSource, POSTGRES_URL).checkpoint();

    verifyNoInteractions(this.dataSource);
  }

  @Test
  @DisplayName("a failed checkpoint is logged and does not stop the shutdown")
  void swallowsAFailure() throws SQLException {
    when(this.dataSource.getConnection()).thenThrow(new SQLException("database is closing"));

    assertThatCode(() -> new H2ShutdownCheckpoint(this.dataSource, H2_URL).checkpoint())
        .doesNotThrowAnyException();
  }
}
