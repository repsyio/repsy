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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.events.PgpKeySourcesChangedEvent;
import io.repsy.os.shared.repo.events.PgpVerifyAllSignaturesToggledEvent;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** The recomputation of {@code signed} of all the versions of a repo on a toggle (RPS-1316). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignedRecomputeService (RPS-1316)")
class SignedRecomputeServiceTest {

  private static final UUID BEFORE_THE_FIRST = new UUID(0L, 0L);

  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock RepoRepository repoRepository;
  @Mock VersionSignatureService versionSignatureService;

  private SignedRecomputeService service;
  private final UUID repoId = UUID.randomUUID();

  /** The jobs that were handed to the executor, and have not been run. */
  private final List<Runnable> queue = new ArrayList<>();

  private boolean full;

  @BeforeEach
  void setUp() {
    this.service =
        new SignedRecomputeService(
            this.artifactVersionRepository,
            this.repoRepository,
            this.versionSignatureService,
            this::submit,
            2);
  }

  private void submit(final Runnable job) {
    if (this.full) {
      throw new RejectedExecutionException("full");
    }

    this.queue.add(job);
  }

  private void runQueued() {
    final var jobs = List.copyOf(this.queue);
    this.queue.clear();
    jobs.forEach(Runnable::run);
  }

  private static List<UUID> ids(final int count) {
    return java.util.stream.IntStream.range(1, count + 1).mapToObj(i -> new UUID(0L, i)).toList();
  }

  private void pageAfter(final UUID after, final List<UUID> page) {
    when(this.artifactVersionRepository.findIdsByRepoIdAfter(
            eq(this.repoId), eq(after), any(Pageable.class)))
        .thenReturn(page);
  }

  @Test
  @DisplayName("walks the versions in pages of ids, each after the last id of the page before")
  void walksAllPages() {
    final var all = ids(5);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 2));
    this.pageAfter(all.get(1), all.subList(2, 4));
    this.pageAfter(all.get(3), all.subList(4, 5));

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(5, 0));
    final var order = inOrder(this.versionSignatureService);
    all.forEach(id -> order.verify(this.versionSignatureService).recompute(id));
  }

  @Test
  @DisplayName("a page that is exactly full is followed by a look for the next one")
  void anExactlyFullLastPageIsFollowedByAnEmptyOne() {
    final var all = ids(2);
    this.pageAfter(BEFORE_THE_FIRST, all);
    this.pageAfter(all.get(1), List.of());

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result.recomputed()).isEqualTo(2);
    verify(this.artifactVersionRepository, times(2))
        .findIdsByRepoIdAfter(eq(this.repoId), any(UUID.class), any(Pageable.class));
  }

  @Test
  @DisplayName("a repo without versions recomputes nothing")
  void anEmptyRepo() {
    this.pageAfter(BEFORE_THE_FIRST, List.of());

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(0, 0));
    verify(this.versionSignatureService, never()).recompute(any());
  }

  @Test
  @DisplayName("a version that fails is counted and the rest are still recomputed")
  void aFailureDoesNotAbortTheRest() {
    final var all = ids(3);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 2));
    this.pageAfter(all.get(1), all.subList(2, 3));
    doThrow(new IllegalStateException("storage is down"))
        .when(this.versionSignatureService)
        .recompute(all.get(0));

    final var result = this.service.recomputeRepo(this.repoId);

    assertThat(result).isEqualTo(new SignedRecomputeService.Result(2, 1));
    verify(this.versionSignatureService).recompute(all.get(1));
    verify(this.versionSignatureService).recompute(all.get(2));
  }

  @Test
  @DisplayName("the event of a toggle queues the recomputation of the repo it names")
  void theEventQueuesTheRecomputationOfItsRepo() {
    this.pageAfter(BEFORE_THE_FIRST, ids(1));

    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    // Handed to the executor, not run on the thread that committed the toggle.
    assertThat(this.queue).hasSize(1);
    verify(this.versionSignatureService, never()).recompute(any());

    this.runQueued();

    verify(this.versionSignatureService).recompute(ids(1).getFirst());
  }

  @Test
  @DisplayName("a repo whose recomputation is still waiting is not queued a second time")
  void aWaitingRepoIsQueuedOnce() {
    this.pageAfter(BEFORE_THE_FIRST, ids(1));

    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    assertThat(this.queue).hasSize(1);

    this.runQueued();

    verify(this.versionSignatureService).recompute(ids(1).getFirst());
  }

  @Test
  @DisplayName("a toggle while the repo's run is going queues another run behind it")
  void aToggleDuringARunQueuesAnotherRun() {
    this.pageAfter(BEFORE_THE_FIRST, ids(1));
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));
    this.runQueued();

    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    assertThat(this.queue).hasSize(1);
  }

  @Test
  @DisplayName("another repo is queued next to a waiting one")
  void anotherRepoIsQueuedToo() {
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(UUID.randomUUID()));

    assertThat(this.queue).hasSize(2);
  }

  @Test
  @DisplayName("a full queue rejects the job: nothing runs on the caller, nothing is thrown")
  void aFullQueueRunsNothingOnTheCaller() {
    this.full = true;

    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    verifyNoInteractions(this.versionSignatureService, this.artifactVersionRepository);

    // The repo is not stuck as waiting: the next toggle is queued once there is room again.
    this.full = false;
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    assertThat(this.queue).hasSize(1);
  }

  @Test
  @DisplayName("a run that fails is logged and does not throw into the executor")
  void aFailingRunDoesNotThrow() {
    when(this.artifactVersionRepository.findIdsByRepoIdAfter(
            eq(this.repoId), eq(BEFORE_THE_FIRST), any(Pageable.class)))
        .thenThrow(new IllegalStateException("database is down"));
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    assertThatCode(this::runQueued).doesNotThrowAnyException();

    // and the repo can be queued again.
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));
    assertThat(this.queue).hasSize(1);
  }

  private void verifyAllIs(final boolean enabled) {
    when(this.repoRepository.findPgpVerifyAllSignaturesEnabledById(this.repoId))
        .thenReturn(Optional.of(enabled));
  }

  @Test
  @DisplayName("a key change on a repo that verifies every signature queues its recomputation")
  void aKeyChangeOfAVerifyAllRepoIsQueued() {
    this.verifyAllIs(true);
    this.pageAfter(BEFORE_THE_FIRST, ids(1));

    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));

    // Handed to the executor, not run on the thread that committed the change.
    assertThat(this.queue).hasSize(1);
    verify(this.versionSignatureService, never()).recompute(any());

    this.runQueued();

    verify(this.versionSignatureService).recompute(ids(1).getFirst());
  }

  @Test
  @DisplayName("a key change on a repo that does not verify every signature recomputes nothing")
  void aKeyChangeOfAFlagOffRepoIsIgnored() {
    this.verifyAllIs(false);

    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));

    assertThat(this.queue).isEmpty();
    verifyNoInteractions(this.versionSignatureService, this.artifactVersionRepository);
  }

  @Test
  @DisplayName("a key change of a repo that is gone recomputes nothing")
  void aKeyChangeOfAMissingRepoIsIgnored() {
    when(this.repoRepository.findPgpVerifyAllSignaturesEnabledById(this.repoId))
        .thenReturn(Optional.empty());

    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));

    assertThat(this.queue).isEmpty();
  }

  @Test
  @DisplayName("key changes and a toggle of one repo share the one waiting run")
  void keyChangesAndAToggleAreQueuedOnce() {
    this.verifyAllIs(true);

    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));
    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));
    this.service.onToggled(new PgpVerifyAllSignaturesToggledEvent(this.repoId));

    assertThat(this.queue).hasSize(1);
  }

  @Test
  @DisplayName("a full queue does not throw into the request that changed a key")
  void aFullQueueDoesNotFailAKeyChange() {
    this.verifyAllIs(true);
    this.full = true;

    assertThatCode(
            () -> this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId)))
        .doesNotThrowAnyException();

    verifyNoInteractions(this.versionSignatureService, this.artifactVersionRepository);

    // The repo is not stuck as waiting: the next change is queued once there is room again.
    this.full = false;
    this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId));

    assertThat(this.queue).hasSize(1);
  }

  @Test
  @DisplayName("a setting that cannot be read does not throw into the request, and queues nothing")
  void anUnreadableSettingDoesNotFailAKeyChange() {
    when(this.repoRepository.findPgpVerifyAllSignaturesEnabledById(this.repoId))
        .thenThrow(new IllegalStateException("database is down"));

    assertThatCode(
            () -> this.service.onKeySourcesChanged(new PgpKeySourcesChangedEvent(this.repoId)))
        .doesNotThrowAnyException();

    assertThat(this.queue).isEmpty();
  }

  @Test
  @DisplayName("a batch size below one is read as one")
  void aBatchSizeBelowOneIsOne() {
    final var single =
        new SignedRecomputeService(
            this.artifactVersionRepository,
            this.repoRepository,
            this.versionSignatureService,
            this::submit,
            0);
    final var all = ids(2);
    this.pageAfter(BEFORE_THE_FIRST, all.subList(0, 1));
    this.pageAfter(all.get(0), all.subList(1, 2));
    this.pageAfter(all.get(1), List.of());

    assertThat(single.recomputeRepo(this.repoId).recomputed()).isEqualTo(2);
  }
}
