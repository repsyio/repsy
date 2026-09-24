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

import io.repsy.os.config.async.MaintenanceTaskExecutorConfig;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.events.PgpVerifyAllSignaturesToggledEvent;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
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
 * <p>It runs on the maintenance executor and only after the transaction that toggled the setting
 * has committed, so the settings request does not wait for it and a rolled back change starts
 * nothing. The setting is read again for every version, under that version's row lock (see {@link
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
 * <p>A version whose signature was stored while the setting was off has no verified signature to
 * count when it is turned on: such a signature is never verified and is not looked at here. It
 * counts once its file and signature are uploaded again.
 */
@Slf4j
@Service
public class SignedRecomputeService {

  private static final UUID BEFORE_THE_FIRST = new UUID(0L, 0L);

  private final ArtifactVersionRepository artifactVersionRepository;
  private final VersionSignatureService versionSignatureService;
  private final int batchSize;

  public SignedRecomputeService(
      final ArtifactVersionRepository artifactVersionRepository,
      final VersionSignatureService versionSignatureService,
      @Value("${repsy.maven.signed-recompute.batch-size:200}") final int batchSize) {

    this.artifactVersionRepository = artifactVersionRepository;
    this.versionSignatureService = versionSignatureService;
    this.batchSize = Math.max(1, batchSize);
  }

  /** Starts the recomputation of the toggled repo once the toggle is committed. */
  @Async(MaintenanceTaskExecutorConfig.BEAN_NAME)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onToggled(final PgpVerifyAllSignaturesToggledEvent event) {

    this.recomputeRepo(event.repoId());
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
