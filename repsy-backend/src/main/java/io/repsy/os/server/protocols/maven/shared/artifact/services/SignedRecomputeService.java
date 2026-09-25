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

import io.repsy.os.config.async.SignedRecomputeExecutorConfig;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.events.PgpKeySourcesChangedEvent;
import io.repsy.os.shared.repo.events.PgpVerifyAllSignaturesToggledEvent;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Recomputes {@code signed} of every version of a Maven repo when the repo's {@code
 * pgpVerifyAllSignaturesEnabled} setting is toggled, on either side (RPS-1316). Until then a
 * version was only recomputed by its next upload, so a repo that turned the setting on kept showing
 * the versions that only had their POM signed as signed, and one that turned it off kept showing
 * the ones whose POM is signed as unsigned.
 *
 * <p>It runs on its own executor ({@link SignedRecomputeExecutorConfig}) and only after the
 * transaction that toggled the setting has committed, so the settings request does not wait for it
 * (not even when the queue is full: the job is then rejected and logged, it never runs on the
 * request's thread) and a rolled back change starts nothing. A repo whose run is still waiting in
 * the queue is not queued again: the run reads the setting when it starts and again for every
 * version. The setting is read again for every version, under that version's row lock (see {@link
 * VersionSignatureService#recompute}), so what a run applies is always the rule of the setting as
 * it is when the version is done: a second toggle while a run is going, a second run, or a run that
 * overlaps an upload all end in the same state. The rule itself is the one of the upload path
 * ({@link VersionSignatureService#refreshSigned}), not a copy of it.
 *
 * <p>The versions are walked in pages of ids and each one is recomputed in a transaction of its
 * own, that holds one row lock and ends before the next version is touched: nothing waits for two
 * locks at once, so it cannot deadlock with an upload, which holds the row of its version only. A
 * version that fails is logged and skipped, the rest are still done.
 *
 * <p>A signature that was stored while the setting was off was never verified. When the setting is
 * turned on the recomputation verifies it, one file at a time and under the lock of its version, by
 * the same verifier and key rules as an upload, so that the toggle alone does not turn an honest
 * publisher's versions unsigned ({@link VersionSignatureService#recompute}, RPS-1323).
 *
 * <p>It also runs when the places a signature's key is looked up change, for a repo that verifies
 * every signature ({@link #onKeySourcesChanged}, RPS-1334).
 *
 * <p>The queue is in memory (RPS-1334): a run that is waiting or going when the process stops is
 * lost, and nothing starts it again at startup. This is deliberate, a persisted marker would need a
 * migration in both dialects for a restart in the middle of a run, which is rare. After such a
 * restart the versions can be left as the setting or the key sources before the run had them:
 * toggle {@code pgpVerifyAllSignaturesEnabled} off and on again to run it again.
 */
@Slf4j
@Service
public class SignedRecomputeService {

  private static final UUID BEFORE_THE_FIRST = new UUID(0L, 0L);

  private final ArtifactVersionRepository artifactVersionRepository;
  private final RepoRepository repoRepository;
  private final VersionSignatureService versionSignatureService;
  private final Executor executor;
  private final int batchSize;

  /** The repos with a run that has been queued and has not started yet. */
  private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

  public SignedRecomputeService(
      final ArtifactVersionRepository artifactVersionRepository,
      final RepoRepository repoRepository,
      final VersionSignatureService versionSignatureService,
      @Qualifier(SignedRecomputeExecutorConfig.BEAN_NAME) final Executor executor,
      @Value("${repsy.maven.signed-recompute.batch-size:200}") final int batchSize) {

    this.artifactVersionRepository = artifactVersionRepository;
    this.repoRepository = repoRepository;
    this.versionSignatureService = versionSignatureService;
    this.executor = executor;
    this.batchSize = Math.max(1, batchSize);
  }

  /**
   * Queues the recomputation of the toggled repo once the toggle is committed, unless one is
   * already waiting for it. A run that has started is not waited for: it may have passed versions
   * that the new setting concerns, so another one is queued behind it.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onToggled(final PgpVerifyAllSignaturesToggledEvent event) {

    this.queue(event.repoId(), "toggle the setting again to start it");
  }

  /**
   * Queues the recomputation of a repo whose key sources changed (a public key or a key-server host
   * registered or deleted, or the key-server lookup toggled, RPS-1334), once that is committed, but
   * only if the repo verifies every signature: what such a repo counts as signed depends on which
   * keys its stored {@code .asc} files verify with. A repo that does not counts the signature of
   * its POM, which was verified when it was uploaded, and a change of the key sources does not
   * verify it again, so there is nothing to recompute for it. The setting is read here, committed,
   * and the run reads it again for every version under that version's lock, so a toggle that
   * follows is still applied by its own rule.
   *
   * <p>It never throws into the request that changed the sources, which has committed by now: a
   * failed read of the setting or a full queue is logged, and the change can be repeated. It is
   * queued like a toggle is, on the same executor and once per repo however many changes are
   * waiting.
   *
   * <p>What it does not do: it does not unsign a version by the removal of a key. A signature that
   * was verified is recorded and stays recorded when its key is gone or cannot be found ({@link
   * VersionSignatureService#recompute}: only a signature that is found not to verify is forgotten),
   * so deleting a key changes nothing for the versions it signed, and what the recomputation adds
   * are the versions whose signatures verify with a key that was just added. What a revocation
   * should mean is a product question of its own.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onKeySourcesChanged(final PgpKeySourcesChangedEvent event) {

    final var repoId = event.repoId();

    try {
      if (!this.repoRepository.findPgpVerifyAllSignaturesEnabledById(repoId).orElse(false)) {
        log.debug(
            "Maven repo {} does not verify every signature: its key sources are not used", repoId);

        return;
      }
    } catch (final RuntimeException e) {
      log.error(
          "Could not read whether Maven repo {} verifies every signature: its versions were not"
              + " recomputed after its key sources changed",
          repoId,
          e);

      return;
    }

    this.queue(repoId, "change a key again or toggle the setting to start it");
  }

  private void queue(final UUID repoId, final String howToRetry) {

    if (!this.queued.add(repoId)) {
      log.debug("A recomputation for Maven repo {} is already waiting", repoId);

      return;
    }

    try {
      this.executor.execute(() -> this.runQueued(repoId));
    } catch (final RejectedExecutionException e) {
      this.queued.remove(repoId);

      log.error(
          "The queue of signed recomputations is full: the versions of Maven repo {} were not"
              + " recomputed, {}",
          repoId,
          howToRetry,
          e);
    }
  }

  private void runQueued(final UUID repoId) {

    this.queued.remove(repoId);

    try {
      this.recomputeRepo(repoId);
    } catch (final RuntimeException e) {
      log.error("Recomputing whether the versions of Maven repo {} are signed failed", repoId, e);
    }
  }

  /**
   * Recomputes {@code signed} of all the versions of the repo.
   *
   * @return how many versions were recomputed and how many failed
   */
  public Result recomputeRepo(final UUID repoId) {

    var recomputed = 0;
    var failed = 0;
    var after = BEFORE_THE_FIRST;

    while (true) {
      final var ids =
          this.artifactVersionRepository.findIdsByRepoIdAfter(
              repoId, after, PageRequest.ofSize(this.batchSize));

      for (final var versionId : ids) {
        if (this.recomputeVersion(repoId, versionId)) {
          recomputed++;
        } else {
          failed++;
        }
      }

      if (ids.size() < this.batchSize) {
        break;
      }

      after = ids.getLast();
    }

    log.info(
        "Recomputed whether the versions of Maven repo {} are signed: {} done, {} failed",
        repoId,
        recomputed,
        failed);

    return new Result(recomputed, failed);
  }

  private boolean recomputeVersion(final UUID repoId, final UUID versionId) {

    try {
      this.versionSignatureService.recompute(versionId);

      return true;
    } catch (final RuntimeException e) {
      log.warn(
          "Could not recompute whether version {} of Maven repo {} is signed",
          versionId,
          repoId,
          e);

      return false;
    }
  }

  /**
   * What a run did.
   *
   * @param recomputed the versions that were recomputed, or were gone by then
   * @param failed the versions whose recomputation failed and were left as they were
   */
  public record Result(int recomputed, int failed) {}
}
