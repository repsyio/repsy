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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.BaseCrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@NullMarked
public interface CargoCrateService<ID> {

  /** Publishes without an edition (RPS-1141): kept for callers that have none to report. */
  default void publish(final BaseRepoInfo<ID> repoInfo, final CratePublishRequest request) {
    this.publish(repoInfo, request, null);
  }

  /**
   * @param edition The {@code edition} key the crate's {@code Cargo.toml} declares, or {@code null}
   *     when it has none or the value did not fit {@code cargo_crate_meta.edition} (RPS-1141).
   */
  void publish(BaseRepoInfo<ID> repoInfo, CratePublishRequest request, @Nullable String edition);

  /**
   * Records the crate version and, while that write is still open, stores its files through {@code
   * filesWriter} (RPS-1124).
   *
   * <p>The rows are written first (and flushed, so a unique-index conflict surfaces here) and the
   * files second, inside one transaction. If the rows cannot be written, storage is never touched,
   * so a publish that loses a race for a version cannot replace the winner's crate file or append a
   * second index line. If the files cannot be written, the rows are rolled back.
   *
   * @param edition see {@link #publish(BaseRepoInfo, CratePublishRequest, String)}
   * @return the usages reported by {@code filesWriter}
   * @throws io.repsy.core.error_handling.exceptions.ItemAlreadyExistException when the version is
   *     already published, including when a concurrent publish of it won the race
   */
  BaseUsages publish(
      BaseRepoInfo<ID> repoInfo,
      CratePublishRequest request,
      @Nullable String edition,
      CrateFilesWriter filesWriter)
      throws IOException;

  void yank(BaseRepoInfo<ID> repoInfo, String name, String vers);

  void unyank(BaseRepoInfo<ID> repoInfo, String name, String vers);

  void deleteCrate(BaseRepoInfo<ID> repoInfo, String name);

  void deleteCrateVersion(BaseRepoInfo<ID> repoInfo, String name, String vers);

  void incrementDownloadCount(BaseRepoInfo<ID> repoInfo, String crateName, String version);

  List<CrateIndexEntry> getIndexEntries(BaseRepoInfo<ID> repoInfo, String name);

  BaseCrateInfo<ID> getCrate(BaseRepoInfo<ID> repoInfo, String name);

  BaseCrateVersionInfo<ID> getCrateVersion(BaseRepoInfo<ID> repoInfo, String name, String vers);

  Page<CrateListItem> search(BaseRepoInfo<ID> repoInfo, String query, Pageable pageable);

  /** Stores the files of a version whose rows {@link #publish} has just written. */
  @FunctionalInterface
  interface CrateFilesWriter {

    /**
     * Writes the crate file and its index line. The version is always new: Cargo never replaces a
     * published version, so a writer that fails may remove whatever it has written.
     */
    BaseUsages write() throws IOException;
  }
}
