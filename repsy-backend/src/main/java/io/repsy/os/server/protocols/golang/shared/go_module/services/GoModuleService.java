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
package io.repsy.os.server.protocols.golang.shared.go_module.services;

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.core.web.utils.LikePatterns;
import io.repsy.core.web_error.ConstraintViolations;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModuleVersion;
import io.repsy.os.server.protocols.golang.shared.go_module.mappers.GoModuleMapper;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleVersionRepository;
import io.repsy.os.server.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.golang.shared.module.services.AbstractGoModuleService;
import io.repsy.protocols.golang.shared.module.services.GoModuleFilesWriter;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class GoModuleService extends AbstractGoModuleService<UUID> {

  /** The unique index on (module, version), created in {@code V0002__Golang_Protocol.sql}. */
  private static final String VERSION_UNIQUE_CONSTRAINT = "ux_go_module_version__module_id_version";

  /** How often a publish takes the module row again after a delete removed it. */
  private static final int MAX_MODULE_LOCK_ATTEMPTS = 5;

  private final RepoTxService repoTxService;
  private final GoModuleRepository goModuleRepository;
  private final GoModuleVersionRepository goModuleVersionRepository;
  private final GoModuleMapper goModuleMapper;
  private final GoStorageService goStorageService;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional(rollbackFor = IOException.class)
  public BaseUsages publishModule(
      final BaseRepoInfo<UUID> repoInfo,
      final String modulePath,
      final String version,
      final @Nullable String goVersion,
      final String modHash,
      final String zipHash,
      final GoModuleFilesWriter filesWriter)
      throws IOException {

    final var repo = this.repoTxService.requireRepo(repoInfo.getStorageKey());

    final var goModule = this.findOrCreateModule(repo, modulePath);

    final var versionExists =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .isPresent();

    if (versionExists) {
      throw new ItemAlreadyExistException(ProtocolErrorCodes.GO_MODULE_VERSION_ALREADY_EXISTS);
    }

    final var moduleVersion = new GoModuleVersion();
    moduleVersion.setGoModule(goModule);
    moduleVersion.setVersion(version);
    moduleVersion.setGoVersion(goVersion);
    moduleVersion.setModHash(modHash);
    moduleVersion.setZipHash(zipHash);

    // Flush so a unique-index conflict (a concurrent upload of the same version) fails here, before
    // any file is written. The transaction, and the row lock it holds, stays open while the files
    // are written, so a losing upload waits for the winner instead of replacing its files.
    try {
      this.goModuleVersionRepository.saveAndFlush(moduleVersion);
    } catch (final DataIntegrityViolationException e) {
      // Only that index means the version exists. Any other violation is not the client's
      // conflict, so it is left to surface as the server error it is.
      if (!ConstraintViolations.violatesConstraint(e, VERSION_UNIQUE_CONSTRAINT)) {
        throw e;
      }

      throw new ItemAlreadyExistException(ProtocolErrorCodes.GO_MODULE_VERSION_ALREADY_EXISTS);
    }

    return filesWriter.write();
  }

  /**
   * Returns the module row, share-locked, inserting it when this is the first version of the module
   * path.
   *
   * <p>The share lock is what serialises a publish with the delete of the module's last version
   * (RPS-1288), which removes the module row: the delete locks the row for update, so it waits for
   * the publishes that hold the row, and a publish waits for a delete that holds it. The lock is
   * held until the publish commits, so a version that is being written is always counted by a
   * delete that follows. A publish that waited for a delete which removed the row finds it gone
   * once the lock is granted, and inserts the row again.
   *
   * <p>The insert skips a row that already exists instead of failing on the unique index: on
   * PostgreSQL a failed statement aborts the transaction, which also holds the version row and the
   * file writes. When a concurrent first upload has inserted the module but not committed yet, the
   * statement waits for it, and then finds the committed row.
   */
  private GoModule findOrCreateModule(final Repo repo, final String modulePath) {
    for (var attempt = 0; attempt < MAX_MODULE_LOCK_ATTEMPTS; attempt++) {
      final var existing =
          this.goModuleRepository.findSharedByRepoIdAndModulePath(repo.getId(), modulePath);
      if (existing.isPresent()) {
        return existing.get();
      }

      this.goModuleRepository.insertIfAbsent(
          UuidCreator.getTimeOrderedEpoch(), repo.getId(), modulePath, Instant.now());

      // The row that was just inserted, or the one a concurrent upload inserted, is locked here.
      // It is absent only when a delete removed it again in between: then go round once more.
      final var inserted =
          this.goModuleRepository.findSharedByRepoIdAndModulePath(repo.getId(), modulePath);
      if (inserted.isPresent()) {
        return inserted.get();
      }
    }

    // Every attempt lost its row to a concurrent delete of the module: a conflict that a retry
    // resolves.
    throw new ItemAlreadyExistException(ProtocolErrorCodes.GO_MODULE_BUSY);
  }

  @Override
  public Optional<String> findLatestPublishedVersion(
      final BaseRepoInfo<UUID> repoInfo, final String modulePath) {

    return this.findModule(repoInfo.getStorageKey(), modulePath)
        .flatMap(
            module ->
                this.computeLatestVersion(
                    this.goModuleVersionRepository.findAllByModuleId(module.getId())));
  }

  /**
   * Looks a module up by its exact, case-preserved path, falling back to the path's all-lower-case
   * spelling only when no row with the exact case exists (RPS-1232). Before this ticket, every
   * module path was lower-cased before being stored, so a module published under a mixed-case URL
   * has, in the database, only ever existed under its lower-cased spelling. Rather than migrating
   * those rows, a lookup for the real (mixed) case that finds nothing falls back once to the
   * lower-cased row, so it keeps resolving for a client that has always used the module's real
   * case. A path that is already all-lower-case is unaffected: the fallback path equals the
   * requested one.
   */
  private Optional<GoModule> findModule(final UUID repoId, final String modulePath) {
    final var exact = this.goModuleRepository.findByRepoIdAndModulePath(repoId, modulePath);
    if (exact.isPresent()) {
      return exact;
    }
    final var lowerCasePath = modulePath.toLowerCase(Locale.ROOT);
    if (lowerCasePath.equals(modulePath)) {
      return Optional.empty();
    }
    return this.goModuleRepository.findByRepoIdAndModulePath(repoId, lowerCasePath);
  }

  public Page<io.repsy.os.generated.model.GoModuleListItem> getModules(
      final UUID repoId, final Pageable pageable) {
    return this.goModuleRepository
        .findAllByRepoId(repoId, pageable)
        .map(this.goModuleMapper::toDto);
  }

  public Page<io.repsy.os.generated.model.GoModuleListItem> getModulesContainsPath(
      final UUID repoId, final String search, final Pageable pageable) {
    return this.goModuleRepository
        .findAllByRepoIdContainsModulePath(repoId, LikePatterns.of("%", search, "%"), pageable)
        .map(this.goModuleMapper::toDto);
  }

  public Page<io.repsy.os.generated.model.GoModuleVersionListItem> getModuleVersions(
      final UUID repoId, final String modulePath, final String search, final Pageable pageable) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoId, modulePath)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.MODULE_NOT_FOUND));
    return this.goModuleVersionRepository
        .findAllByModuleIdContainsVersion(goModule.getId(), search, pageable)
        .map(this.goModuleMapper::toVersionDto);
  }

  public GoModuleInfo getModuleInfo(final UUID repoId, final String modulePath) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoId, modulePath)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.MODULE_NOT_FOUND));

    final var versions = this.goModuleVersionRepository.findAllByModuleId(goModule.getId());

    return this.goModuleMapper.toGoModuleInfo(goModule, versions);
  }

  /**
   * Deletes the module with its versions, rows and files, in one transaction that holds the module
   * row's lock: a publish of the module waits for it, and finds the module gone (RPS-1288).
   *
   * @return the usage the deletion frees, to be given back to the repo
   */
  @Transactional
  public BaseUsages deleteModule(final RepoInfo repoInfo, final String modulePath) {
    final var goModule = this.lockModule(repoInfo, modulePath);

    final var versions = this.goModuleVersionRepository.findVersionsByModuleId(goModule.getId());

    return this.removeModule(repoInfo, goModule, versions, 0L);
  }

  /**
   * Deletes the version, and the module with it when that was its last version (RPS-1288): a module
   * without a version has nothing to serve, and would only be a row that the module list shows and
   * the wire does not know. The module row is locked first, so a publish of the module, which
   * shares that lock, is either counted here or finds the module gone and creates it again.
   *
   * @return the usage the deletion frees, to be given back to the repo
   */
  @Transactional
  public BaseUsages deleteModuleVersion(
      final RepoInfo repoInfo, final String modulePath, final String version) {

    final var goModule = this.lockModule(repoInfo, modulePath);

    final var moduleVersion =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.VERSION_NOT_FOUND));

    this.goModuleVersionRepository.delete(moduleVersion);
    // Flush so a rejection by the database fails here, before any file is removed, and so the
    // count below does not include the row.
    this.goModuleVersionRepository.flush();

    final var versionFilesBytes =
        this.goStorageService.deleteVersionFiles(
            StoragePath.of(
                repoInfo.getStorageKey(),
                "/" + GoVersionUtils.escapeModulePath(modulePath) + "/@v/" + version),
            repoInfo.getName());

    if (this.goModuleVersionRepository.countByGoModuleId(goModule.getId()) == 0L) {
      return this.removeModule(repoInfo, goModule, List.of(version), versionFilesBytes);
    }

    this.publishVersionDeleted(repoInfo, modulePath, version);

    return BaseUsages.ofDisk(-versionFilesBytes);
  }

  private GoModule lockModule(final RepoInfo repoInfo, final String modulePath) {
    return this.goModuleRepository
        .findLockedByRepoIdAndModulePath(repoInfo.getStorageKey(), modulePath)
        .orElseThrow(() -> new ItemNotFoundException(ProtocolErrorCodes.MODULE_NOT_FOUND));
  }

  /**
   * Removes the module row and what is left of its files, and reports the events of the versions
   * that went with it.
   *
   * <p>Only the module's own {@code @v} directory is removed, never the module's directory: the
   * directory of {@code example.com/mod} also holds {@code example.com/mod/v2}, a module of its
   * own. modulePath is the DB's case-preserved path and storage keys the module by its !-escaped
   * form (RPS-1232), which is the same for an all-lower-case path such as a legacy module's.
   */
  private BaseUsages removeModule(
      final RepoInfo repoInfo,
      final GoModule goModule,
      final List<String> deletedVersions,
      final long alreadyFreedBytes) {

    this.goModuleRepository.delete(goModule);
    // Flush so a rejection by the database fails here, before any file is removed.
    this.goModuleRepository.flush();

    final var freedBytes =
        alreadyFreedBytes
            + this.goStorageService.deleteDirectory(
                StoragePath.of(
                    repoInfo.getStorageKey(),
                    "/" + GoVersionUtils.escapeModulePath(goModule.getModulePath()) + "/@v"));

    deletedVersions.forEach(
        deleted -> this.publishVersionDeleted(repoInfo, goModule.getModulePath(), deleted));

    return BaseUsages.ofDisk(-freedBytes);
  }

  private void publishVersionDeleted(
      final RepoInfo repoInfo, final String modulePath, final String version) {

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            modulePath,
            version));
  }

  /**
   * Picks the version {@code @latest} serves. Delegates to {@link GoVersionUtils#latestOf}, the go
   * command's own "latest" version query (go.dev/ref/mod#version-queries), not a plain max over
   * every published version (RPS-1720 C8/RPS-1733) -- the same rule {@link GoModuleMapper} uses for
   * the panel's {@code latestVersion}, so the wire protocol and the panel agree.
   */
  private Optional<String> computeLatestVersion(final List<GoModuleVersionListItem> versions) {
    return GoVersionUtils.latestOf(
        versions.stream().map(GoModuleVersionListItem::getVersion).toList());
  }
}
