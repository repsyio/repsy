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

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleInfo;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModuleVersion;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleVersionRepository;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class GoModuleServiceImpl implements GoModuleService<UUID> {

  private final RepoRepository repoRepository;
  private final GoModuleRepository goModuleRepository;
  private final GoModuleVersionRepository goModuleVersionRepository;

  @Override
  @Transactional
  public void createOrUpdateModule(
      final BaseRepoInfo<UUID> repoInfo, final String modulePath, final String version) {

    final var repo =
        this.repoRepository
            .findById(repoInfo.getStorageKey())
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repo.getId(), modulePath)
            .orElseGet(
                () -> {
                  final var newModule = new GoModule();
                  newModule.setRepo(repo);
                  newModule.setModulePath(modulePath);
                  return this.goModuleRepository.save(newModule);
                });

    final var versionExists =
        this.goModuleVersionRepository
            .findByGoModuleIdAndVersion(goModule.getId(), version)
            .isPresent();

    if (!versionExists) {
      final var moduleVersion = new GoModuleVersion();
      moduleVersion.setGoModule(goModule);
      moduleVersion.setVersion(version);
      this.goModuleVersionRepository.save(moduleVersion);
    }

    final var currentLatest = goModule.getLatestVersion();
    if (currentLatest == null
        || GoVersionUtils.COMPARATOR.compare(version, currentLatest) > 0) {
      goModule.setLatestVersion(version);
      this.goModuleRepository.save(goModule);
    }
  }

  @Override
  @Transactional
  public void updateGoVersion(
      final BaseRepoInfo<UUID> repoInfo,
      final String modulePath,
      final String version,
      final @Nullable String goVersion) {

    this.goModuleRepository
        .findByRepoIdAndModulePath(repoInfo.getStorageKey(), modulePath)
        .ifPresent(
            goModule ->
                this.goModuleVersionRepository
                    .findByGoModuleIdAndVersion(goModule.getId(), version)
                    .ifPresent(
                        moduleVersion -> {
                          moduleVersion.setGoVersion(goVersion);
                          this.goModuleVersionRepository.save(moduleVersion);
                        }));
  }

  public Page<GoModuleListItem> getModules(final UUID repoId, final Pageable pageable) {
    return this.goModuleRepository.findAllByRepoId(repoId, pageable);
  }

  public Page<GoModuleListItem> getModulesContainsPath(
      final UUID repoId, final String search, final Pageable pageable) {
    return this.goModuleRepository.findAllByRepoIdContainsModulePath(repoId, search, pageable);
  }

  public GoModuleInfo getModuleInfo(final UUID repoId, final String modulePath) {
    final var goModule =
        this.goModuleRepository
            .findByRepoIdAndModulePath(repoId, modulePath)
            .orElseThrow(() -> new ItemNotFoundException("moduleNotFound"));

    final var versions =
        this.goModuleVersionRepository.findAllByModuleId(goModule.getId());

    return GoModuleInfo.builder()
        .id(goModule.getId())
        .modulePath(goModule.getModulePath())
        .latestVersion(goModule.getLatestVersion())
        .createdAt(goModule.getCreatedAt())
        .versions(versions)
        .build();
  }
}
