package io.repsy.protocols.cargo.shared.crate.services;

import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@NullMarked
public interface CargoCrateService<ID> {

  void publish(BaseRepoInfo<ID> repoInfo, CratePublishRequest request);

  void yank(BaseRepoInfo<ID> repoInfo, String name, String vers);

  void unyank(BaseRepoInfo<ID> repoInfo, String name, String vers);

  void deleteCrate(BaseRepoInfo<ID> repoInfo, String name);

  void deleteCrateVersion(BaseRepoInfo<ID> repoInfo, String name, String vers);

  List<CrateIndexEntry> getIndexEntries(BaseRepoInfo<ID> repoInfo, String name);

  CrateInfo getCrate(BaseRepoInfo<ID> repoInfo, String name);

  CrateVersionInfo getCrateVersion(BaseRepoInfo<ID> repoInfo, String name, String vers);

  Page<CrateListItem> search(BaseRepoInfo<ID> repoInfo, String query, Pageable pageable);

  CrateIndexEntry getIndexEntry(BaseRepoInfo<ID> repoInfo, String name, String vers);
}
