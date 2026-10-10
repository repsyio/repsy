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
package io.repsy.protocols.helm.protocol.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.shared.chart.services.ChartService;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestInfo;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushForm;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciManifestPushResult;
import io.repsy.protocols.helm.shared.oci.dtos.HelmOciTagListInfo;
import io.repsy.protocols.helm.shared.oci.services.OciManifestService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.storage.RepoRef;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.StoredUpload;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The OCI manifest side of the Helm facade: lookups, the tag list and the manifest push that writes
 * the chart version, the manifest row and the manifest file as one unit.
 */
@Slf4j
@NullMarked
@RequiredArgsConstructor
final class HelmOciManifestFacade<ID> {

  private final HelmStorageService<ID> helmStorageService;
  private final ChartService<ID> chartService;
  private final OciManifestService<ID> ociManifestService;

  Optional<HelmOciManifestInfo> checkManifest(
      final ProtocolContext context, final String name, final String reference) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.ociManifestService.findByNameAndReference(repoInfo.getId(), name, reference);
  }

  HelmOciManifestInfo getManifest(
      final ProtocolContext context, final String name, final String reference) throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    return this.ociManifestService
        .findByNameAndReference(repoInfo.getId(), name, reference)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.MANIFEST_NOT_FOUND));
  }

  /**
   * Writes the chart version, the manifest and the manifest file in the one transaction the caller
   * opened (RPS-1354). The chart row is locked first ({@link ChartService#getOrCreate}), which is
   * what makes pushes of one chart take turns (RPS-1273) and now also holds the turn until the
   * manifest is written. Both rows are flushed before the file is written, the order RPS-1124 set
   * for the classic upload: a row the database refuses never reaches storage, and a file that
   * cannot be written rolls the rows back. A brand-new manifest whose file fails has its partial
   * file removed; a manifest being replaced keeps its row, and so its file path, until the next
   * successful push.
   */
  HelmOciManifestPushResult pushManifest(
      final ProtocolContext context, final HelmOciManifestPushForm form, final byte[] contentBytes)
      throws IOException {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var chart = this.chartService.getOrCreate(form.getChart(), repoInfo.getId());
    // Looked up after the chart lock: a push that waited for another one must see the manifest
    // that one committed, not the row as it was before the wait.
    final var replaced =
        this.ociManifestService
            .findByNameAndReference(repoInfo.getId(), form.getName(), form.getReference())
            .isPresent();
    final var manifest =
        this.ociManifestService.save(
            HelmOciManifestForm.builder()
                .chartId(chart.id())
                .name(form.getName())
                .reference(form.getReference())
                .digest(form.getDigest())
                .mediaType(form.getMediaType())
                .content(form.getContent())
                .build(),
            repoInfo.getId());

    // What a replaced manifest's file held, to be put back when the transaction does not commit.
    final var previous = replaced ? this.readManifestFile(repoInfo, form) : null;

    return StoredUpload.storeOrDiscard(
        () -> {
          final var usages =
              this.helmStorageService.saveManifest(
                  RepoRef.of(repoInfo), form.getName(), form.getReference(), contentBytes);
          this.undoManifestFileUnlessCommitted(repoInfo, form, contentBytes, previous, replaced);
          return new HelmOciManifestPushResult(manifest, usages);
        },
        () ->
            this.helmStorageService.deleteManifestFile(
                RepoRef.of(repoInfo), form.getName(), form.getReference()),
        replaced,
        log,
        Level.DEBUG,
        "manifest " + form.getName() + ":" + form.getReference());
  }

  /**
   * The file is the last write of the unit, so everything that fails before the commit rolls the
   * rows back without a file to undo. What can still fail is the commit itself (a lost connection,
   * a serialization failure at commit): the rows are then rolled back and the file, already
   * written, holds bytes no row describes (RPS-1366). This hook runs once the transaction is over
   * and, when it rolled back, removes the file of a new manifest or puts back the bytes a replaced
   * one had.
   *
   * <p>It runs after the database locks are released, so a later push of the same manifest may
   * already have written the file. The file is therefore touched only while it still holds the
   * bytes this unit wrote. When the outcome is unknown (the commit failed with a transaction
   * exception that says nothing about whether it took effect) the file is kept: a file no row
   * refers to is harmless, a missing one for a row that did commit is not.
   */
  private void undoManifestFileUnlessCommitted(
      final BaseRepoInfo<ID> repoInfo,
      final HelmOciManifestPushForm form,
      final byte[] written,
      final byte @Nullable [] previous,
      final boolean replaced) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(final int status) {
            if (status == STATUS_COMMITTED) {
              return;
            }

            if (status != STATUS_ROLLED_BACK) {
              log.warn(
                  "The outcome of the push of manifest {}:{} is unknown, its file is kept",
                  form.getName(),
                  form.getReference());
              return;
            }

            HelmOciManifestFacade.this.undoManifestFile(
                repoInfo, form, written, previous, replaced);
          }
        });
  }

  private void undoManifestFile(
      final BaseRepoInfo<ID> repoInfo,
      final HelmOciManifestPushForm form,
      final byte[] written,
      final byte @Nullable [] previous,
      final boolean replaced) {

    try {
      final var current = this.readManifestFile(repoInfo, form);
      if (current == null || !Arrays.equals(current, written)) {
        // Removed, or already written again by a later push: not ours to touch.
        return;
      }

      if (previous != null) {
        this.helmStorageService.saveManifest(
            RepoRef.of(repoInfo), form.getName(), form.getReference(), previous);
      } else if (!replaced) {
        this.helmStorageService.deleteManifestFile(
            RepoRef.of(repoInfo), form.getName(), form.getReference());
      }
    } catch (final RuntimeException e) {
      log.warn(
          "The file of manifest {}:{} of a push that did not commit could not be undone: {}",
          form.getName(),
          form.getReference(),
          e.getMessage());
    }
  }

  private byte @Nullable [] readManifestFile(
      final BaseRepoInfo<ID> repoInfo, final HelmOciManifestPushForm form) {

    try {
      final var resource =
          this.helmStorageService.findManifest(
              RepoRef.of(repoInfo), form.getName(), form.getReference());
      if (resource.isEmpty() || !resource.get().exists()) {
        return null;
      }

      return resource.get().getContentAsByteArray();
    } catch (final IOException | RuntimeException e) {
      log.debug(
          "The file of manifest {}:{} could not be read: {}",
          form.getName(),
          form.getReference(),
          e.getMessage());
      return null;
    }
  }

  HelmOciTagListInfo listTags(final ProtocolContext context, final String name) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    // listTagsByName's references include pushes by digest (sha256:...), which the distribution
    // spec's tags/list must not return, and are ordered newest-first, where the spec wants
    // lexical order. The filtering/sorting stays here rather than in OciManifestService because
    // the panel (HelmApiFacade) calls listTagsByName directly and relies on its raw, unfiltered,
    // createdAt-desc output (see HelmOciManifestNameRepairServiceIT).
    final var tags =
        this.ociManifestService.listTagsByName(repoInfo.getId(), name).stream()
            .filter(reference -> !reference.startsWith(HelmConstants.SHA256_PREFIX))
            .sorted()
            .toList();
    // The distribution spec's name is the whole repository name, <repo>/<chart> here, as the
    // Docker registry answers it (RPS-1489); the bare chart name was ambiguous between repos.
    return HelmOciTagListInfo.builder().name(repoInfo.getName() + "/" + name).tags(tags).build();
  }
}
