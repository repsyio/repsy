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
package io.repsy.os.server.core.post_precessors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1556: {@link H2CheckpointPostProcessor} must run a synchronous {@code CHECKPOINT SYNC} after
 * a write on H2 -- and only then, since it is a real (measured) added cost per write and pointless
 * against PostgreSQL, which already fsyncs its WAL on commit.
 */
@DisplayName("H2CheckpointPostProcessor")
class H2CheckpointPostProcessorTest {

  private static final String H2_URL =
      "jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
  private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/repsy";

  private DataSource dataSource;
  private Connection connection;
  private Statement statement;
  private HttpServletRequest request;
  private HttpServletResponse response;

  private static Map<String, Object> properties(final boolean writeOperation) {
    return Map.of("writeOperation", writeOperation);
  }

  private H2CheckpointPostProcessor processorFor(final String datasourceUrl) {
    return new H2CheckpointPostProcessor(this.dataSource, datasourceUrl, List.of());
  }

  @BeforeEach
  void setUp() throws SQLException {
    this.dataSource = mock(DataSource.class);
    this.connection = mock(Connection.class);
    this.statement = mock(Statement.class);
    this.request = mock(HttpServletRequest.class);
    this.response = mock(HttpServletResponse.class);

    when(this.dataSource.getConnection()).thenReturn(this.connection);
    when(this.connection.createStatement()).thenReturn(this.statement);
  }

  @Test
  @DisplayName("on H2, a write runs CHECKPOINT SYNC and lets the request continue")
  void checkpointsAWriteOnH2() throws SQLException {
    final var processor = this.processorFor(H2_URL);

    final var result =
        processor.process(new ProtocolContext(), this.request, this.response, properties(true));

    verify(this.statement).execute(eq("CHECKPOINT SYNC"));
    assertThat(result.isEmpty()).isTrue();
  }

  @Test
  @DisplayName("on H2, a read (no writeOperation) never opens a connection")
  void skipsAReadOnH2() {
    final var processor = this.processorFor(H2_URL);

    processor.process(new ProtocolContext(), this.request, this.response, properties(false));

    verifyNoInteractions(this.dataSource);
  }

  @Test
  @DisplayName("with no writeOperation property at all, defaults to skipping")
  void skipsWhenThePropertyIsMissing() {
    final var processor = this.processorFor(H2_URL);

    processor.process(new ProtocolContext(), this.request, this.response, Map.of());

    verifyNoInteractions(this.dataSource);
  }

  @Test
  @DisplayName(
      "on PostgreSQL, a write never opens a connection: PostgreSQL already fsyncs on commit")
  void skipsEveryRequestOnPostgres() {
    final var processor = this.processorFor(POSTGRES_URL);

    processor.process(new ProtocolContext(), this.request, this.response, properties(true));

    verifyNoInteractions(this.dataSource);
  }

  @Test
  @DisplayName("a failed CHECKPOINT SYNC is logged and swallowed: the write is already committed")
  void swallowsACheckpointFailure() throws SQLException {
    when(this.dataSource.getConnection()).thenThrow(new SQLException("database is closing"));
    final var processor = this.processorFor(H2_URL);

    final var result =
        processor.process(new ProtocolContext(), this.request, this.response, properties(true));

    assertThat(result.isEmpty()).isTrue();
  }
}
