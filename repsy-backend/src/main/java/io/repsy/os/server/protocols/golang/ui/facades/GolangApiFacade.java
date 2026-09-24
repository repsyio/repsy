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
package io.repsy.os.server.protocols.golang.ui.facades;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.generated.model.GoModuleListItem;
import io.repsy.os.generated.model.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleVersionRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.services.GoModuleServiceImpl;
import io.repsy.os.server.protocols.golang.shared.storage.services.GolangStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GolangApiFacade implements ProtocolApiFacade {

  private final @NonNull GolangStorageService golangStorageService;
  private final @NonNull GoModuleServiceImpl goModuleService;
  private final @NonNull GoModuleRepository goModuleRepository;
  private final @NonNull GoModuleVersionRepository goModuleVersionRepository;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  @Transactional
  @Override
  public void createRepo(final @NonNull UUID repoId) {
    this.golangStorageService.createRepo(repoId);
  }

  @Transactional
  @Override
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {

    this.golangStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  public @NonNull Page<GoModuleListItem> getModules(
      final @NonNull UUID repoId, final @NonNull Pageable pageable) {
    return this.goModuleService.getModules(repoId, pageable);
  }

  public @NonNull Page<GoModuleListItem> searchModules(
      final @NonNull UUID repoId, final @NonNull String search, final @NonNull Pageable pageable) {
    return this.goModuleService.getModulesContainsPath(repoId, search, pageable);
  }

  public @NonNull Page<GoModuleVersionListItem> getModuleVersions(
      final @NonNull UUID repoId,
      final @NonNull String modulePath,
      final @NonNull String search,
      final @NonNull Pageable pageable) {
    return this.goModuleService.getModuleVersions(repoId, modulePath, search, pageable);
  }

  public @NonNull GoModuleInfo getModuleInfo(
      final @NonNull UUID repoId, final @NonNull String modulePath) {
    return this.goModuleService.getModuleInfo(repoId, modulePath);
  }

  /**
   * Deletes the module with its versions, rows and files, in one transaction that holds the module
   * row's lock: a publish of the module waits for it, and finds the module gone (RPS-1288).
   *
   * @return the usage the deletion frees, to be given back to the repo
   */
  @Transactional
  public @NonNull BaseUsages deleteModule(
      final @NonNull RepoInfo repoInfo, final @NonNull String modulePath) {
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
  public @NonNull BaseUsages deleteModuleVersion(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String modulePath,
      final @NonNull String version) {

    final var goModule = this.lockModule(repoInfo, modulePath);

    final var moduleVersion =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException("versionNotFound"));

    this.goModuleVersionRepository.delete(moduleVersion);
    // Flush so a rejection by the database fails here, before any file is removed, and so the
    // count below does not include the row.
    this.goModuleVersionRepository.flush();

    final var versionFilesBytes =
        this.golangStorageService.deleteVersionFiles(
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

  private @NonNull GoModule lockModule(
      final @NonNull RepoInfo repoInfo, final @NonNull String modulePath) {
    return this.goModuleRepository
        .findLockedByRepoIdAndModulePath(repoInfo.getStorageKey(), modulePath)
        .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));
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
  private @NonNull BaseUsages removeModule(
      final @NonNull RepoInfo repoInfo,
      final @NonNull GoModule goModule,
      final @NonNull List<String> deletedVersions,
      final long alreadyFreedBytes) {

    this.goModuleRepository.delete(goModule);
    // Flush so a rejection by the database fails here, before any file is removed.
    this.goModuleRepository.flush();

    final var freedBytes =
        alreadyFreedBytes
            + this.golangStorageService.deleteDirectory(
                StoragePath.of(
                    repoInfo.getStorageKey(),
                    "/" + GoVersionUtils.escapeModulePath(goModule.getModulePath()) + "/@v"));

    deletedVersions.forEach(
        deleted -> this.publishVersionDeleted(repoInfo, goModule.getModulePath(), deleted));

    return BaseUsages.ofDisk(-freedBytes);
  }

  private void publishVersionDeleted(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String modulePath,
      final @NonNull String version) {

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            modulePath,
            version));
  }
}
