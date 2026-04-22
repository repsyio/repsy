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
package io.repsy.protocols.cargo.shared.crate.services;

import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
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

  void incrementDownloadCount(BaseRepoInfo<ID> repoInfo, String crateName, String version);

  List<CrateIndexEntry> getIndexEntries(BaseRepoInfo<ID> repoInfo, String name);

  BaseCrateInfo<ID> getCrate(BaseRepoInfo<ID> repoInfo, String name);

  BaseCrateVersionInfo<ID> getCrateVersion(BaseRepoInfo<ID> repoInfo, String name, String vers);

  Page<CrateListItem> search(BaseRepoInfo<ID> repoInfo, String query, Pageable pageable);
}
