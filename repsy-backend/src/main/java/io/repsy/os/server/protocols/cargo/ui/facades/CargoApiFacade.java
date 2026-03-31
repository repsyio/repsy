package io.repsy.os.server.protocols.cargo.ui.facades;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories.CargoCrateRepository;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.services.CargoCrateServiceImpl;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
@NullMarked
public class CargoApiFacade {

  private final RepoTxService repoTxService;
  private final CargoCrateServiceImpl cargoCrateService;
  private final CargoStorageService cargoStorageService;
  private final CargoCrateRepository crateRepository;

  public void deleteRepo(final RepoInfo repoInfo) {

    final var crates = this.crateRepository.findAllByRepoId(repoInfo.getStorageKey());

    for (final var crate : crates) {
      this.cargoStorageService.deletePackage(
          repoInfo.getStorageKey(), repoInfo.getName(), crate.getName());
    }

    this.crateRepository.deleteAll(crates);

    this.repoTxService.deleteRepo(repoInfo.getStorageKey());
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
  public CrateInfo getCrate(final RepoInfo repoInfo, final String name) {

    return this.cargoCrateService.getCrate(repoInfo, name);
  }

  @Transactional(readOnly = true)
  public CrateVersionInfo getCrateVersion(
      final RepoInfo repoInfo, final String name, final String vers) {

    return this.cargoCrateService.getCrateVersion(repoInfo, name, vers);
  }

  public BaseUsages deleteCrate(final RepoInfo repoInfo, final String name) {

    final var normalizedName = normalizeName(name);

    this.cargoCrateService.deleteCrate(repoInfo, normalizedName);

    final var usage =
        this.cargoStorageService.deletePackage(
            repoInfo.getStorageKey(), repoInfo.getName(), normalizedName);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  public BaseUsages deleteCrateVersion(
      final RepoInfo repoInfo, final String name, final String vers) {

    final var normalizedName = normalizeName(name);

    this.cargoCrateService.deleteCrateVersion(repoInfo, normalizedName, vers);

    final var usage =
        this.cargoStorageService.deleteCrate(
            repoInfo.getStorageKey(), repoInfo.getName(), normalizedName, vers);

    return BaseUsages.builder().diskUsage(-1L * usage).build();
  }

  public void createRepo(final UUID repoId) {

    this.cargoStorageService.createRepo(repoId);
  }

  private static String normalizeName(final String name) {
    return name.toLowerCase().replace('-', '_');
  }
}
