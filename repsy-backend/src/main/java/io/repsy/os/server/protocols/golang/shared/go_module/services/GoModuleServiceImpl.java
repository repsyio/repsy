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
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModuleVersion;
import io.repsy.os.server.protocols.golang.shared.go_module.mappers.GoModuleMapper;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleVersionRepository;
import io.repsy.os.shared.error_handling.utils.ConstraintViolations;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.golang.shared.module.services.GoModuleFilesWriter;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class GoModuleServiceImpl implements GoModuleService<UUID> {

  /** The unique index on (module, version), created in {@code V0002__Golang_Protocol.sql}. */
  private static final String VERSION_UNIQUE_CONSTRAINT = "ux_go_module_version__module_id_version";

  private final RepoRepository repoRepository;
  private final GoModuleRepository goModuleRepository;
  private final GoModuleVersionRepository goModuleVersionRepository;
  private final GoModuleMapper goModuleMapper;

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

    final var repo =
        this.repoRepository
            .findById(repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var goModule = this.findOrCreateModule(repo, modulePath);

    final var versionExists =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .isPresent();

    if (versionExists) {
      throw new ItemAlreadyExistException("goModuleVersionAlreadyExists");
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

      throw new ItemAlreadyExistException("goModuleVersionAlreadyExists");
    }

    return filesWriter.write();
  }

  /**
   * Returns the module row, inserting it when this is the first version of the module path.
   *
   * <p>The insert skips a row that already exists instead of failing on the unique index: on
   * PostgreSQL a failed statement aborts the transaction, which also holds the version row and the
   * file writes. When a concurrent first upload has inserted the module but not committed yet, the
   * statement waits for it, and then finds the committed row.
   */
  private GoModule findOrCreateModule(final Repo repo, final String modulePath) {
    final var existing =
        this.goModuleRepository.findByRepoIdAndModulePath(repo.getId(), modulePath);
    if (existing.isPresent()) {
      return existing.get();
    }

    this.goModuleRepository.insertIfAbsent(
        UuidCreator.getTimeOrderedEpoch(), repo.getId(), modulePath, Instant.now());

    return this.goModuleRepository
        .findByRepoIdAndModulePath(repo.getId(), modulePath)
        .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));
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
        .findAllByRepoIdContainsModulePath(repoId, search, pageable)
        .map(this.goModuleMapper::toDto);
  }

  public Page<io.repsy.os.generated.model.GoModuleVersionListItem> getModuleVersions(
      final UUID repoId, final String modulePath, final String search, final Pageable pageable) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoId, modulePath)
            .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));
    return this.goModuleVersionRepository
        .findAllByModuleIdContainsVersion(goModule.getId(), search, pageable)
        .map(this.goModuleMapper::toVersionDto);
  }

  public GoModuleInfo getModuleInfo(final UUID repoId, final String modulePath) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoId, modulePath)
            .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));

    final var versions = this.goModuleVersionRepository.findAllByModuleId(goModule.getId());

    return this.goModuleMapper.toGoModuleInfo(goModule, versions);
  }

  private Optional<String> computeLatestVersion(final List<GoModuleVersionListItem> versions) {
    return versions.stream()
        .map(GoModuleVersionListItem::getVersion)
        .max(GoVersionUtils.COMPARATOR);
  }
}
