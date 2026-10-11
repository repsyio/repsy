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
package io.repsy.os.server.core.post_processors;

import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RPS-1556: on the embedded H2 database, forces a just-committed write onto the database file
 * before the HTTP response that told the client it succeeded is sent.
 *
 * <p>H2's MVStore normally defers writing a committed transaction to its file by up to {@code
 * autoCommitDelay} (500ms by default) AND only once enough unsaved memory has piled up across the
 * whole store -- a small, infrequent write (one publish) can sit unflushed for longer still, with
 * nothing forcing it out until either that timer or that memory threshold is hit. A crash (SIGKILL,
 * OOM) inside that window loses the just-published row(s) although the artifact bytes were already
 * written to storage and the client was already told the publish succeeded (verified live: repeated
 * {@code docker kill --signal KILL} moments after a publish, {@code
 * e2e/tests/stack/persistence.spec.ts}).
 *
 * <p>Lowering the URL's {@code WRITE_DELAY} setting alone was tried first and rejected: it removes
 * H2's only periodic safety net (its background writer thread) without replacing it with anything
 * deterministic, so it only shrinks the race instead of closing it -- measured against the running
 * application (not H2 in isolation), it still lost data in 2 of 5 crash/restart trials. {@code
 * CHECKPOINT SYNC} is different: it forces H2 to write every pending change to the file AND fsync
 * it, synchronously, right now, regardless of the delay or memory settings. It also operates on the
 * whole database rather than one session, so it flushes whatever any other pooled connection
 * committed moments earlier too. The trade-off is latency: this blocks the response on one extra
 * pooled-connection round trip and a real disk flush for every write, which is deliberately paid
 * only for a write (never a GET/HEAD download) and only on H2 (PostgreSQL already fsyncs its WAL on
 * commit, so this is a no-op there without even touching the pool).
 *
 * <p>Runs last among the post-processors -- after usage tracking ({@link UsagePostProcessor}) and
 * the pushed-artifact event ({@link
 * io.repsy.os.server.security.shared.postprocessors.ArtifactPushedEventPostProcessor}) -- so that
 * write's own row is checkpointed too, not just the protocol handler's.
 */
@Slf4j
@Component
public class H2CheckpointPostProcessor extends ProtocolProcessor {

  private static final int PRIORITY = Integer.MAX_VALUE;
  private static final String CHECKPOINT_SQL = "CHECKPOINT SYNC";

  private final DataSource dataSource;
  private final boolean enabled;

  public H2CheckpointPostProcessor(
      final DataSource dataSource,
      @Value("${spring.datasource.url:}") final String datasourceUrl,
      final List<ProtocolProvider> protocolProviders) {

    this.dataSource = dataSource;
    this.enabled = datasourceUrl.startsWith("jdbc:h2:");
    log.info("CHECKPOINT SYNC after protocol writes: {}", this.enabled ? "enabled (H2)" : "off");

    for (final var protocolProvider : protocolProviders) {
      protocolProvider.registerPostProcessor(this);
    }
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    if (this.enabled && this.isWriteOperation(properties)) {
      final long started = System.nanoTime();
      this.checkpoint();
      log.debug(
          "{} after {} {} took {} ms",
          CHECKPOINT_SQL,
          request.getMethod(),
          request.getRequestURI(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    return ProcessorResult.next();
  }

  private void checkpoint() {
    try (final Connection connection = this.dataSource.getConnection();
        final Statement statement = connection.createStatement()) {
      statement.execute(CHECKPOINT_SQL);
    } catch (final SQLException e) {
      // The write is already committed in the ordinary H2 sense (JPA/Hibernate already returned
      // successfully); a checkpoint that itself fails only widens the pre-existing write-delay
      // window back to its default instead of closing it, so this logs and lets the response go
      // out rather than turning a checkpoint failure into a 500 for a publish that otherwise fully
      // succeeded.
      log.warn(
          "{} failed after a write; the write-delay window is not closed for it",
          CHECKPOINT_SQL,
          e);
    }
  }

  private boolean isWriteOperation(final Map<String, Object> properties) {
    return (boolean) properties.getOrDefault(HandlerPropertyKeys.WRITE_OPERATION, false);
  }
}
