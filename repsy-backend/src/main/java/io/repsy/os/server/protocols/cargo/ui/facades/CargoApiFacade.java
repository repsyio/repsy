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
package io.repsy.os.server.protocols.cargo.ui.facades;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.cargo.shared.crate.services.CargoCrateServiceImpl;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionListItem;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
@NullMarked
public class CargoApiFacade {

  private final RepoTxService repoTxService;
  private final CargoCrateServiceImpl cargoCrateService;
  private final CargoStorageService cargoStorageService;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public BaseUsages deleteRepo(final RepoInfo repoInfo) throws IOException {

    final var free = this.cargoStorageService.deleteRepo(repoInfo.getId());

    return BaseUsages.builder().diskUsage(-1L * free).build();
  }

  @Transactional(readOnly = true)
  public RepoSettingsInfo getSettings(final RepoInfo repoInfo) {

    return RepoSettingsInfo.builder()
        .privateRepo(repoInfo.isPrivateRepo())
        .searchable(repoInfo.isSearchable())
        .allowOverride(repoInfo.isAllowOverride())
        .build();
  }

  public void updateSettings(final RepoInfo repoInfo, final RepoSettingsForm settings) {

    this.repoTxService.updateSettings(repoInfo.getStorageKey(), settings);
  }

  @Transactional(readOnly = true)
  public Page<CrateListItem> search(
      final RepoInfo repoInfo, final String query, final Pageable pageable) {

    return this.cargoCrateService.search(repoInfo, query, pageable);
  }

  @Transactional(readOnly = true)
  public BaseCrateInfo<UUID> getCrate(final RepoInfo repoInfo, final String name) {

    return this.cargoCrateService.getCrate(repoInfo, name);
  }

  @Transactional(readOnly = true)
  public BaseCrateVersionInfo<UUID> getCrateVersion(
      final RepoInfo repoInfo, final String name, final String vers) {

    return this.cargoCrateService.getCrateVersion(repoInfo, name, vers);
  }

  @Transactional(readOnly = true)
  public Page<CrateVersionListItem> getCrateVersions(
      final RepoInfo repoInfo, final String name, final String query, final Pageable pageable) {

    return this.cargoCrateService.getCrateVersions(repoInfo, name, query, pageable);
  }

  public BaseUsages deleteCrate(final RepoInfo repoInfo, final String name) throws IOException {

    final var normalizedName = normalizeName(name);

    this.cargoCrateService.deleteCrate(repoInfo, normalizedName);

    final var usage =
        this.cargoStorageService.deletePackage(
            repoInfo.getStorageKey(), repoInfo.getName(), normalizedName);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  public BaseUsages deleteCrateVersion(
      final RepoInfo repoInfo, final String name, final String vers) throws IOException {

    final var normalizedName = normalizeName(name);

    this.cargoCrateService.deleteCrateVersion(repoInfo, normalizedName, vers);

    final var remainingEntries = this.cargoCrateService.getIndexEntries(repoInfo, normalizedName);

    if (remainingEntries.isEmpty()) {
      final var usage =
          this.cargoStorageService.deletePackage(
              repoInfo.getStorageKey(), repoInfo.getName(), normalizedName);
      return BaseUsages.builder().diskUsage(-1L * usage).build();
    }

    final List<String> jsonLines =
        remainingEntries.stream()
            .map(
                entry -> {
                  try {
                    return this.objectMapper.writeValueAsString(entry);
                  } catch (final Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .toList();

    final var indexDelta =
        this.cargoStorageService.rewriteIndex(
            repoInfo.getStorageKey(), repoInfo.getName(), normalizedName, jsonLines);

    final var crateFreed =
        this.cargoStorageService.deleteCrate(
            repoInfo.getStorageKey(), repoInfo.getName(), normalizedName, vers);

    return BaseUsages.builder().diskUsage(-1L * crateFreed + indexDelta).build();
  }

  public void createRepo(final UUID repoId) {

    this.cargoStorageService.createRepo(repoId);
  }

  private static String normalizeName(final String name) {
    return name.toLowerCase().replace('-', '_');
  }
}
