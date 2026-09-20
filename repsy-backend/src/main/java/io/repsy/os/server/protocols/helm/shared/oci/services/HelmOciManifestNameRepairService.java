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
package io.repsy.os.server.protocols.helm.shared.oci.services;

import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch;
import io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestRepository;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds and repairs the OCI manifests that were stored under a name other than the name of their
 * chart (RPS-1038).
 *
 * <p>Before RPS-978 a manifest push whose path name differed from the {@code Chart.yaml} name was
 * accepted: the chart and its version were stored under the {@code Chart.yaml} name, the manifest
 * row, its tags and its file under {@code oci/manifests/{name}/} under the path name. The chart
 * page then listed the chart without tags, {@code /api/helm/charts/{repo}/{pathName}} answered 404
 * and the chart could only be pulled under the path name.
 *
 * <p>The repair re-keys such a manifest to the chart name, keeping its reference, and moves its
 * file to the new name. A manifest already stored under the chart name and the same reference wins
 * over the stray one, because that is the name the chart page and the panel API use:
 *
 * <ul>
 *   <li>when the two carry the same digest the stray one is a plain duplicate and is removed;
 *   <li>when they differ, nothing is dropped automatically. The stray manifest is left where it is
 *       and reported as a conflict for an operator to resolve.
 * </ul>
 *
 * <p>The manifest file is a copy that is never read (the manifest is served from its row), so it is
 * rewritten from the row rather than moved, and a failure to move it is logged instead of undoing
 * the repair.
 */
@Slf4j
@NullMarked
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HelmOciManifestNameRepairService {

  private final HelmOciManifestRepository helmOciManifestRepository;
  private final HelmStorageService helmStorageService;

  /** What one run of {@link #repair()} did. */
  public record RepairReport(
      int rekeyed, int duplicatesRemoved, List<HelmOciManifestMismatch> conflicts) {

    public boolean isEmpty() {
      return this.rekeyed == 0 && this.duplicatesRemoved == 0 && this.conflicts.isEmpty();
    }
  }

  /** The manifests stored under a name that differs from the name of their chart. */
  public List<HelmOciManifestMismatch> findMismatches() {
    return this.helmOciManifestRepository.findAllNamedDifferentlyFromChart();
  }

  @Transactional
  public RepairReport repair() {
    final var mismatches = this.findMismatches();

    var rekeyed = 0;
    var duplicatesRemoved = 0;
    final var conflicts = new ArrayList<HelmOciManifestMismatch>();
    final var fileMoves = new ArrayList<PendingFileMove>();

    for (final var mismatch : mismatches) {
      final var manifest = this.helmOciManifestRepository.findById(mismatch.id()).orElse(null);
      if (manifest == null) {
        continue;
      }

      final var target =
          this.helmOciManifestRepository
              .findByRepoIdAndNameAndReference(
                  mismatch.repoId(), mismatch.chartName(), mismatch.reference())
              .orElse(null);

      if (target == null) {
        manifest.setName(mismatch.chartName());
        this.helmOciManifestRepository.saveAndFlush(manifest);
        fileMoves.add(new PendingFileMove(mismatch, manifest.getContent()));
        rekeyed++;
      } else if (target.getDigest().equals(manifest.getDigest())) {
        this.helmOciManifestRepository.delete(manifest);
        this.helmOciManifestRepository.flush();
        fileMoves.add(new PendingFileMove(mismatch, null));
        duplicatesRemoved++;
      } else {
        log.warn(
            "OCI manifest {} of repo {} is stored under {}, but its chart is {} and that name"
                + " already holds a different manifest for {}. Left as it is.",
            mismatch.id(),
            mismatch.repoName(),
            mismatch.name(),
            mismatch.chartName(),
            mismatch.reference());
        conflicts.add(mismatch);
      }
    }

    // The files go last, so a failed database change never leaves them moved.
    fileMoves.forEach(this::moveFile);

    return new RepairReport(rekeyed, duplicatesRemoved, List.copyOf(conflicts));
  }

  private void moveFile(final PendingFileMove move) {
    final var mismatch = move.mismatch();
    try {
      if (move.content() != null) {
        this.helmStorageService.saveManifest(
            mismatch.repoId(),
            mismatch.chartName(),
            mismatch.reference(),
            move.content().getBytes(StandardCharsets.UTF_8),
            mismatch.repoName());
      }
      this.helmStorageService.deleteManifestFile(
          mismatch.repoId(), mismatch.name(), mismatch.reference(), mismatch.repoName());
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "Could not move the file of OCI manifest {} of repo {} from {} to {}",
          mismatch.reference(),
          mismatch.repoName(),
          mismatch.name(),
          mismatch.chartName(),
          e);
    }
  }

  /**
   * The file work that follows a repaired row.
   *
   * @param mismatch the manifest as it was stored
   * @param content what to write under the chart name, or {@code null} to only drop the old file
   */
  private record PendingFileMove(HelmOciManifestMismatch mismatch, @Nullable String content) {}
}
