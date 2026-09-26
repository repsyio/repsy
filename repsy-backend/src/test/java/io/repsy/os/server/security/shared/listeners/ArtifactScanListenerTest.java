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
package io.repsy.os.server.security.shared.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.RetryableException;
import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityScanner;
import io.repsy.os.server.security.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.server.security.scanner.dtos.ScanRequest;
import io.repsy.os.server.security.scanner.trivy.TrivyScannerProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.net.ConnectException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArtifactScanListener")
class ArtifactScanListenerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SCAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String SATURATED_MESSAGE = "Scan executor is saturated; retry later";

  @Mock private VulnerabilityScannerRegistry scannerRegistry;
  @Mock private VulnerabilityScanTxService scanTxService;
  @Mock private RepoTxService repoTxService;
  @Mock private DockerScanTokenIssuer dockerScanTokenIssuer;
  @Mock private Executor scanTaskExecutor;
  @Mock private TaskScheduler taskScheduler;
  @Mock private VulnerabilityScanner scanner;

  private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
  private static final TrivyScannerProperties PROPERTIES =
      new TrivyScannerProperties("http://scanner", "key", 10, 3000, 330, 3, 15, 60);

  private ArtifactScanListener listener;
  private Logger listenerLogger;
  private Level originalLogLevel;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    this.listenerLogger = (Logger) LoggerFactory.getLogger(ArtifactScanListener.class);
    this.originalLogLevel = this.listenerLogger.getLevel();
    this.listenerLogger.setLevel(Level.DEBUG);
    this.logAppender = new ListAppender<>();
    this.logAppender.start();
    this.listenerLogger.addAppender(this.logAppender);

    this.listener =
        new ArtifactScanListener(
            this.scannerRegistry,
            this.scanTxService,
            this.repoTxService,
            this.dockerScanTokenIssuer,
            this.taskScheduler,
            PROPERTIES,
            this.scanTaskExecutor,
            Map.<String, StorageStrategy>of());
  }

  @AfterEach
  void tearDown() {
    this.listenerLogger.detachAppender(this.logAppender);
    this.listenerLogger.setLevel(this.originalLogLevel);
  }

  @Test
  @DisplayName("skips the scan without an error when the repo was deleted before the listener ran")
  void skipsScanWhenRepoIsGone() {
    when(this.scannerRegistry.findScanner("MAVEN")).thenReturn(Optional.empty());
    when(this.scanTxService.createPendingScan(REPO_ID, "artifact", "1.0.0", "none"))
        .thenThrow(new ItemNotFoundException("repoNotFound"));

    assertThatCode(() -> this.listener.handleArtifactPushed(this.event()))
        .doesNotThrowAnyException();

    verifyNoInteractions(this.scanTaskExecutor);
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(logEvent -> logEvent.getFormattedMessage().contains("no longer exists"));
  }

  @Test
  @DisplayName("fails a pushed scan when the executor rejects it")
  void rejectedPushedScanIsFailed() {
    final var event = this.event();
    when(this.scannerRegistry.findScanner("MAVEN")).thenReturn(Optional.empty());
    when(this.scanTxService.createPendingScan(REPO_ID, "artifact", "1.0.0", "none"))
        .thenReturn(SCAN_ID);
    doThrow(new RejectedExecutionException()).when(this.scanTaskExecutor).execute(any());

    this.listener.handleArtifactPushed(event);

    verify(this.scanTxService).recordScanFailure(SCAN_ID, SATURATED_MESSAGE);
  }

  @Test
  @DisplayName("returns a retryable failure when a manual scan is rejected")
  void rejectedManualScanIsRetryable() {
    doThrow(new RejectedExecutionException()).when(this.scanTaskExecutor).execute(any());

    assertThatThrownBy(() -> this.listener.executeManualScan(SCAN_ID, this.event()))
        .isInstanceOf(RetryableException.class)
        .hasMessage("scanExecutorSaturated");

    verify(this.scanTxService).recordScanFailure(SCAN_ID, SATURATED_MESSAGE);
  }

  @Test
  @DisplayName("skips a Docker scan without an error when the repo was deleted after it was queued")
  void skipsDockerScanWhenRepoIsDeletedAfterQueueing() {
    this.givenDockerScanIsQueued();
    when(this.repoTxService.getRepo(REPO_ID)).thenThrow(new ItemNotFoundException("repoNotFound"));

    assertThatCode(() -> this.listener.handleArtifactPushed(this.dockerEvent()))
        .doesNotThrowAnyException();

    verify(this.scanner, never()).scan(any());
    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(logEvent -> logEvent.getFormattedMessage().contains("repo was deleted"));
  }

  @Test
  @DisplayName(
      "logs at INFO and skips recordScanFailure when the scan row is gone because the repo was"
          + " deleted mid-scan")
  void skipsFailureRecordingWhenScanRowIsGone() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    doThrow(new ItemNotFoundException("vulnerabilityScanNotFound")).when(this.scanner).scan(any());

    assertThatCode(() -> this.listener.handleArtifactPushed(this.dockerEvent()))
        .doesNotThrowAnyException();

    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(
            logEvent ->
                logEvent.getLevel() == Level.INFO
                    && logEvent.getFormattedMessage().contains("scan row no longer exists"));
  }

  @Test
  @DisplayName(
      "still records a failure and logs at ERROR for any other ItemNotFoundException from the"
          + " scanner")
  void recordsFailureForOtherItemNotFoundExceptions() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    doThrow(new ItemNotFoundException("artifactNotFound")).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanTxService).recordScanFailure(SCAN_ID, "artifactNotFound");
    assertThat(this.logAppender.list)
        .anyMatch(
            logEvent ->
                logEvent.getLevel() == Level.ERROR
                    && logEvent.getFormattedMessage().contains("Vulnerability scan failed"));
  }

  @Test
  @DisplayName(
      "logs at INFO and skips recordScanFailure when writing the outcome finds no row to update"
          + " because the repo was deleted between the read and the write")
  void skipsFailureRecordingWhenOutcomeWriteLostItsRow() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(false);
    doThrow(optimisticLockFailure()).when(this.scanner).scan(any());

    assertThatCode(() -> this.listener.handleArtifactPushed(this.dockerEvent()))
        .doesNotThrowAnyException();

    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(
            logEvent ->
                logEvent.getLevel() == Level.INFO
                    && logEvent.getFormattedMessage().contains("scan row no longer exists"));
  }

  @Test
  @DisplayName(
      "still records a failure and logs at ERROR for an optimistic locking failure on a scan row"
          + " that still exists")
  void recordsFailureForOptimisticLockFailureOfExistingRow() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(true);
    doThrow(optimisticLockFailure()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanTxService).recordScanFailure(eq(SCAN_ID), any());
    assertThat(this.logAppender.list)
        .anyMatch(
            logEvent ->
                logEvent.getLevel() == Level.ERROR
                    && logEvent.getFormattedMessage().contains("Vulnerability scan failed"));
  }

  @Test
  @DisplayName(
      "logs at INFO and skips recordScanFailure when a finding of the scan hits the foreign key"
          + " because the scan row is gone")
  void skipsFailureRecordingWhenFindingsLostTheirScan() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(false);
    doThrow(integrityViolation("23503")).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.WARN))
        .anyMatch(logEvent -> logEvent.getFormattedMessage().contains("scan row no longer exists"));
  }

  @Test
  @DisplayName(
      "still records a failure and logs at ERROR for a data integrity violation that is not a"
          + " foreign key violation, without asking whether the row exists")
  void recordsFailureForOtherIntegrityViolations() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    doThrow(integrityViolation("23505")).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanTxService).recordScanFailure(eq(SCAN_ID), any());
    verify(this.scanTxService, never()).scanExists(any());
    assertThat(this.logAppender.list).anyMatch(logEvent -> logEvent.getLevel() == Level.ERROR);
  }

  @Test
  @DisplayName("scans a public Docker repo without a registry token")
  void scansPublicDockerRepoWithoutToken() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);

    this.listener.handleArtifactPushed(this.dockerEvent());

    final var request = this.capturedScanRequest();
    assertThat(request.dockerRegistryReference()).isEqualTo("repo/image:1.0");
    assertThat(request.registryAuthToken()).isNull();
    verifyNoInteractions(this.dockerScanTokenIssuer);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("scans a Docker image named by a digest of either algorithm with @, not :")
  void scansADigestReferenceWithAtSign(final String algorithm) {
    final var digest = algorithm + ":" + "a".repeat("sha256".equals(algorithm) ? 64 : 128);
    this.givenDockerScanIsQueued(digest);
    this.givenDockerRepo(false);

    this.listener.handleArtifactPushed(this.dockerEvent(digest));

    assertThat(this.capturedScanRequest().dockerRegistryReference())
        .isEqualTo("repo/image@" + digest);
  }

  @Test
  @DisplayName("scans a private Docker repo with a read-only pull token")
  void scansPrivateDockerRepoWithPullToken() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(true);
    when(this.dockerScanTokenIssuer.mintReadOnlyPullToken(REPO_ID, "repo")).thenReturn("token");

    this.listener.handleArtifactPushed(this.dockerEvent());

    final var request = this.capturedScanRequest();
    assertThat(request.dockerRegistryReference()).isEqualTo("repo/image:1.0");
    assertThat(request.registryAuthToken()).isEqualTo("token");
  }

  @Test
  @DisplayName(
      "retries a scanner that cannot be reached after 15 s and 60 s, then records the failure"
          + " naming the attempts")
  void retriesAnUnreachableScannerTwiceThenFails() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(true);
    doThrow(unreachable()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    final var firstRetry = this.captureScheduled(1);
    assertThat(firstRetry.instants().getFirst()).isEqualTo(NOW.plusSeconds(15));

    firstRetry.tasks().getFirst().run();

    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    final var secondRetry = this.captureScheduled(2);
    assertThat(secondRetry.instants().get(1)).isEqualTo(NOW.plusSeconds(60));

    secondRetry.tasks().get(1).run();

    verify(this.scanner, times(3)).scan(any());
    verify(this.taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    final var message = ArgumentCaptor.forClass(String.class);
    verify(this.scanTxService).recordScanFailure(eq(SCAN_ID), message.capture());
    assertThat(message.getValue()).contains("Connection refused").endsWith("(after 3 attempts)");
    assertThat(this.logAppender.list)
        .filteredOn(logEvent -> logEvent.getLevel() == Level.WARN)
        .hasSize(2)
        .allMatch(logEvent -> logEvent.getFormattedMessage().contains("Retrying in"));
  }

  @Test
  @DisplayName("keeps the retries, all their waits and their requests, within the poller's limit")
  void retryBudgetStaysBelowTheMaximumScanDuration() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(true);
    doThrow(unreachable()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());
    this.captureScheduled(1).tasks().getFirst().run();
    final var scheduled = this.captureScheduled(2);
    scheduled.tasks().get(1).run();

    final var totalWait =
        Duration.between(NOW, scheduled.instants().getFirst())
            .plus(Duration.between(NOW, scheduled.instants().get(1)));

    assertThat(totalWait.toSeconds()).isEqualTo(75);
    assertThat(PROPERTIES.submitRetryBudgetSeconds())
        .isEqualTo(totalWait.toSeconds() + 3 * PROPERTIES.requestTimeoutSeconds())
        .isLessThan(PROPERTIES.maxScanDurationSeconds());
  }

  @Test
  @DisplayName("records no failure and completes one scan when the second submit succeeds")
  void succeedsOnTheSecondAttempt() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(true);
    doThrow(unreachable()).doNothing().when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());
    this.captureScheduled(1).tasks().getFirst().run();

    verify(this.scanner, times(2)).scan(any());
    verify(this.taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .noneMatch(logEvent -> logEvent.getLevel().isGreaterOrEqual(Level.ERROR));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("failuresThatAreNotRetried")
  @DisplayName("does not retry a scanner error, a submit timeout or an unreadable artifact")
  void doesNotRetryOtherFailures(final String name, final RuntimeException failure) {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    doThrow(failure).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    verify(this.scanner, times(1)).scan(any());
    verifyNoInteractions(this.taskScheduler);
    verify(this.scanTxService).recordScanFailure(SCAN_ID, failure.getMessage());
  }

  static Stream<Arguments> failuresThatAreNotRetried() {
    return Stream.of(
        Arguments.of(
            "5xx from the scanner",
            new IllegalArgumentException(
                "Scanner adapter returned error: 503 SERVICE_UNAVAILABLE")),
        Arguments.of(
            "block() timeout",
            new IllegalStateException("Timeout on blocking read for 10000000000 NANOSECONDS")),
        Arguments.of(
            "unreadable artifact",
            new IllegalArgumentException("Failed to read artifact content")));
  }

  @Test
  @DisplayName("records the failure when the executor is saturated at the time of the retry")
  void savesFailureWhenTheRetryIsRejected() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    doThrow(unreachable()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());
    doThrow(new RejectedExecutionException()).when(this.scanTaskExecutor).execute(any());
    this.captureScheduled(1).tasks().getFirst().run();

    verify(this.scanner, times(1)).scan(any());
    verify(this.scanTxService).recordScanFailure(SCAN_ID, SATURATED_MESSAGE);
    assertThat(this.logAppender.list)
        .anyMatch(
            logEvent ->
                logEvent.getLevel() == Level.WARN
                    && logEvent.getFormattedMessage().contains("Giving up on the retry"));
  }

  @Test
  @DisplayName("records the failure when the retry cannot be scheduled")
  void recordsFailureWhenTheSchedulerRejects() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    doThrow(new RejectedExecutionException("scheduler shut down"))
        .when(this.taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    doThrow(unreachable()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());

    final var message = ArgumentCaptor.forClass(String.class);
    verify(this.scanTxService).recordScanFailure(eq(SCAN_ID), message.capture());
    assertThat(message.getValue()).endsWith("(the retry could not be scheduled)");
  }

  @Test
  @DisplayName("does not submit the retry of a scan whose row is gone")
  void skipsTheRetryWhenTheScanRowIsGone() {
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    this.givenSchedulerAt(NOW);
    when(this.scanTxService.scanExists(SCAN_ID)).thenReturn(false);
    doThrow(unreachable()).when(this.scanner).scan(any());

    this.listener.handleArtifactPushed(this.dockerEvent());
    this.captureScheduled(1).tasks().getFirst().run();

    verify(this.scanner, times(1)).scan(any());
    verify(this.scanTxService, never()).recordScanFailure(any(), any());
    assertThat(this.logAppender.list)
        .anyMatch(logEvent -> logEvent.getFormattedMessage().contains("scan row no longer exists"));
  }

  @Test
  @DisplayName("does not retry, and does not mention attempts, when the retry is switched off")
  void recordsTheFailureAtOnceWhenOneAttemptIsConfigured() {
    final var listener =
        new ArtifactScanListener(
            this.scannerRegistry,
            this.scanTxService,
            this.repoTxService,
            this.dockerScanTokenIssuer,
            this.taskScheduler,
            new TrivyScannerProperties("http://scanner", "key", 10, 3000, 330, 1, 15, 60),
            this.scanTaskExecutor,
            Map.<String, StorageStrategy>of());
    this.givenDockerScanIsQueued();
    this.givenDockerRepo(false);
    final var failure = unreachable();
    doThrow(failure).when(this.scanner).scan(any());

    listener.handleArtifactPushed(this.dockerEvent());

    verifyNoInteractions(this.taskScheduler);
    verify(this.scanTxService).recordScanFailure(SCAN_ID, failure.getMessage());
  }

  private void givenSchedulerAt(final Instant now) {
    when(this.taskScheduler.getClock()).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
  }

  private ScheduledRetries captureScheduled(final int expected) {
    final var tasks = ArgumentCaptor.forClass(Runnable.class);
    final var instants = ArgumentCaptor.forClass(Instant.class);
    verify(this.taskScheduler, times(expected)).schedule(tasks.capture(), instants.capture());
    return new ScheduledRetries(
        new ArrayList<>(tasks.getAllValues()), new ArrayList<>(instants.getAllValues()));
  }

  private record ScheduledRetries(List<Runnable> tasks, List<Instant> instants) {}

  private static WebClientRequestException unreachable() {
    return new WebClientRequestException(
        new ConnectException("Connection refused: scanner/10.0.0.1:8090"),
        HttpMethod.POST,
        URI.create("http://scanner:8090/scan"),
        HttpHeaders.EMPTY);
  }

  private static ObjectOptimisticLockingFailureException optimisticLockFailure() {
    return new ObjectOptimisticLockingFailureException(VulnerabilityScan.class, SCAN_ID);
  }

  private static DataIntegrityViolationException integrityViolation(final String sqlState) {
    return new DataIntegrityViolationException(
        "violation", new SQLException("violation", sqlState));
  }

  private void givenDockerScanIsQueued() {
    this.givenDockerScanIsQueued("1.0");
  }

  private void givenDockerScanIsQueued(final String version) {
    when(this.scanner.getName()).thenReturn("trivy");
    when(this.scannerRegistry.findScanner("DOCKER")).thenReturn(Optional.of(this.scanner));
    when(this.scanTxService.createPendingScan(REPO_ID, "image", version, "trivy"))
        .thenReturn(SCAN_ID);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(this.scanTaskExecutor)
        .execute(any());
  }

  private void givenDockerRepo(final boolean privateRepo) {
    final var repoInfo = mock(RepoInfo.class);
    when(repoInfo.isPrivateRepo()).thenReturn(privateRepo);
    when(this.repoTxService.getRepo(REPO_ID)).thenReturn(repoInfo);
  }

  private ScanRequest capturedScanRequest() {
    final var captor = ArgumentCaptor.forClass(ScanRequest.class);
    verify(this.scanner).scan(captor.capture());
    return captor.getValue();
  }

  private ArtifactPushedEvent dockerEvent() {
    return this.dockerEvent("1.0");
  }

  private ArtifactPushedEvent dockerEvent(final String version) {
    return new ArtifactPushedEvent(
        REPO_ID, "DOCKER", "repo", "manifests/1.0", "image", version, false, false);
  }

  private ArtifactPushedEvent event() {
    return new ArtifactPushedEvent(
        REPO_ID, "MAVEN", "repo", "artifact/1.0.0/artifact.jar", "artifact", "1.0.0", false, false);
  }
}
