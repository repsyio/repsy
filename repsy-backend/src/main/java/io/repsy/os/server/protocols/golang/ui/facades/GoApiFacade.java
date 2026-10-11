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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.generated.model.GoModuleInfo;
import io.repsy.os.generated.model.GoModuleListItem;
import io.repsy.os.generated.model.GoModuleVersionListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.services.GoModuleService;
import io.repsy.os.server.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoApiFacade implements ProtocolApiFacade {

  private final GoStorageService golangStorageService;
  private final GoModuleService goModuleService;

  @Transactional
  @Override
  public void createRepo(final UUID repoId) {
    this.golangStorageService.createRepo(repoId);
  }

  @Transactional
  @Override
  public void deleteRepo(final RepoInfo repoInfo) {

    this.golangStorageService.deleteRepo(repoInfo.getStorageKey());
  }

  public Page<GoModuleListItem> searchModules(
      final UUID repoId, final String search, final Pageable pageable) {
    return this.goModuleService.getModulesContainsPath(repoId, search, pageable);
  }

  public Page<GoModuleVersionListItem> getModuleVersions(
      final UUID repoId, final String modulePath, final String search, final Pageable pageable) {
    return this.goModuleService.getModuleVersions(repoId, modulePath, search, pageable);
  }

  public GoModuleInfo getModuleInfo(final UUID repoId, final String modulePath) {
    return this.goModuleService.getModuleInfo(repoId, modulePath);
  }

  /**
   * Deletes the module with its versions, rows and files, in one transaction that holds the module
   * row's lock (RPS-1288).
   *
   * @return the usage the deletion frees, to be given back to the repo
   */
  @Transactional
  public BaseUsages deleteModule(final RepoInfo repoInfo, final String modulePath) {
    return this.goModuleService.deleteModule(repoInfo, modulePath);
  }

  /**
   * Deletes the version, and the module with it when that was its last version (RPS-1288).
   *
   * @return the usage the deletion frees, to be given back to the repo
   */
  @Transactional
  public BaseUsages deleteModuleVersion(
      final RepoInfo repoInfo, final String modulePath, final String version) {
    return this.goModuleService.deleteModuleVersion(repoInfo, modulePath, version);
  }
}
