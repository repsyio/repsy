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
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleInfo;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleVersionRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.services.GoModuleServiceImpl;
import io.repsy.os.server.protocols.golang.shared.storage.services.GolangStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GolangApiFacade {

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull GolangStorageService golangStorageService;
  private final @NonNull GoModuleServiceImpl goModuleService;
  private final @NonNull GoModuleRepository goModuleRepository;
  private final @NonNull GoModuleVersionRepository goModuleVersionRepository;

  @Transactional
  public void createRepo(final @NonNull UUID repoId) {
    this.golangStorageService.createRepo(repoId);
  }

  @Transactional
  public void deleteRepo(final @NonNull RepoInfo repoInfo) {
    this.golangStorageService.deleteRepo(repoInfo.getStorageKey());
    this.repoTxService.deleteRepo(repoInfo.getStorageKey());
  }

  public @NonNull List<StorageItemInfo> getItems(
      final @NonNull RepoInfo repoInfo, final @NonNull RelativePath relativePath) {
    final var storagePath = new StoragePath(repoInfo.getStorageKey(), relativePath);
    return this.golangStorageService.listDirectory(storagePath);
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

  @Transactional
  public void deleteModule(final @NonNull RepoInfo repoInfo, final @NonNull String modulePath) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoInfo.getStorageKey(), modulePath)
            .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), "/" + modulePath);
    this.golangStorageService.deleteDirectory(storagePath);

    this.goModuleRepository.delete(goModule);
  }

  @Transactional
  public void deleteModuleVersion(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String modulePath,
      final @NonNull String version) {

    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoInfo.getStorageKey(), modulePath)
            .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));

    final var moduleVersion =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .orElseThrow(() -> new ItemNotFoundException("versionNotFound"));

    final var versionStoragePath =
        StoragePath.of(repoInfo.getStorageKey(), "/" + modulePath + "/@v/" + version);
    this.golangStorageService.deleteVersionFiles(versionStoragePath, repoInfo.getName());

    // Soft-delete: keep the DB record so GOPROXY requests return 410 Gone instead of 404
    moduleVersion.setDeleted(true);
    this.goModuleVersionRepository.save(moduleVersion);
  }
}
