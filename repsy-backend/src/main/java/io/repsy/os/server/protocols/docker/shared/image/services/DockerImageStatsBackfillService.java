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
package io.repsy.os.server.protocols.docker.shared.image.services;

import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Fills {@code docker_image.size} and {@code docker_image.digest} for the images a release before
 * RPS-1216 pushed without ever computing them (RPS-1563): the panel lists such an image with size 0
 * and no digest until it is pushed again, on any release, because nothing but a push recomputes
 * them. {@link ImageTxService#refreshImageSize} does that from rows already in the database (the
 * layers the image's tags reach and the tag most recently moved), so unlike {@code
 * DockerManifestLayoutRepairService} this needs no read of a stored file: an image that still has a
 * tag but no digest is recomputed the same way a push would.
 *
 * <p>The service is idempotent and resumable, the same way the manifest layout repair is: a batch
 * of candidate ids is recomputed one at a time, a recomputed image no longer matches the query that
 * found it (it now has a digest), and a run that finds nothing costs one query.
 */
@Slf4j
@NullMarked
@Service
@RequiredArgsConstructor
public class DockerImageStatsBackfillService {

  private static final int BATCH_SIZE = 200;

  private final ImageRepository imageRepository;
  private final ImageTxService imageTxService;

  /**
   * What one run of {@link #backfill()} did.
   *
   * @param refreshed images whose size and digest are now filled
   * @param failed images whose recomputation failed with an error (logged), retried by the next run
   */
  public record BackfillReport(int refreshed, int failed) {

    public boolean isEmpty() {
      return this.refreshed == 0 && this.failed == 0;
    }
  }

  private enum Outcome {
    REFRESHED,
    FAILED
  }

  public BackfillReport backfill() {

    final var outcomes = new EnumMap<Outcome, Integer>(Outcome.class);

    var rows = this.imageRepository.findBackfillableIds(PageRequest.ofSize(BATCH_SIZE));

    while (!rows.isEmpty()) {
      for (final var row : rows) {
        outcomes.merge(this.refreshOne(row), 1, Integer::sum);
      }

      rows =
          this.imageRepository.findBackfillableIdsAfter(
              lastImageId(rows), PageRequest.ofSize(BATCH_SIZE));
    }

    return new BackfillReport(
        outcomes.getOrDefault(Outcome.REFRESHED, 0), outcomes.getOrDefault(Outcome.FAILED, 0));
  }

  private Outcome refreshOne(final ImageRepository.ImageStatsBackfillRow row) {

    try {
      this.imageTxService.refreshImageSize(row.getRepoId(), row.getImageId());
      return Outcome.REFRESHED;
    } catch (final RuntimeException e) {
      log.error(
          "Could not backfill the size and digest of Docker image {} in repo {}",
          row.getImageId(),
          row.getRepoId(),
          e);
      return Outcome.FAILED;
    }
  }

  private static UUID lastImageId(final List<ImageRepository.ImageStatsBackfillRow> rows) {
    return rows.get(rows.size() - 1).getImageId();
  }
}
